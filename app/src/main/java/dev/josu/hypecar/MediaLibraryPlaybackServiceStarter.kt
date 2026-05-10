package dev.josu.hypecar

import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.josu.hypecar.auto.service.HypeMediaLibraryService
import dev.josu.hypecar.core.playback.PlaybackForegroundServiceStarter
import javax.inject.Inject

class MediaLibraryPlaybackServiceStarter @Inject constructor(
    @ApplicationContext private val context: Context,
) : PlaybackForegroundServiceStarter {
    override fun ensureStarted() {
        runCatching {
            // Media3 owns foreground promotion for the media service; starting it as
            // a foreground service here trips Android's startForeground timeout.
            context.startService(Intent(context, HypeMediaLibraryService::class.java))
        }.onFailure {
            Log.w("PlaybackServiceStarter", "Unable to start media playback service", it)
        }
    }
}
