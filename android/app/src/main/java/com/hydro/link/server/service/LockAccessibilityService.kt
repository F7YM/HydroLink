package com.hydro.link.server.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent

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
        return dispatchGestureSync(gesture)
    }

    /**
     * Dispatch a double tap as **one** gesture with two sequential strokes.
     *
     * 两下之间的间隔由我们固定（默认 90ms），落在系统双击判定窗口内。
     * 之前是调用方循环 `dispatchTap`：每次都要等手势完成回调，两下实际间隔常 >300ms，
     * 目标 App 会当成两次单击（抖音里就表现为「点赞 -> 暂停」）。
     */
    fun dispatchDoubleTap(x: Float, y: Float, tapMs: Long = 45, gapMs: Long = 90): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path1 = Path().apply { moveTo(x, y) }
        val path2 = Path().apply { moveTo(x, y) }
        val first = GestureDescription.StrokeDescription(path1, 0, tapMs.coerceAtLeast(1))
        val second = GestureDescription.StrokeDescription(path2, tapMs + gapMs, tapMs.coerceAtLeast(1))
        val gesture = GestureDescription.Builder().addStroke(first).addStroke(second).build()
        return dispatchGestureSync(gesture)
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
        return dispatchGestureSync(gesture)
    }

    /**
     * 派发手势并同步返回结果。
     *
     * **不等待回调**：`dispatchGesture` 返回 true 就代表手势已被系统接受、会执行。
     * 回调在部分机型/固件上根本不来，之前等回调超时就返回 false，调用方会再走
     * shell 兜底（input tap/swipe）把同一动作执行第二遍（"点一下动两下"），
     * 而且每次点击都白等一个超时。这里只要派发成功即视为成功。
     */
    private fun dispatchGestureSync(gesture: GestureDescription): Boolean {
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        @Volatile
        var instance: LockAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
