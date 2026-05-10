package dev.josu.hypecar

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.josu.hypecar.core.playback.PlaybackForegroundServiceStarter
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppPlaybackModule {
    @Binds
    @Singleton
    abstract fun bindPlaybackForegroundServiceStarter(
        starter: MediaLibraryPlaybackServiceStarter,
    ): PlaybackForegroundServiceStarter
}
