package dev.josu.hypecar.core.data.di

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.josu.hypecar.core.data.BuildConfig
import dev.josu.hypecar.core.data.local.HypeDatabase
import dev.josu.hypecar.core.data.net.AndroidConnectivityRepository
import dev.josu.hypecar.core.data.net.HypeResponseCacheInterceptor
import dev.josu.hypecar.core.data.net.ResilientDns
import dev.josu.hypecar.core.data.repository.AccountDataWriteGate
import dev.josu.hypecar.core.data.repository.AccountLocalDataWiper
import dev.josu.hypecar.core.data.repository.DefaultAuthRepository
import dev.josu.hypecar.core.data.repository.DefaultCatalogRepository
import dev.josu.hypecar.core.data.repository.DefaultHistoryRepository
import dev.josu.hypecar.core.data.repository.DefaultMeRepository
import dev.josu.hypecar.core.data.repository.DefaultOfflineRepository
import dev.josu.hypecar.core.data.repository.DefaultSearchRepository
import dev.josu.hypecar.core.data.repository.FavoriteStateCoordinator
import dev.josu.hypecar.core.data.repository.HypeSessionStore
import dev.josu.hypecar.core.data.repository.SessionGateway
import dev.josu.hypecar.core.data.repository.UnauthorizedSessionInterceptor
import dev.josu.hypecar.core.model.repository.AuthRepository
import dev.josu.hypecar.core.model.repository.CatalogRepository
import dev.josu.hypecar.core.model.repository.ConnectivityRepository
import dev.josu.hypecar.core.model.repository.HistoryRepository
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.OfflineRepository
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import dev.josu.hypecar.core.model.repository.SearchRepository
import dev.josu.hypecar.core.network.HypeApiInterceptor
import dev.josu.hypecar.core.network.HypeApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideSessionStore(
        @ApplicationContext context: Context,
        accountLocalDataWiper: AccountLocalDataWiper,
        accountDataWriteGate: AccountDataWriteGate,
    ): HypeSessionStore = HypeSessionStore(
        context = context,
        accountDataWiper = accountLocalDataWiper,
        accountDataWriteGate = accountDataWriteGate,
    )

    @Provides
    fun provideSessionGateway(sessionStore: HypeSessionStore): SessionGateway = sessionStore

    @Provides
    @Singleton
    fun provideApiBaseUrl(@ApplicationContext context: Context): String {
        val isAutomotive = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        val isEmulator = Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true)

        return ApiBaseUrlSelector.select(
            isAutomotive = isAutomotive,
            isEmulator = isEmulator,
            isDevProxyEnabled = BuildConfig.ENABLE_AAOS_DEV_PROXY,
        )
    }

    @Provides
    @Singleton
    fun provideHttpCache(@ApplicationContext context: Context): Cache {
        val cacheDir = File(context.cacheDir, "okhttp").apply { mkdirs() }
        return Cache(cacheDir, OkHttpCacheBytes)
    }

    @Provides
    @Singleton
    fun provideOkHttp(
        cache: Cache,
        sessionStore: HypeSessionStore,
        apiBaseUrl: String,
    ): OkHttpClient {
        val apiUrl = apiBaseUrl.toHttpUrl()
        val apiHost = apiUrl.host
        val usesDevProxy = ApiBaseUrlSelector.isDevProxy(apiBaseUrl)

        return OkHttpClient.Builder()
            // `/me/favorites` is a non-idempotent toggle. Replaying a request
            // after a broken connection can silently undo the first mutation.
            .retryOnConnectionFailure(false)
            .cache(cache)
            .dns(if (usesDevProxy) ResilientDns() else Dns.SYSTEM)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .addInterceptor(
                HypeApiInterceptor(
                    authTokenProvider = sessionStore,
                    devProxyAllowed = usesDevProxy,
                ),
            )
            .addInterceptor(
                UnauthorizedSessionInterceptor(
                    sessionGateway = sessionStore,
                    apiHost = apiHost,
                ),
            )
            .addNetworkInterceptor(
                HypeResponseCacheInterceptor(
                    apiHost = apiHost,
                    anonymousMaxAgeSeconds = ApiCacheMaxAgeSeconds,
                ),
            )
            .build()
    }

    private const val OkHttpCacheBytes: Long = 10L * 1024L * 1024L
    private const val ApiCacheMaxAgeSeconds: Int = 60

    @Provides
    @Singleton
    fun provideApi(
        json: Json,
        client: OkHttpClient,
        apiBaseUrl: String,
    ): HypeApiService =
        Retrofit.Builder()
            .baseUrl(apiBaseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(HypeApiService::class.java)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): HypeDatabase =
        Room.databaseBuilder(context, HypeDatabase::class.java, "hype-car.db").build()

    @Provides
    @Singleton
    fun provideAccountDataWriteGate(): AccountDataWriteGate = AccountDataWriteGate(initiallyActive = false)

    @Provides
    fun provideTrackDao(db: HypeDatabase) = db.trackDao()

    @Provides
    fun provideTrackListDao(db: HypeDatabase) = db.trackListDao()

    @Provides
    fun providePlaylistDao(db: HypeDatabase) = db.playlistDao()

    @Provides
    fun provideHistoryDao(db: HypeDatabase) = db.historyDao()

    @Provides
    @Singleton
    fun provideAccountLocalDataWiper(
        db: HypeDatabase,
        offlineRepositoryProvider: Provider<OfflineRepository>,
        playbackRepositoryProvider: Provider<PlaybackRepository>,
        cache: Cache,
        accountDataWriteGate: AccountDataWriteGate,
    ): AccountLocalDataWiper = AccountLocalDataWiper {
        accountDataWriteGate.wipe {
            var firstFailure: Exception? = null

            fun recordFailure(exception: Exception) {
                val existing = firstFailure
                if (existing == null) {
                    firstFailure = exception
                } else if (existing !== exception) {
                    existing.addSuppressed(exception)
                }
            }

            suspend fun attempt(block: suspend () -> Unit) {
                try {
                    block()
                } catch (exception: Exception) {
                    recordFailure(exception)
                }
            }

            val playbackRepository = try {
                playbackRepositoryProvider.get()
            } catch (exception: Exception) {
                recordFailure(exception)
                null
            }
            if (playbackRepository != null) {
                // The queue belongs to the outgoing account. Clear it before
                // another session can record its transitions as new history.
                attempt { playbackRepository.play(emptyList()) }
            }

            val offlineRepository = try {
                offlineRepositoryProvider.get()
            } catch (exception: Exception) {
                recordFailure(exception)
                null
            }
            if (offlineRepository != null) {
                // Stop and join account-derived writes before deleting their data.
                attempt { offlineRepository.clearAccountData() }
            }
            attempt {
                withContext(Dispatchers.IO) {
                    db.clearAllTables()
                }
            }
            attempt {
                withContext(Dispatchers.IO) {
                    cache.evictAll()
                }
            }

            firstFailure?.let { throw it }
        }
    }

    @Provides
    @Singleton
    fun provideAuthRepository(
        api: HypeApiService,
        sessionStore: HypeSessionStore,
    ): AuthRepository =
        DefaultAuthRepository(
            api = api,
            sessionStore = sessionStore,
        )

    @Provides
    @Singleton
    fun provideCatalogRepository(
        api: HypeApiService,
        db: HypeDatabase,
        json: Json,
        accountDataWriteGate: AccountDataWriteGate,
        favoriteStateCoordinator: FavoriteStateCoordinator,
    ): CatalogRepository = DefaultCatalogRepository(
        api,
        db.trackDao(),
        db.trackListDao(),
        json,
        accountDataWriteGate,
        favoriteStateCoordinator,
    )

    @Provides
    @Singleton
    fun provideMeRepository(
        api: HypeApiService,
        db: HypeDatabase,
        json: Json,
        accountDataWriteGate: AccountDataWriteGate,
        favoriteStateCoordinator: FavoriteStateCoordinator,
    ): MeRepository =
        DefaultMeRepository(
            api,
            db.trackDao(),
            db.trackListDao(),
            db.playlistDao(),
            db.historyDao(),
            json,
            accountDataWriteGate,
            favoriteStateCoordinator,
        )

    @Provides
    @Singleton
    fun provideSearchRepository(
        api: HypeApiService,
        db: HypeDatabase,
        json: Json,
        accountDataWriteGate: AccountDataWriteGate,
        favoriteStateCoordinator: FavoriteStateCoordinator,
    ): SearchRepository = DefaultSearchRepository(
        api,
        db.trackDao(),
        db.trackListDao(),
        json,
        accountDataWriteGate,
        favoriteStateCoordinator,
    )

    @Provides
    @Singleton
    fun provideHistoryRepository(
        api: HypeApiService,
        db: HypeDatabase,
        accountDataWriteGate: AccountDataWriteGate,
        sessionStore: HypeSessionStore,
    ): HistoryRepository = DefaultHistoryRepository(
        api = api,
        historyDao = db.historyDao(),
        trackDao = db.trackDao(),
        accountDataWriteGate = accountDataWriteGate,
        authTokenProvider = sessionStore,
    )

    @Provides
    @Singleton
    fun provideOfflineRepository(
        @ApplicationContext context: Context,
        meRepository: MeRepository,
        client: OkHttpClient,
        json: Json,
        accountDataWriteGate: AccountDataWriteGate,
    ): OfflineRepository = DefaultOfflineRepository(context, meRepository, client, json, accountDataWriteGate)

    @Provides
    @Singleton
    fun provideConnectivityRepository(@ApplicationContext context: Context): ConnectivityRepository =
        AndroidConnectivityRepository(context)
}

internal object ApiBaseUrlSelector {
    private const val ProductionApiBaseUrl = "https://api.hypem.com/v2/"
    private const val AaosDevProxyBaseUrl = "http://10.0.2.2:8787/v2/"

    fun select(
        isAutomotive: Boolean,
        isEmulator: Boolean,
        isDevProxyEnabled: Boolean,
    ): String = if (isDevProxyEnabled && isAutomotive && isEmulator) {
        AaosDevProxyBaseUrl
    } else {
        ProductionApiBaseUrl
    }

    fun isDevProxy(baseUrl: String): Boolean = baseUrl == AaosDevProxyBaseUrl
}
