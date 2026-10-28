package com.remotedroid.app.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to your relay over a WebSocket, and reconnects by itself if the link
 * drops (Wi-Fi change, relay restart, phone sleep). Both this device and your
 * browser connect to the same room; the relay forwards between them.
 *
 * Text frames carry JSON control; binary frames carry H.264 video (device ->
 * browser) and file chunks (browser -> device). Auth is peer-to-peer: the relay
 * never sees your token.
 */
class RelayClient(
    private val url: String,
    private val token: String,
    private val listener: Events
) : WebSocketListener() {

    interface Events {
        fun onControllerAuthenticated()
        fun onControlMessage(msg: JSONObject)
        /** Binary payload from the controller (file upload chunks). */
        fun onBinary(data: ByteArray)
        fun onOpen()
        fun onClosed(reason: String)
        fun onFailure(t: Throwable)
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val reconnect = Handler(Looper.getMainLooper())
    @Volatile private var ws: WebSocket? = null
    @Volatile private var authed = false
    @Volatile private var shouldRun = false
    private var backoffMs = 1_000L

    fun connect() {
        shouldRun = true
        open()
    }

    private fun open() {
        if (!shouldRun) return
        authed = false
        ws = client.newWebSocket(Request.Builder().url(url).build(), this)
    }

    fun close() {
        shouldRun = false
        reconnect.removeCallbacksAndMessages(null)
        authed = false
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
    }

    private fun scheduleReconnect() {
        if (!shouldRun) return
        listener.onClosed("reconnecting")
        reconnect.postDelayed({ open() }, backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
    }

    fun sendJson(obj: JSONObject) { ws?.send(obj.toString()) }

    fun sendVideo(data: ByteArray) {
        if (!authed) return
        ws?.send(data.toByteString(0, data.size))
    }

    val isControllerAuthenticated: Boolean get() = authed
    fun queuedBytes(): Long = ws?.queueSize() ?: 0L

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.i(TAG, "relay connected")
        backoffMs = 1_000L
        sendJson(JSONObject().put("t", "hello").put("role", "device"))
        listener.onOpen()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        when (msg.optString("t")) {
            "auth" -> {
                if (msg.optString("token") == token) {
                    authed = true
                    sendJson(JSONObject().put("t", "auth_ok"))
                    listener.onControllerAuthenticated()
                } else {
                    sendJson(JSONObject().put("t", "auth_fail"))
                }
            }
            else -> if (authed) listener.onControlMessage(msg)
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        if (authed) listener.onBinary(bytes.toByteArray())
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        authed = false
        webSocket.close(1000, null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        authed = false
        scheduleReconnect()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        authed = false
        Log.w(TAG, "relay failure: ${t.message}")
        listener.onFailure(t)
        scheduleReconnect()
    }

    companion object { private const val TAG = "RelayClient" }
}
