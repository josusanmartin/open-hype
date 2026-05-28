package dev.josu.hypecar.auto.service

import android.app.PendingIntent
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
            // Session-level PendingIntent the car HUD opens when the user taps
            // the Now Playing surface or a placeholder that prompts them to
            // pick up the phone. The :auto module can't reference :app's
            // MainActivity directly, so we resolve the launcher activity via
            // PackageManager and fall back to a generic main intent.
            .apply { sessionOpenPhoneIntent()?.let(::setSessionActivity) }
            .build()
        librarySession = session

        // Phone playback drives the shared player directly, so no browser
        // controller may bind before audio starts. Register the session here so
        // Media3 can own the foreground media notification for background play.
        addSession(session)
    }

    /**
     * Builds the PendingIntent that the Android Auto HUD launches when the
     * user taps "open on phone" affordances (sign-in flow, deep details).
     * Always uses the app's own launcher intent so any future MainActivity
     * rename keeps working.
     */
    private fun sessionOpenPhoneIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
        launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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
