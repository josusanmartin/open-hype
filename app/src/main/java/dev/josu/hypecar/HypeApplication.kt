package dev.josu.hypecar

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.josu.hypecar.core.data.repository.SessionGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class HypeApplication :
    Application(),
    Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var sessionStore: SessionGateway
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // No activity, media service, or worker may observe account-derived
        // state while a missing/corrupt/tombstoned session is being wiped:
        // repositories stay behind the inactive account gate, while requests
        // await this same barrier. Keep the potentially large file/DB cleanup
        // off the main thread so cold start cannot be blocked by disk work.
        startupScope.launch { sessionStore.awaitSessionInitialized() }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
