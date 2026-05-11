package dev.josu.hypecar.auto.service

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import dagger.hilt.android.AndroidEntryPoint
import dev.josu.hypecar.core.playback.HypePlaybackManager
import javax.inject.Inject

@AndroidEntryPoint
@androidx.annotation.OptIn(UnstableApi::class)
class HypeMediaLibraryService : MediaLibraryService() {
    @Inject lateinit var playbackManager: HypePlaybackManager

    @Inject lateinit var callback: HypeMediaLibraryCallback

    @Inject lateinit var okHttpBitmapLoader: OkHttpBitmapLoader

    private var librarySession: MediaLibrarySession? = null

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Route remote artwork fetches through our app's already-trusted
        // OkHttpClient (see OkHttpBitmapLoader docstring for why the bundled
        // SimpleBitmapLoader fails with "Chain validation failed" on stripped
        // trust stores like the AAOS_API_35 emulator). CacheBitmapLoader
        // memoises results so repeated cards on the browse list don't
        // re-download.
        val bitmapLoader = CacheBitmapLoader(okHttpBitmapLoader)
        val session = MediaLibrarySession.Builder(this, playbackManager.player, callback)
            .setBitmapLoader(bitmapLoader)
            .build()
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
