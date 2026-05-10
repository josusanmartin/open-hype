package dev.josu.hypecar.auto.service

import android.content.Intent
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import dagger.hilt.android.AndroidEntryPoint
import dev.josu.hypecar.core.playback.HypePlaybackManager
import javax.inject.Inject

@AndroidEntryPoint
class HypeMediaLibraryService : MediaLibraryService() {
    @Inject lateinit var playbackManager: HypePlaybackManager

    @Inject lateinit var callback: HypeMediaLibraryCallback

    private var librarySession: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val session = MediaLibrarySession.Builder(this, playbackManager.player, callback).build()
        librarySession = session

        // Phone playback drives the shared player directly, so no browser
        // controller may bind before audio starts. Register the session here so
        // Media3 can own the foreground media notification for background play.
        addSession(session)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = librarySession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (playbackManager.player.playWhenReady) {
            return
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        librarySession?.let { session ->
            removeSession(session)
            session.release()
        }
        librarySession = null
        callback.close()
        super.onDestroy()
    }
}
