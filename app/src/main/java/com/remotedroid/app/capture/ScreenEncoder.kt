package com.remotedroid.app.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Captures the screen through a [MediaProjection] and encodes it to H.264,
 * handing each encoded chunk (SPS/PPS config + frames) to [onEncoded] as raw
 * Annex-B bytes the browser can feed straight into a WebCodecs VideoDecoder.
 *
 * It owns a VirtualDisplay + MediaCodec. Calling [stop] tears those down but
 * does NOT release the MediaProjection — that stays alive in CaptureService so
 * a resume needs no new on-phone permission prompt.
 */
class ScreenEncoder(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private var bitrate: Int,
    private var fps: Int,
    /** (data, isConfig, isKeyFrame) in raw Annex-B bytes. */
    private val onEncoded: (ByteArray, Boolean, Boolean) -> Unit
) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 2f)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
        }
        val c = MediaCodec.createEncoderByType(MIME)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = c.createInputSurface()
        c.start()
        codec = c

        virtualDisplay = projection.createVirtualDisplay(
            "RemoteDroid",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )

        running = true
        thread = Thread({ drainLoop() }, "screen-encoder").also { it.start() }
    }

    /** Ask the encoder for a fresh keyframe (e.g. when a new browser connects). */
    fun requestKeyFrame() {
        val c = codec ?: return
        val params = android.os.Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        }
        try { c.setParameters(params) } catch (e: Exception) { Log.w(TAG, "keyframe req failed", e) }
    }

    private fun drainLoop() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = c.dequeueOutputBuffer(info, 10_000)
                if (idx >= 0) {
                    val buf: ByteBuffer? = c.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val out = ByteArray(info.size)
                        buf.get(out)
                        val isConfig =
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKey =
                            (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        onEncoded(out, isConfig, isKey)
                    }
                    c.releaseOutputBuffer(idx, false)
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "drain loop error", e)
        }
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (_: InterruptedException) {}
        thread = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
    }

    companion object {
        private const val TAG = "ScreenEncoder"
        private const val MIME = "video/avc"
    }
}
