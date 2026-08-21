package com.hydro.station.server.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent

class LockAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op; we only need global actions
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

    companion object {
        @Volatile
        var instance: LockAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
