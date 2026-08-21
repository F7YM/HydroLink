package com.hydro.station.server.service

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
}
