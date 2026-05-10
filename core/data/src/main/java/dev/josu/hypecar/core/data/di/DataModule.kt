package dev.josu.hypecar.core.data.di

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.room.Room
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.josu.hypecar.core.data.BuildConfig
import dev.josu.hypecar.core.data.local.HypeDatabase
import dev.josu.hypecar.core.data.net.ResilientDns
import dev.josu.hypecar.core.data.repository.DefaultAuthRepository
import dev.josu.hypecar.core.data.repository.DefaultCatalogRepository
import dev.josu.hypecar.core.data.repository.DefaultHistoryRepository
import dev.josu.hypecar.core.data.repository.DefaultMeRepository
import dev.josu.hypecar.core.data.repository.DefaultOfflineRepository
import dev.josu.hypecar.core.data.repository.DefaultSearchRepository
import dev.josu.hypecar.core.data.repository.HypeSessionStore
import dev.josu.hypecar.core.data.repository.UnauthorizedSessionInterceptor
import dev.josu.hypecar.core.model.repository.AuthRepository
import dev.josu.hypecar.core.model.repository.CatalogRepository
import dev.josu.hypecar.core.model.repository.HistoryRepository
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.OfflineRepository
import dev.josu.hypecar.core.model.repository.SearchRepository
import dev.josu.hypecar.core.network.HypeApiInterceptor
import dev.josu.hypecar.core.network.HypeApiService
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.io.File
import java.util.concurrent.TimeUnit
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
    fun provideSessionStore(@ApplicationContext context: Context): HypeSessionStore = HypeSessionStore(context)

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
    fun provideOkHttp(
        @ApplicationContext context: Context,
        sessionStore: HypeSessionStore,
    ): OkHttpClient {
        val cacheDir = File(context.cacheDir, "okhttp").apply { mkdirs() }
        val cache = Cache(cacheDir, OkHttpCacheBytes)
        return OkHttpClient.Builder()
            .cache(cache)
            .dns(ResilientDns())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .addInterceptor(
                HypeApiInterceptor(
                    authTokenProvider = sessionStore,
                    devProxyAllowed = BuildConfig.ENABLE_AAOS_DEV_PROXY,
                ),
            )
            .addInterceptor(UnauthorizedSessionInterceptor(sessionGateway = sessionStore))
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (
                    chain.request().method == "GET" &&
                    response.isSuccessful &&
                    chain.request().url.host == "api.hypem.com"
                ) {
                    response.newBuilder()
                        .removeHeader("Pragma")
                        .header("Cache-Control", "public, max-age=$ApiCacheMaxAgeSeconds")
                        .build()
                } else {
                    response
                }
            }
            .apply {
                if (BuildConfig.ENABLE_AAOS_DEV_PROXY) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BASIC
                        },
                    )
                }
            }
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
    fun provideTrackDao(db: HypeDatabase) = db.trackDao()

    @Provides
    fun provideTrackListDao(db: HypeDatabase) = db.trackListDao()

    @Provides
    fun providePlaylistDao(db: HypeDatabase) = db.playlistDao()

    @Provides
    fun provideHistoryDao(db: HypeDatabase) = db.historyDao()

    @Provides
    @Singleton
    fun provideAuthRepository(api: HypeApiService, sessionStore: HypeSessionStore): AuthRepository =
        DefaultAuthRepository(api, sessionStore)

    @Provides
    @Singleton
    fun provideCatalogRepository(
        api: HypeApiService,
        db: HypeDatabase,
        json: Json,
    ): CatalogRepository = DefaultCatalogRepository(api, db.trackDao(), db.trackListDao(), json)

    @Provides
    @Singleton
    fun provideMeRepository(
        api: HypeApiService,
        db: HypeDatabase,
        json: Json,
    ): MeRepository = DefaultMeRepository(api, db.trackDao(), db.trackListDao(), db.playlistDao(), db.historyDao(), json)

    @Provides
    @Singleton
    fun provideSearchRepository(
        api: HypeApiService,
        db: HypeDatabase,
        json: Json,
    ): SearchRepository = DefaultSearchRepository(api, db.trackDao(), db.trackListDao(), json)

    @Provides
    @Singleton
    fun provideHistoryRepository(
        api: HypeApiService,
        db: HypeDatabase,
    ): HistoryRepository = DefaultHistoryRepository(api, db.historyDao(), db.trackDao())

    @Provides
    @Singleton
    fun provideOfflineRepository(
        @ApplicationContext context: Context,
        meRepository: MeRepository,
        client: OkHttpClient,
        json: Json,
    ): OfflineRepository = DefaultOfflineRepository(context, meRepository, client, json)
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
}
