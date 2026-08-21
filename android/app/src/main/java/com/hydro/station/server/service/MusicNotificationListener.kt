package com.hydro.station.server.service

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Notification listener service that enables media session access.
 * When connected, it finds active media sessions and registers callbacks
 * to track playback state and metadata changes in real-time.
 *
 * Must be enabled by the user in system settings: Settings > Notification access.
 */
class MusicNotificationListener : NotificationListenerService() {

    private var mediaController: MediaController? = null
    private var currentPackage: String? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            Log.d(TAG, "Playback state changed: ${state?.state}")
            updateCachedInfo()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            Log.d(TAG, "Metadata changed")
            updateCachedInfo()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "通知监听服务已连接")
        checkActiveMediaSessions()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        handleMediaNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn != null && isMediaNotification(sbn)) {
            if (mediaController != null && sbn.packageName == currentPackage) {
                mediaController?.unregisterCallback(controllerCallback)
                mediaController = null
                currentPackage = null
                cachedMediaInfo = null
                Log.d(TAG, "媒体会话已断开: ${sbn.packageName}")
            }
        }
    }

    /**
     * Check active media sessions when the listener connects.
     * Uses MediaSessionManager.getActiveSessions() with our own component name,
     * which works because we're now an enabled notification listener.
     */
    private fun checkActiveMediaSessions() {
        try {
            val sm = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            if (sm != null) {
                val component = ComponentName(this, MusicNotificationListener::class.java)
                val controllers = sm.getActiveSessions(component)
                Log.d(TAG, "找到 ${controllers.size} 个活跃媒体会话")
                if (controllers.isNotEmpty()) {
                    val best = findBestController(controllers)
                    if (best != null) {
                        attachController(best)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查活跃媒体会话失败", e)
        }
    }

    /**
     * Handle a new notification - check if it's a media notification.
     */
    private fun handleMediaNotification(sbn: StatusBarNotification?) {
        if (sbn == null || !isMediaNotification(sbn)) return
        if (sbn.packageName == currentPackage) return

        try {
            val extras = sbn.notification.extras
            val token = extras.getParcelable<android.media.session.MediaSession.Token>(
                android.app.Notification.EXTRA_MEDIA_SESSION
            )
            if (token != null) {
                val controller = MediaController(this, token)
                attachController(controller, sbn.packageName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理媒体通知时出错", e)
        }
    }

    /**
     * Attach to a media controller and register callbacks.
     */
    private fun attachController(controller: MediaController, packageName: String? = null) {
        // Unregister previous controller
        mediaController?.unregisterCallback(controllerCallback)

        mediaController = controller
        currentPackage = packageName ?: controller.packageName
        controller.registerCallback(controllerCallback)
        Log.d(TAG, "已连接到媒体会话: $currentPackage")
        updateCachedInfo()
    }

    /**
     * Find the best controller: prefer playing, then with metadata.
     */
    private fun findBestController(controllers: List<MediaController>): MediaController? {
        for (c in controllers) {
            val state = c.playbackState
            if (state != null && state.state == PlaybackState.STATE_PLAYING) return c
        }
        for (c in controllers) {
            if (c.metadata != null) return c
        }
        return controllers.firstOrNull()
    }

    /**
     * Update the cached media info from the current controller.
     */
    private fun updateCachedInfo() {
        val controller = mediaController ?: run {
            cachedMediaInfo = null
            return
        }
        val metadata = controller.metadata
        if (metadata == null) {
            cachedMediaInfo = null
            return
        }
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (title.isNullOrBlank()) {
            cachedMediaInfo = null
            return
        }
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val state = controller.playbackState
        val isPlaying = state != null && state.state == PlaybackState.STATE_PLAYING

        cachedMediaInfo = MusicController.MediaInfo(
            title = title,
            artist = artist,
            playing = isPlaying,
            hasMedia = true
        )
        Log.d(TAG, "缓存音乐信息: $title - $artist (playing=$isPlaying)")
    }

    private fun isMediaNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification ?: return false
        return notification.extras != null &&
                notification.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)
    }

    companion object {
        private const val TAG = "MusicListener"

        @Volatile
        var instance: MusicNotificationListener? = null
            private set

        @Volatile
        var cachedMediaInfo: MusicController.MediaInfo? = null
            private set

        fun getComponentName(context: android.content.Context): ComponentName {
            return ComponentName(context, MusicNotificationListener::class.java)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        mediaController?.unregisterCallback(controllerCallback)
        mediaController = null
        cachedMediaInfo = null
        instance = null
        super.onDestroy()
    }
}
