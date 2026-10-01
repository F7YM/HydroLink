package com.hydro.link.server.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LockAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op; we only need global actions and gestures
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.DEFAULT
        serviceInfo = info
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Perform lock screen action.
     * @return true if successful
     */
    fun lockScreen(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    /**
     * Perform a global navigation action (back, home, recents, notifications, etc.).
     */
    fun performGlobal(action: Int): Boolean {
        return performGlobalAction(action)
    }

    /**
     * Dispatch a tap/click gesture at screen coordinates (x, y).
     */
    fun dispatchTap(x: Float, y: Float, durationMs: Long = 50): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSync(gesture, durationMs + 300)
    }

    /**
     * Dispatch a swipe/drag gesture from (x1, y1) to (x2, y2).
     */
    fun dispatchSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSync(gesture, durationMs + 500)
    }

    private fun dispatchGestureSync(gesture: GestureDescription, timeoutMs: Long): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return dispatchGesture(gesture, null, null)
        }
        val latch = CountDownLatch(1)
        var success = false
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                success = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                success = false
                latch.countDown()
            }
        }, null)
        if (!dispatched) return false
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            success
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        @Volatile
        var instance: LockAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
