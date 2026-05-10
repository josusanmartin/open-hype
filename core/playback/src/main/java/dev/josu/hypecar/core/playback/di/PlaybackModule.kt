package dev.josu.hypecar.core.playback.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import dev.josu.hypecar.core.playback.HypePlaybackManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {
    @Binds
    @Singleton
    abstract fun bindPlaybackRepository(manager: HypePlaybackManager): PlaybackRepository
}
