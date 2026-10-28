package com.remotedroid.app.net

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
 * Talks to your relay over a WebSocket.
 *
 * The protocol is deliberately simple so almost any forwarding relay works:
 * this device and your browser both connect to the same URL (a "room"); the
 * relay just forwards messages between the two. Text frames carry JSON control
 * messages; binary frames carry the H.264 video the browser decodes.
 *
 * Auth is peer-to-peer: the relay never sees your token. The browser must send
 * the correct token before this device will stream or accept any input.
 */
class RelayClient(
    private val url: String,
    private val token: String,
    private val listener: Events
) : WebSocketListener() {

    interface Events {
        /** A controller (browser) authenticated successfully. */
        fun onControllerAuthenticated()
        /** A JSON control message arrived from the controller. */
        fun onControlMessage(msg: JSONObject)
        fun onOpen()
        fun onClosed(reason: String)
        fun onFailure(t: Throwable)
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var authed = false

    fun connect() {
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, this)
    }

    fun close() {
        authed = false
        ws?.close(1000, "bye")
        ws = null
    }

    /** Send a JSON control message to the controller. */
    fun sendJson(obj: JSONObject) {
        ws?.send(obj.toString())
    }

    /** Send a binary video frame to the controller (only once authenticated). */
    fun sendVideo(data: ByteArray) {
        if (!authed) return
        ws?.send(data.toByteString(0, data.size))
    }

    val isControllerAuthenticated: Boolean get() = authed

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.i(TAG, "relay connected")
        // Announce ourselves as the device side of the room.
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
            else -> {
                // All other control messages require an authenticated controller.
                if (authed) listener.onControlMessage(msg)
            }
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        // Device side does not expect binary input in v1.
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        authed = false
        webSocket.close(1000, null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        authed = false
        listener.onClosed(reason)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        authed = false
        Log.w(TAG, "relay failure", t)
        listener.onFailure(t)
    }

    companion object { private const val TAG = "RelayClient" }
}
