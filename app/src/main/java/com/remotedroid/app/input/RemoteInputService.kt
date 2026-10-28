package com.remotedroid.app.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

/**
 * Performs the remote input you send from the browser: taps, swipes, text into
 * the focused field, and the Back / Home / Recents hardware keys. You turn this
 * on once in Settings > Accessibility; it does nothing on its own, only what
 * the authenticated controller asks.
 */
class RemoteInputService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "accessibility connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not needed */ }
    override fun onInterrupt() {}

    /** Tap at absolute screen pixel (x, y). */
    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, 0, 50)
    }

    /** Long press at (x, y). */
    fun longPress(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, 0, 600)
    }

    /** Swipe from (x1,y1) to (x2,y2) over [durationMs]. */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        dispatch(path, 0, durationMs.coerceIn(20, 10_000))
    }

    private fun dispatch(path: Path, startTime: Long, durationMs: Long) {
        val stroke = GestureDescription.StrokeDescription(path, startTime, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /** One of: back, home, recents, notifications, power (lock screen via dialog). */
    fun globalKey(key: String) {
        val action = when (key) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "power" -> GLOBAL_ACTION_POWER_DIALOG
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> return
        }
        performGlobalAction(action)
    }

    /** Replace the text of the currently focused editable field. */
    fun setText(text: String) {
        val root = rootInActiveWindow ?: return
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    companion object {
        private const val TAG = "RemoteInputService"
        @Volatile var instance: RemoteInputService? = null
            private set

        val isRunning: Boolean get() = instance != null
    }
}
