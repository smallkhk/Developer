package com.remotedroid.app.capture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Low-latency "Fast mode": streams the screen to the browser over a direct
 * WebRTC peer connection. The relay is used only for signaling (offer/answer/
 * ICE), so media travels phone<->browser directly when the network allows.
 *
 * The phone is the offerer. Signaling messages are plain JSON sent through the
 * existing relay connection via [signal].
 */
class WebRtcClient(
    private val context: Context,
    private val resultData: Intent,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val signal: (JSONObject) -> Unit,
    private val onProjectionStopped: () -> Unit
) {
    private val eglBase: EglBase = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    fun start() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        val encoder = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()

        capturer = ScreenCapturerAndroid(resultData, object : MediaProjection.Callback() {
            override fun onStop() { onProjectionStopped() }
        })
        surfaceHelper = SurfaceTextureHelper.create("RtcCapture", eglBase.eglBaseContext)
        videoSource = factory!!.createVideoSource(true /* isScreencast */)
        capturer!!.initialize(surfaceHelper, context, videoSource!!.capturerObserver)
        capturer!!.startCapture(width, height, fps)
        videoTrack = factory!!.createVideoTrack("screen", videoSource)

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        pc = factory!!.createPeerConnection(rtcConfig, pcObserver)
        pc!!.addTrack(videoTrack, listOf("stream0"))
        createOffer()
    }

    private fun createOffer() {
        val constraints = MediaConstraints()
        pc?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc?.setLocalDescription(SdpObserverAdapter(), sdp)
                signal(JSONObject().put("t", "rtc_offer").put("sdp", sdp.description))
            }
        }, constraints)
    }

    /** Apply the browser's answer SDP. */
    fun onAnswer(sdp: String) {
        pc?.setRemoteDescription(
            SdpObserverAdapter(),
            SessionDescription(SessionDescription.Type.ANSWER, sdp)
        )
    }

    /** Add a remote ICE candidate from the browser. */
    fun onRemoteIce(mid: String?, index: Int, candidate: String) {
        pc?.addIceCandidate(IceCandidate(mid, index, candidate))
    }

    fun stop() {
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { capturer?.dispose() } catch (_: Exception) {}
        try { videoSource?.dispose() } catch (_: Exception) {}
        try { surfaceHelper?.dispose() } catch (_: Exception) {}
        try { pc?.dispose() } catch (_: Exception) {}
        try { factory?.dispose() } catch (_: Exception) {}
        try { eglBase.release() } catch (_: Exception) {}
        capturer = null; videoSource = null; videoTrack = null
        surfaceHelper = null; pc = null; factory = null
    }

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            signal(
                JSONObject().put("t", "rtc_ice")
                    .put("candidate", c.sdp)
                    .put("sdpMid", c.sdpMid)
                    .put("sdpMLineIndex", c.sdpMLineIndex)
            )
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "pc state: $newState")
        }
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: org.webrtc.MediaStream) {}
        override fun onRemoveStream(s: org.webrtc.MediaStream) {}
        override fun onDataChannel(d: org.webrtc.DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    /** No-op SDP observer we can subclass where we only need one callback. */
    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { Log.w(TAG, "sdp create fail: $error") }
        override fun onSetFailure(error: String?) { Log.w(TAG, "sdp set fail: $error") }
    }

    companion object { private const val TAG = "WebRtcClient" }
}
