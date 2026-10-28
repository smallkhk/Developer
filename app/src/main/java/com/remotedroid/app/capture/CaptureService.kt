package com.remotedroid.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.remotedroid.app.R
import com.remotedroid.app.input.RemoteInputService
import com.remotedroid.app.net.RelayClient
import com.remotedroid.app.ui.MainActivity
import org.json.JSONObject

/**
 * The always-on piece. Holds the MediaProjection, the relay connection and the
 * encoder, and routes control messages from the browser to the input service.
 *
 * Pause/resume only tears down the encoder, never the MediaProjection, so you
 * can resume from the browser with no on-phone tap. A full Stop (or reboot)
 * releases the projection and does require re-approving capture on the phone.
 */
class CaptureService : Service(), RelayClient.Events {

    private lateinit var projectionManager: MediaProjectionManager
    private var projection: MediaProjection? = null
    private var encoder: ScreenEncoder? = null
    private var relay: RelayClient? = null

    private var screenW = 0
    private var screenH = 0
    private var screenDpi = 0
    // Encoder resolution — scaled down from the real screen to save bandwidth.
    private var encodeW = 0
    private var encodeH = 0

    @Volatile private var paused = false
    private var cachedConfig: ByteArray? = null
    // Frame-drop gate: when the send buffer backs up we stop sending delta
    // frames and jump to the next keyframe, so lag can't pile up.
    @Volatile private var droppingUntilKeyframe = false
    private val main = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "projection stopped by system/user")
            stopEverything()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        readScreenMetrics()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // Must be a foreground service BEFORE we obtain the MediaProjection.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, buildNotification("Connecting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, buildNotification("Connecting…"))
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val relayUrl = intent?.getStringExtra(EXTRA_RELAY_URL).orEmpty()
        val token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()

        if (resultCode != 0 && data != null && projection == null) {
            projection = projectionManager.getMediaProjection(resultCode, data).also {
                it.registerCallback(projectionCallback, main)
            }
        }

        if (relay == null && relayUrl.isNotBlank()) {
            relay = RelayClient(relayUrl, token, this).also { it.connect() }
        }
        return START_STICKY
    }

    // --- RelayClient.Events ---------------------------------------------------

    override fun onOpen() = updateNotification("Waiting for browser…")

    override fun onControllerAuthenticated() {
        main.post {
            updateNotification("Connected — streaming")
            startEncoderIfNeeded()
            // Tell the browser the real pixel size so it can map clicks.
            relay?.sendJson(
                JSONObject().put("t", "meta").put("w", screenW).put("h", screenH)
            )
            sendState()
        }
    }

    override fun onControlMessage(msg: JSONObject) {
        when (msg.optString("t")) {
            "tap" -> input()?.tap(px(msg, "x"), py(msg, "y"))
            "longpress" -> input()?.longPress(px(msg, "x"), py(msg, "y"))
            "swipe" -> input()?.swipe(
                px(msg, "x1"), py(msg, "y1"), px(msg, "x2"), py(msg, "y2"),
                msg.optLong("ms", 250)
            )
            "key" -> input()?.globalKey(msg.optString("k"))
            "text" -> input()?.setText(msg.optString("s"))
            "pause" -> main.post { pauseCapture() }
            "resume" -> main.post { resumeCapture() }
            "keyframe" -> encoder?.requestKeyFrame()
            "state" -> sendState()
        }
    }

    override fun onClosed(reason: String) = updateNotification("Disconnected")
    override fun onFailure(t: Throwable) = updateNotification("Reconnecting…")

    // --- capture control ------------------------------------------------------

    private fun startEncoderIfNeeded() {
        if (encoder != null || paused) return
        val proj = projection ?: return
        cachedConfig = null
        droppingUntilKeyframe = false
        encoder = ScreenEncoder(
            proj, encodeW, encodeH, screenDpi,
            bitrate = BITRATE, fps = FPS
        ) { data, isConfig, isKey ->
            // Prefix: 0 = config (SPS/PPS), 1 = keyframe, 2 = delta frame.
            if (isConfig) {
                cachedConfig = data
                sendFramed(0, data)
                return@ScreenEncoder
            }

            val backedUp = (relay?.queuedBytes() ?: 0L) > MAX_QUEUE_BYTES
            if (isKey) {
                // A keyframe is a clean restart point — always send it and
                // clear any drop state.
                droppingUntilKeyframe = false
                sendFramed(1, data)
            } else if (droppingUntilKeyframe) {
                // Waiting for the next keyframe; skip stale deltas.
                return@ScreenEncoder
            } else if (backedUp) {
                // Network is behind: drop this delta and ask for a fresh
                // keyframe so the browser jumps straight to the latest screen.
                droppingUntilKeyframe = true
                encoder?.requestKeyFrame()
            } else {
                sendFramed(2, data)
            }
        }.also { it.start() }
    }

    /** Send one video unit with a 1-byte type header (0 config, 1 key, 2 delta). */
    private fun sendFramed(type: Int, data: ByteArray) {
        val out = ByteArray(data.size + 1)
        out[0] = type.toByte()
        System.arraycopy(data, 0, out, 1, data.size)
        relay?.sendVideo(out)
    }

    private fun pauseCapture() {
        if (paused) return
        paused = true
        encoder?.stop()
        encoder = null
        updateNotification("Paused")
        sendState()
    }

    private fun resumeCapture() {
        if (!paused) return
        paused = false
        startEncoderIfNeeded()
        encoder?.requestKeyFrame()
        updateNotification("Connected — streaming")
        sendState()
    }

    private fun sendState() {
        relay?.sendJson(
            JSONObject().put("t", "state")
                .put("capturing", projection != null)
                .put("paused", paused)
        )
    }

    private fun stopEverything() {
        try { encoder?.stop() } catch (_: Exception) {}
        encoder = null
        try { relay?.close() } catch (_: Exception) {}
        relay = null
        try {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        } catch (_: Exception) {}
        projection = null
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- helpers --------------------------------------------------------------

    private fun input(): RemoteInputService? = RemoteInputService.instance

    private fun px(msg: JSONObject, key: String) =
        (msg.optDouble(key, 0.0).toFloat()).coerceIn(0f, 1f) * screenW
    private fun py(msg: JSONObject, key: String) =
        (msg.optDouble(key, 0.0).toFloat()).coerceIn(0f, 1f) * screenH

    private fun readScreenMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenW = bounds.width()
            screenH = bounds.height()
            screenDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenW = metrics.widthPixels
            screenH = metrics.heightPixels
            screenDpi = metrics.densityDpi
        }
        // H.264 encoders want even dimensions.
        screenW -= screenW % 2
        screenH -= screenH % 2

        // Scale down for streaming: cap the shorter side to MAX_SHORT_SIDE,
        // keeping aspect ratio. Input stays mapped via normalized 0..1 coords,
        // so this doesn't affect tap accuracy.
        val shortSide = minOf(screenW, screenH)
        val scale = if (shortSide > MAX_SHORT_SIDE) MAX_SHORT_SIDE.toFloat() / shortSide else 1f
        encodeW = ((screenW * scale).toInt()).let { it - it % 2 }
        encodeH = ((screenH * scale).toInt()).let { it - it % 2 }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, "Remote session", NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("RemoteDroid")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL = "remote_session"
        private const val NOTIF_ID = 42

        // Streaming tuning. Lower MAX_SHORT_SIDE/BITRATE for slower links.
        private const val MAX_SHORT_SIDE = 720
        private const val BITRATE = 2_500_000
        private const val FPS = 30
        // Drop frames once this many bytes are waiting to be sent.
        private const val MAX_QUEUE_BYTES = 512_000L

        const val ACTION_STOP = "com.remotedroid.app.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_RELAY_URL = "relay_url"
        const val EXTRA_TOKEN = "token"

        fun start(ctx: Context, resultCode: Int, data: Intent, relayUrl: String, token: String) {
            val i = Intent(ctx, CaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
                .putExtra(EXTRA_RELAY_URL, relayUrl)
                .putExtra(EXTRA_TOKEN, token)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.startService(
                Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
