package org.bolcr.letmeknow.radio

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService

/**
 * Pauses a video playing behind while the message window covers the screen, and plays it again when the window
 * closes; music keeps playing. Android lets an app see what is playing only if it may read notifications, so on the
 * radio LetMeKnow asks for that once ("Pausar el video"); it reads none of them, this service is empty.
 */
class MediaWatch : NotificationListenerService() {
    companion object {
        /** The video apps paused when the window opened, to play them again when it closes. */
        private val paused = ArrayList<MediaController>()

        /** Video apps; music, radio and Bluetooth audio go on. */
        private val video = listOf(
            "com.google.android.youtube", "com.netflix.mediaclient", "com.disney.disneyplus", "com.amazon.avod.thirdpartyclient",
            "com.hbo.hbonow", "com.wbd.stream", "org.videolan.vlc", "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro",
            "com.plexapp.android", "com.google.android.videos", "tv.twitch.android.app", "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill", "com.instagram.android", "com.facebook.katana", "com.vimeo.android.videoapp",
            "com.apple.atve.androidtv.appletv", "com.star.plus", "com.paramount.android.pplus",
        )

        private fun isVideo(pkg: String): Boolean {
            val p = pkg.lowercase()
            if ("music" in p || "radio" in p || "spotify" in p || "podcast" in p || "bluetooth" in p) return false
            return p in video || "video" in p || "movie" in p
        }

        fun allowed(c: Context): Boolean = runCatching {
            val m = c.getSystemService(MediaSessionManager::class.java)
            m.getActiveSessions(ComponentName(c, MediaWatch::class.java))
            true
        }.getOrDefault(false)

        /** The window opened: videos playing now pause. */
        fun pauseVideos(c: Context) {
            paused.clear()
            runCatching {
                val m = c.getSystemService(MediaSessionManager::class.java)
                for (s in m.getActiveSessions(ComponentName(c, MediaWatch::class.java))) {
                    if (s.playbackState?.state == PlaybackState.STATE_PLAYING && isVideo(s.packageName)) {
                        s.transportControls.pause()
                        paused += s
                    }
                }
            }
        }

        /** The window closed: what was paused plays again. */
        fun resumeVideos() {
            paused.forEach { runCatching { it.transportControls.play() } }
            paused.clear()
        }
    }
}
