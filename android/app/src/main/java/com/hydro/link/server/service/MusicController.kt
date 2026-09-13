package com.hydro.link.server.service

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent

/**
 * Controls media playback on the Android device.
 *
 * Detection: Uses MusicNotificationListener's cached info (real-time via callbacks)
 *           as primary source, falls back to MediaSessionManager.getActiveSessions().
 *
 * Control: Tries transport controls first, falls back to AudioManager.dispatchMediaKeyEvent().
 */
class MusicController(private val context: Context) {

    private val sessionManager: MediaSessionManager? by lazy {
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    }

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /**
     * Get info about the currently active media session.
     * Priority:
     *   1. MusicNotificationListener cached info (real-time, most reliable)
     *   2. MediaSessionManager.getActiveSessions(component)
     */
    fun getActiveMediaInfo(): MediaInfo? {
        // 1. Try cached info from NotificationListener first
        val cached = MusicNotificationListener.cachedMediaInfo
        if (cached != null) return cached

        // 2. Fall back to querying active sessions
        val controllers = getActiveControllers() ?: return null
        if (controllers.isEmpty()) return null

        for (controller in controllers) {
            val metadata = controller.metadata ?: continue
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: continue
            if (title.isBlank()) continue

            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: ""

            val state = controller.playbackState
            val isPlaying = state != null && state.state == PlaybackState.STATE_PLAYING

            return MediaInfo(
                title = title,
                artist = artist,
                playing = isPlaying,
                hasMedia = true
            )
        }
        return null
    }

    /**
     * 当前封面的位图（没有则 null）。
     *
     * 来源与桌面不同但语义相同：安卓的封面在 MediaSession 的 metadata 里
     * （METADATA_KEY_ALBUM_ART / ART），通知使用权给了我们读取它的权限；
     * 少数播放器只给 URI（ALBUM_ART_URI / ART_URI），那就解一次。
     *
     * 结果按「歌曲 + 歌手」缓存：`/api/music/art` 会被手表反复请求（背景图），
     * 每次都去解 URI 是浪费。
     */
    fun albumArt(): AlbumArt? {
        val info = getActiveMediaInfo() ?: run {
            artCache = null
            return null
        }
        val key = "${info.title}|${info.artist}"
        artCache?.let { if (it.first == key) return it.second }

        val art = loadAlbumArt()
        artCache = key to art
        return art
    }

    /** 有没有可用封面——ABI 上下文据此决定要不要给插件 artUrl/artBlur。 */
    fun hasAlbumArt(): Boolean = albumArt() != null

    private fun loadAlbumArt(): AlbumArt? {
        val controller = findBestController(getActiveControllers() ?: return null) ?: return null
        val metadata = controller.metadata ?: return null
        for (k in ART_KEYS) {
            val bmp = metadata.getBitmap(k)
            if (bmp != null && bmp.width > 0 && bmp.height > 0) return AlbumArt(bmp, "$k")
        }
        for (k in ART_URI_KEYS) {
            val uriStr = metadata.getString(k) ?: continue
            val bmp = decodeUri(uriStr) ?: continue
            return AlbumArt(bmp, uriStr)
        }
        return null
    }

    private fun decodeUri(uriStr: String): android.graphics.Bitmap? = try {
        val uri = android.net.Uri.parse(uriStr)
        when (uri.scheme?.lowercase()) {
            "content", "android.resource" ->
                android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            "file" -> android.graphics.BitmapFactory.decodeFile(uri.path)
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /** 一张封面：位图 + 来源描述（用于缓存/日志）。 */
    data class AlbumArt(val bitmap: android.graphics.Bitmap, val source: String)

    /**
     * Send a media control action.
     * Tries transport controls (from cached controller or getActiveSessions),
     * falls back to AudioManager key events.
     */
    fun control(action: String): Boolean {
        // Try transport controls via active sessions
        val controllers = getActiveControllers()
        if (controllers != null && controllers.isNotEmpty()) {
            val controller = findBestController(controllers)
            if (controller != null) {
                val tc = controller.transportControls
                if (tc != null) {
                    when (action.lowercase()) {
                        "play" -> { tc.play(); return true }
                        "pause" -> { tc.pause(); return true }
                        "playpause" -> {
                            val state = controller.playbackState
                            if (state != null && state.state == PlaybackState.STATE_PLAYING) {
                                tc.pause()
                            } else {
                                tc.play()
                            }
                            return true
                        }
                        "next" -> { tc.skipToNext(); return true }
                        "previous" -> { tc.skipToPrevious(); return true }
                    }
                }
            }
        }
        // Fallback: AudioManager key events
        return dispatchMediaKey(action)
    }

    private fun getActiveControllers(): List<MediaController>? {
        val sm = sessionManager ?: return null
        val component = MusicNotificationListener.getComponentName(context)
        return try {
            sm.getActiveSessions(component)
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

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
     * Fallback media control via AudioManager key events.
     */
    private fun dispatchMediaKey(action: String): Boolean {
        val keycode = when (action.lowercase()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return false
        }
        return try {
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, keycode)
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_UP, keycode)
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    data class MediaInfo(
        val title: String,
        val artist: String,
        val playing: Boolean,
        val hasMedia: Boolean
    )

    companion object {
        private const val TAG = "MusicController"

        /** metadata 里直接给位图的键（优先）。 */
        private val ART_KEYS = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        )

        /** 只给 URI 的键（少数播放器）。 */
        private val ART_URI_KEYS = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
        )
    }

    /** 封面缓存：歌曲变化才重新取（`null` 表示这首没有封面）。 */
    @Volatile
    private var artCache: Pair<String, AlbumArt?>? = null
}
