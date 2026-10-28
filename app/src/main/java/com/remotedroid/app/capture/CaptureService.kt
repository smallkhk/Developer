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

    // Current streaming quality (changeable live from the browser).
    private var curLevel = "medium"
    private var curMaxShort = DEFAULT_MAX_SHORT_SIDE
    private var curBitrate = DEFAULT_BITRATE
    private var curFps = DEFAULT_FPS

    // Auto quality: step down when congested, back up when it stays clear.
    @Volatile private var autoQuality = false
    @Volatile private var dropsSinceCheck = 0
    private var clearChecks = 0
    private val autoRunnable = object : Runnable {
        override fun run() {
            if (autoQuality) evaluateAutoQuality()
            maybeSendStatus()
            main.postDelayed(this, AUTO_INTERVAL_MS)
        }
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private val fileReceiver by lazy { FileReceiver(this) }
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
        main.postDelayed(autoRunnable, AUTO_INTERVAL_MS)
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
        acquireWakeLock()
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
            "quality" -> main.post { setQuality(msg.optString("level", "medium")) }
            "clipboard" -> main.post { setClipboard(msg.optString("s")) }
            "pinch" -> input()?.pinch(px(msg, "x"), py(msg, "y"), msg.optBoolean("out", true))
            "applist" -> sendAppList()
            "launch" -> launchApp(msg.optString("pkg"))
            "file_begin" -> {
                val ok = fileReceiver.begin(msg.optString("name"), msg.optLong("size", 0))
                if (!ok) relay?.sendJson(JSONObject().put("t", "file_error"))
            }
            "file_end" -> {
                val name = fileReceiver.end()
                relay?.sendJson(
                    JSONObject().put("t", if (name != null) "file_done" else "file_error")
                        .put("name", name ?: "")
                )
            }
            "file_abort" -> fileReceiver.abort()
        }
    }

    override fun onBinary(data: ByteArray) {
        // Inbound binary is file-upload data for the active transfer.
        fileReceiver.chunk(data)
    }

    /** Apply a quality preset. "auto" lets the app pick and adapt. */
    private fun setQuality(level: String) {
        if (level == "auto") {
            autoQuality = true
            applyLevel("medium")
        } else {
            autoQuality = false
            applyLevel(level)
        }
    }

    private val levels = listOf("low", "medium", "high")

    private fun applyLevel(level: String) {
        if (level == curLevel && encoder != null) return
        curLevel = level
        when (level) {
            "low" -> { curMaxShort = 480; curBitrate = 1_000_000; curFps = 20 }
            "high" -> { curMaxShort = 1080; curBitrate = 5_000_000; curFps = 30 }
            else -> { curMaxShort = 720; curBitrate = 2_500_000; curFps = 30 }
        }
        restartEncoder()
    }

    /** Restart the encoder in place (new resolution/fps) without touching the
     *  MediaProjection, so no re-approval is needed. */
    private fun restartEncoder() {
        if (paused || encoder == null) return
        encoder?.stop()
        encoder = null
        startEncoderIfNeeded()
        encoder?.requestKeyFrame()
    }

    private fun evaluateAutoQuality() {
        val drops = dropsSinceCheck
        dropsSinceCheck = 0
        val idx = levels.indexOf(curLevel).coerceAtLeast(0)
        if (drops > 2 && idx > 0) {
            clearChecks = 0
            applyLevel(levels[idx - 1])          // congested: step down
            relay?.sendJson(JSONObject().put("t", "autoq").put("level", curLevel))
        } else if (drops == 0) {
            clearChecks++
            if (clearChecks >= 4 && idx < levels.size - 1) {   // stable: step up
                clearChecks = 0
                applyLevel(levels[idx + 1])
                relay?.sendJson(JSONObject().put("t", "autoq").put("level", curLevel))
            }
        } else {
            clearChecks = 0
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Screen likely rotated: re-read size and restart the encoder so the
        // browser gets correctly-shaped frames.
        val oldW = screenW; val oldH = screenH
        readScreenMetrics()
        if ((screenW != oldW || screenH != oldH) && !paused && encoder != null) {
            restartEncoder()
            relay?.sendJson(JSONObject().put("t", "meta").put("w", screenW).put("h", screenH))
        }
    }

    /** Send the list of launchable apps to the browser. */
    private fun sendAppList() {
        try {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
            val arr = org.json.JSONArray()
            for ((pkg, label) in apps) {
                arr.put(JSONObject().put("pkg", pkg).put("name", label))
            }
            relay?.sendJson(JSONObject().put("t", "applist").put("apps", arr))
        } catch (e: Exception) { Log.w(TAG, "applist failed", e) }
    }

    /** Launch an app by package name. */
    private fun launchApp(pkg: String) {
        if (pkg.isBlank()) return
        try {
            val i = packageManager.getLaunchIntentForPackage(pkg)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i != null) startActivity(i)
        } catch (e: Exception) { Log.w(TAG, "launch failed", e) }
    }

    /** Periodically report battery + charging state to the browser. */
    private fun maybeSendStatus() {
        val relay = relay ?: return
        if (!relay.isControllerAuthenticated) return
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            relay.sendJson(
                JSONObject().put("t", "status").put("battery", level).put("charging", charging)
            )
        } catch (e: Exception) { /* ignore */ }
    }

    /** Best-effort: put text on the phone's clipboard. */
    private fun setClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("RemoteDroid", text))
        } catch (e: Exception) { Log.w(TAG, "clipboard set failed", e) }
    }

    override fun onClosed(reason: String) = updateNotification("Disconnected")
    override fun onFailure(t: Throwable) = updateNotification("Reconnecting…")

    // --- capture control ------------------------------------------------------

    private fun startEncoderIfNeeded() {
        if (encoder != null || paused) return
        val proj = projection ?: return
        cachedConfig = null
        droppingUntilKeyframe = false
        computeEncodeDims(curMaxShort)
        encoder = ScreenEncoder(
            proj, encodeW, encodeH, screenDpi,
            bitrate = curBitrate, fps = curFps
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
                dropsSinceCheck++
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

    /** Keep the CPU alive so the stream and reconnect survive doze/sleep. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK, "RemoteDroid:session"
        ).also { it.setReferenceCounted(false); it.acquire() }
    }

    private fun stopEverything() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        try { fileReceiver.abort() } catch (_: Exception) {}
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
        main.removeCallbacks(autoRunnable)
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

        computeEncodeDims(curMaxShort)
    }

    /** Scale the capture so the shorter side is at most [maxShort], keeping
     *  aspect ratio. Input stays mapped via normalized 0..1 coords, so this
     *  never affects tap accuracy. */
    private fun computeEncodeDims(maxShort: Int) {
        val shortSide = minOf(screenW, screenH).coerceAtLeast(1)
        val scale = if (shortSide > maxShort) maxShort.toFloat() / shortSide else 1f
        encodeW = ((screenW * scale).toInt()).let { it - it % 2 }.coerceAtLeast(2)
        encodeH = ((screenH * scale).toInt()).let { it - it % 2 }.coerceAtLeast(2)
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

        // Default streaming tuning (changeable live via the quality control).
        private const val DEFAULT_MAX_SHORT_SIDE = 720
        private const val DEFAULT_BITRATE = 2_500_000
        private const val DEFAULT_FPS = 30
        // Drop frames once this many bytes are waiting to be sent.
        private const val MAX_QUEUE_BYTES = 512_000L
        // How often auto-quality reviews congestion.
        private const val AUTO_INTERVAL_MS = 3_000L

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
