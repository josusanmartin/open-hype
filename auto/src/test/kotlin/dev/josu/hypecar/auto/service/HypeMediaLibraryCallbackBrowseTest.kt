package dev.josu.hypecar.auto.service

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.os.Process
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.auto.HypeMediaIds
import dev.josu.hypecar.auto.R
import dev.josu.hypecar.core.data.repository.AccountDataWriteGate
import dev.josu.hypecar.core.data.repository.FavoriteSyncManager
import dev.josu.hypecar.core.model.AuthSession
import dev.josu.hypecar.core.model.Blog
import dev.josu.hypecar.core.model.FeedItem
import dev.josu.hypecar.core.model.FeedMode
import dev.josu.hypecar.core.model.LatestMode
import dev.josu.hypecar.core.model.MediaItemExtras
import dev.josu.hypecar.core.model.PlaybackQueue
import dev.josu.hypecar.core.model.Playlist
import dev.josu.hypecar.core.model.PopularMode
import dev.josu.hypecar.core.model.SearchQuery
import dev.josu.hypecar.core.model.Track
import dev.josu.hypecar.core.model.User
import dev.josu.hypecar.core.model.repository.AuthRepository
import dev.josu.hypecar.core.model.repository.CatalogRepository
import dev.josu.hypecar.core.model.repository.MeRepository
import dev.josu.hypecar.core.model.repository.OfflineDownloadStatus
import dev.josu.hypecar.core.model.repository.OfflineRepository
import dev.josu.hypecar.core.model.repository.PlaybackRepository
import dev.josu.hypecar.core.model.repository.SearchRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers the design-review additions to the Android Auto callback:
 *  - 4 top-level sections instead of 6 (Latest / Popular / Favorites / More)
 *  - More umbrella surfaces Feed / Playlists / History one level deeper
 *  - Signed-out browse of an authenticated section returns a placeholder, not
 *    a generic error
 *  - Section tiles carry localised titles, subtitles, and inline artwork
 *  - Track items carry artist-first subtitles plus blog/loved extras
 *  - Empty sections render a friendly placeholder on the first page
 */
@RunWith(RobolectricTestRunner::class)
@androidx.annotation.OptIn(UnstableApi::class)
class HypeMediaLibraryCallbackBrowseTest {
    private val testContext: android.content.Context get() = ApplicationProvider.getApplicationContext()

    private fun buildCallback(
        authRepository: AuthRepository = BrowseSignedInAuthRepository,
        meRepository: MeRepository = BrowseEmptyMeRepository,
        catalogRepository: CatalogRepository = BrowseEmptyCatalogRepository,
        searchRepository: SearchRepository = BrowseEmptySearchRepository,
        favoriteSyncManager: FavoriteSyncManager = browseTestFavoriteSyncManager(),
    ): HypeMediaLibraryCallback = HypeMediaLibraryCallback(
        context = testContext,
        catalogRepository = catalogRepository,
        meRepository = meRepository,
        searchRepository = searchRepository,
        offlineRepository = BrowseEmptyOfflineRepository,
        authRepository = authRepository,
        favoriteSyncManager = favoriteSyncManager,
    )

    private fun controller(
        packageName: String,
        uid: Int,
        trusted: Boolean,
        controllerVersion: Int = 1,
    ): MediaSession.ControllerInfo = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        packageName,
        /* pid = */
        12_345,
        uid,
        controllerVersion,
        /* interfaceVersion = */
        1,
        trusted,
        Bundle.EMPTY,
        /* isPackageNameVerified = */
        true,
    )

    private inline fun withSession(
        callback: HypeMediaLibraryCallback,
        block: (MediaLibrarySession) -> Unit,
    ) {
        val player = ExoPlayer.Builder(testContext).build()
        val session = MediaLibrarySession.Builder(testContext, player, callback).build()
        try {
            block(session)
        } finally {
            callback.close()
            session.release()
            player.release()
        }
    }

    @Test
    fun `untrusted external controller is rejected at connection`() {
        val callback = buildCallback()
        withSession(callback) { session ->
            val result = callback.onConnect(
                session,
                controller(
                    packageName = "dev.example.untrusted",
                    uid = Process.myUid() + 10_000,
                    trusted = false,
                ),
            )

            assertThat(result.isAccepted).isFalse()
        }
    }

    @Test
    fun `trusted controller cannot gain favorite mutation by spoofing the Auto package`() {
        val callback = buildCallback()
        withSession(callback) { session ->
            val spoofedAuto = controller(
                packageName = "com.google.android.projection.gearhead",
                uid = Process.myUid() + 10_001,
                trusted = true,
                controllerVersion = MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
            )

            val result = callback.onConnect(session, spoofedAuto)

            assertThat(result.isAccepted).isTrue()
            assertThat(
                result.availableSessionCommands.commands.any {
                    it.customAction == "dev.josu.hypecar.auto.action.TOGGLE_FAVORITE"
                },
            ).isFalse()
        }
    }

    @Test
    fun `connected controller favorite command follows login and logout`() {
        val authRepository = MutableBrowseAuthRepository(initialSession = null)
        val callback = buildCallback(authRepository = authRepository)

        withSession(callback) { session ->
            val toggleCommand = SessionCommand(
                "dev.josu.hypecar.auto.action.TOGGLE_FAVORITE",
                Bundle.EMPTY,
            )
            val controllerFuture = MediaController.Builder(testContext, session.token).buildAsync()
            awaitCondition {
                shadowOf(Looper.getMainLooper()).idle()
                controllerFuture.isDone
            }
            val mediaController = controllerFuture.get(2, TimeUnit.SECONDS)
            try {
                assertThat(mediaController.isSessionCommandAvailable(toggleCommand)).isFalse()
                assertThat(
                    mediaController.mediaButtonPreferences.any {
                        it.sessionCommand?.customAction == toggleCommand.customAction
                    },
                ).isFalse()

                authRepository.sessions.value = AuthSession(username = "account-a", token = "token-a")
                awaitCondition {
                    shadowOf(Looper.getMainLooper()).idle()
                    mediaController.isSessionCommandAvailable(toggleCommand) &&
                        mediaController.mediaButtonPreferences.any {
                            it.sessionCommand?.customAction == toggleCommand.customAction
                        }
                }

                authRepository.sessions.value = null
                awaitCondition {
                    shadowOf(Looper.getMainLooper()).idle()
                    !mediaController.isSessionCommandAvailable(toggleCommand) &&
                        mediaController.mediaButtonPreferences.none {
                            it.sessionCommand?.customAction == toggleCommand.customAction
                        }
                }
            } finally {
                mediaController.release()
            }
        }
    }

    @Test
    fun `verified untrusted Auto can browse public catalog but not account data`() {
        var privateReads = 0
        val privateRepository = object : MeRepository by BrowseEmptyMeRepository {
            override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> {
                privateReads += 1
                return listOf(browseSampleTrack)
            }
        }
        val callback = buildCallback(meRepository = privateRepository)
        withSession(callback) { session ->
            val autoPackage = "com.google.android.projection.gearhead"
            val autoUid = Process.myUid() + 10_005
            shadowOf(testContext.packageManager).setPackagesForUid(autoUid, autoPackage)
            val autoController = controller(
                packageName = autoPackage,
                uid = autoUid,
                trusted = false,
                controllerVersion = MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
            )

            val result = callback.onConnect(session, autoController)

            assertThat(result.isAccepted).isTrue()
            assertThat(
                result.availableSessionCommands.commands.any {
                    it.customAction == "dev.josu.hypecar.auto.action.TOGGLE_FAVORITE"
                },
            ).isFalse()

            val publicResult = callback.onGetChildren(
                session,
                autoController,
                HypeMediaIds.latest,
                /* page = */
                0,
                /* pageSize = */
                20,
                /* params = */
                null,
            ).get(2, TimeUnit.SECONDS)
            val privateResult = callback.onGetChildren(
                session,
                autoController,
                HypeMediaIds.favorites,
                /* page = */
                0,
                /* pageSize = */
                20,
                /* params = */
                null,
            ).get(2, TimeUnit.SECONDS)
            val playlistItemResult = callback.onGetItem(
                session,
                autoController,
                HypeMediaIds.playlist(7),
            ).get(2, TimeUnit.SECONDS)

            assertThat(publicResult.resultCode).isEqualTo(androidx.media3.session.LibraryResult.RESULT_SUCCESS)
            assertThat(privateResult.resultCode).isEqualTo(SessionError.ERROR_PERMISSION_DENIED)
            assertThat(playlistItemResult.resultCode).isEqualTo(SessionError.ERROR_PERMISSION_DENIED)

            // A forged source id must not reconstruct the private Favorites
            // queue. The selected public track itself remains resolvable.
            val forgedSelection = MediaItem.Builder()
                .setMediaId(
                    HypeMediaIds.track(
                        browseSampleTrack.id,
                        HypeMediaIds.favorites,
                        sourcePage = 0,
                        sourcePageSize = 20,
                    ),
                )
                .build()
            val resolved = callback.onSetMediaItems(
                session,
                autoController,
                listOf(forgedSelection),
                /* startIndex = */
                0,
                /* startPositionMs = */
                0L,
            ).get(2, TimeUnit.SECONDS)

            assertThat(resolved.mediaItems).hasSize(1)
            assertThat(resolved.mediaItems.single().localConfiguration?.uri.toString())
                .isEqualTo(browseSampleTrack.streamUrl())
            assertThat(privateReads).isEqualTo(0)
        }
    }

    @Test
    fun `generic trusted media controller cannot enumerate private account sections`() {
        var privateReads = 0
        val privateRepository = object : MeRepository by BrowseEmptyMeRepository {
            override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> {
                privateReads += 1
                return listOf(browseSampleTrack)
            }
        }
        val callback = buildCallback(meRepository = privateRepository)

        withSession(callback) { session ->
            val result = callback.onGetChildren(
                session,
                controller(
                    packageName = "dev.example.notification-listener",
                    uid = Process.myUid() + 10_006,
                    trusted = true,
                ),
                HypeMediaIds.favorites,
                /* page = */
                0,
                /* pageSize = */
                20,
                /* params = */
                null,
            ).get(2, TimeUnit.SECONDS)

            assertThat(result.resultCode).isEqualTo(SessionError.ERROR_PERMISSION_DENIED)
            assertThat(privateReads).isEqualTo(0)
        }
    }

    @Test
    fun `library callback rejects an untrusted controller even when called directly`() {
        val callback = buildCallback()
        withSession(callback) { session ->
            val result = callback.onGetChildren(
                session,
                controller(
                    packageName = "dev.example.untrusted",
                    uid = Process.myUid() + 10_002,
                    trusted = false,
                ),
                HypeMediaIds.latest,
                /* page = */
                0,
                /* pageSize = */
                20,
                /* params = */
                null,
            ).get(2, TimeUnit.SECONDS)

            assertThat(result.resultCode).isEqualTo(SessionError.ERROR_PERMISSION_DENIED)
        }
    }

    @Test
    fun `external controller media URI is rebuilt from the app catalog`() {
        val callback = buildCallback(catalogRepository = BrowseLatestCatalogRepository)
        withSession(callback) { session ->
            val maliciousUri = "https://evil.example/attacker-controlled.mp3"
            val callerItem = MediaItem.Builder()
                .setMediaId(HypeMediaIds.track(browseSampleTrack.id))
                .setUri(maliciousUri)
                .build()

            val resolved = callback.onAddMediaItems(
                session,
                controller(
                    packageName = "dev.example.trusted-controller",
                    uid = Process.myUid() + 10_003,
                    trusted = true,
                ),
                listOf(callerItem),
            ).get(2, TimeUnit.SECONDS)

            assertThat(resolved).hasSize(1)
            assertThat(resolved.single().localConfiguration?.uri.toString())
                .isEqualTo(browseSampleTrack.streamUrl())
            assertThat(resolved.single().localConfiguration?.uri.toString()).isNotEqualTo(maliciousUri)
        }
    }

    @Test
    fun `untrusted controller cannot inject media items through a direct callback`() {
        val callback = buildCallback(catalogRepository = BrowseLatestCatalogRepository)
        withSession(callback) { session ->
            val failed = callback.onAddMediaItems(
                session,
                controller(
                    packageName = "dev.example.untrusted",
                    uid = Process.myUid() + 10_004,
                    trusted = false,
                ),
                listOf(MediaItem.fromUri("https://evil.example/stream.mp3")),
            )

            val thrown = assertThrows(ExecutionException::class.java) {
                failed.get(2, TimeUnit.SECONDS)
            }
            assertThat(thrown.cause).isInstanceOf(SecurityException::class.java)
        }
    }

    @Test
    fun `media button fallback can restore local playback history`() {
        val historyRepository = object : MeRepository by BrowseEmptyMeRepository {
            override suspend fun history(page: Int, count: Int): List<Track> = listOf(browseSampleTrack)
        }
        val callback = buildCallback(meRepository = historyRepository)
        withSession(callback) { session ->
            val fallback = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                testContext.packageName,
                /* pid = */
                -1,
                /* uid = */
                -1,
                /* controllerVersion = */
                1,
                /* interfaceVersion = */
                1,
                /* trusted = */
                false,
                Bundle.EMPTY,
                /* isPackageNameVerified = */
                true,
            )

            val resumed = callback.onPlaybackResumption(session, fallback, true).get(2, TimeUnit.SECONDS)

            assertThat(resumed.mediaItems).hasSize(1)
            assertThat(resumed.mediaItems.single().mediaId).contains(browseSampleTrack.id)
        }
    }

    @Test
    fun `metadata-only resumption requests and returns one local item`() {
        var requestedCount = 0
        val historyRepository = object : MeRepository by BrowseEmptyMeRepository {
            override suspend fun history(page: Int, count: Int): List<Track> {
                requestedCount = count
                return listOf(browseSampleTrack, browseSampleTrack.copy(id = "second"))
            }
        }
        val callback = buildCallback(meRepository = historyRepository)
        withSession(callback) { session ->
            val fallback = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                testContext.packageName,
                /* pid = */
                -1,
                /* uid = */
                -1,
                /* controllerVersion = */
                1,
                /* interfaceVersion = */
                1,
                /* trusted = */
                false,
                Bundle.EMPTY,
                /* isPackageNameVerified = */
                true,
            )

            val resumed = callback.onPlaybackResumption(session, fallback, false).get(2, TimeUnit.SECONDS)

            assertThat(requestedCount).isEqualTo(1)
            assertThat(resumed.mediaItems).hasSize(1)
        }
    }

    @Test
    fun `media button fallback cannot restore another account history while signed out`() {
        var historyReads = 0
        val historyRepository = object : MeRepository by BrowseEmptyMeRepository {
            override suspend fun history(page: Int, count: Int): List<Track> {
                historyReads += 1
                return listOf(browseSampleTrack)
            }
        }
        val callback = buildCallback(
            authRepository = BrowseSignedOutAuthRepository,
            meRepository = historyRepository,
        )
        withSession(callback) { session ->
            val fallback = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                testContext.packageName,
                /* pid = */
                -1,
                /* uid = */
                -1,
                /* controllerVersion = */
                1,
                /* interfaceVersion = */
                1,
                /* trusted = */
                false,
                Bundle.EMPTY,
                /* isPackageNameVerified = */
                true,
            )

            val thrown = assertThrows(ExecutionException::class.java) {
                callback.onPlaybackResumption(session, fallback, true).get(2, TimeUnit.SECONDS)
            }

            assertThat(thrown.cause).isInstanceOf(IllegalStateException::class.java)
            assertThat(historyReads).isEqualTo(0)
        }
    }

    @Test
    fun `root returns four top-level sections in the expected order`() {
        val callback = buildCallback()

        val items = callback.privateBrowseLoadChildren(HypeMediaIds.root, pageSize = 20)

        assertThat(items).hasSize(4)
        assertThat(items.map { it.mediaId }).containsExactly(
            HypeMediaIds.latest,
            HypeMediaIds.popular,
            HypeMediaIds.favorites,
            HypeMediaIds.more,
        ).inOrder()
    }

    @Test
    fun `each top-level section carries a localised title subtitle and inline artwork`() {
        val callback = buildCallback()

        val items = callback.privateBrowseLoadChildren(HypeMediaIds.root, pageSize = 20)
        val tiles = items.associateBy { it.mediaId }

        // Spot-check Latest: title, subtitle, and artwork are all populated.
        val latest = tiles.getValue(HypeMediaIds.latest)
        assertThat(latest.mediaMetadata.title.toString())
            .isEqualTo(testContext.getString(R.string.auto_section_latest_title))
        assertThat(latest.mediaMetadata.subtitle.toString())
            .isEqualTo(testContext.getString(R.string.auto_section_latest_subtitle))
        assertThat(latest.mediaMetadata.artworkData).isNotEmpty()
        assertThat(latest.mediaMetadata.artworkUri).isNull()
        // Every tile has inline bitmap artwork — no android.resource vector
        // URI that real head units can tint into a white square.
        items.forEach { item ->
            assertThat(item.mediaMetadata.artworkData).isNotEmpty()
            assertThat(item.mediaMetadata.artworkUri).isNull()
            assertTransparentArtworkCorners(item)
        }
    }

    @Test
    fun `the more umbrella surfaces feed playlists and history`() {
        val callback = buildCallback()

        val items = callback.privateBrowseLoadChildren(HypeMediaIds.more, pageSize = 20)

        assertThat(items.map { it.mediaId }).containsExactly(
            HypeMediaIds.feed,
            HypeMediaIds.playlists,
            HypeMediaIds.history,
        ).inOrder()
    }

    @Test
    fun `track sections request compact list rows for Auto hosts`() {
        val callback = buildCallback()

        val params = callback.privateParamsWithHintsFor(HypeMediaIds.latest)
        val extras = params.extras

        assertThat(extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        assertThat(extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    @Test
    fun `search results request compact list rows for Auto hosts`() {
        val callback = buildCallback()

        val params = callback.privateSearchParamsWithHints()
        val extras = params.extras

        assertThat(extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        assertThat(extras.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    @Test
    fun `signed-out browse of favorites shows the sign-in placeholder`() {
        val callback = buildCallback(authRepository = BrowseSignedOutAuthRepository)

        val items = callback.privateBrowseLoadChildren(HypeMediaIds.favorites, pageSize = 20)

        assertThat(items).hasSize(1)
        val placeholder = items.single()
        assertThat(placeholder.mediaMetadata.isBrowsable).isTrue()
        assertThat(placeholder.mediaMetadata.isPlayable).isFalse()
        assertThat(placeholder.mediaMetadata.title.toString())
            .isEqualTo(testContext.getString(R.string.auto_signin_title))
        assertThat(placeholder.mediaMetadata.subtitle.toString())
            .isEqualTo(testContext.getString(R.string.auto_signin_subtitle))
        // The sign-in artwork must be present so the tile is recognisable on the HUD.
        assertThat(placeholder.mediaMetadata.artworkData).isNotEmpty()
        assertThat(placeholder.mediaMetadata.artworkUri).isNull()
        assertTransparentArtworkCorners(placeholder)
    }

    @Test
    fun `signed-out browse of feed shows the sign-in placeholder`() {
        val callback = buildCallback(authRepository = BrowseSignedOutAuthRepository)

        val items = callback.privateBrowseLoadChildren(HypeMediaIds.feed, pageSize = 20)

        assertThat(items).hasSize(1)
        assertThat(items.single().mediaMetadata.title.toString())
            .isEqualTo(testContext.getString(R.string.auto_signin_title))
    }

    @Test
    fun `signed-out browse of history never reads previous account rows`() {
        var historyReads = 0
        val historyRepository = object : MeRepository by BrowseEmptyMeRepository {
            override suspend fun history(page: Int, count: Int): List<Track> {
                historyReads += 1
                return listOf(browseSampleTrack)
            }
        }
        val callback = buildCallback(
            authRepository = BrowseSignedOutAuthRepository,
            meRepository = historyRepository,
        )

        val items = callback.privateBrowseLoadChildren(HypeMediaIds.history, pageSize = 20)

        assertThat(items).hasSize(1)
        assertThat(items.single().mediaMetadata.title.toString())
            .isEqualTo(testContext.getString(R.string.auto_signin_title))
        assertThat(historyReads).isEqualTo(0)
    }

    @Test
    fun `signed-in browse of an empty section returns a friendly placeholder`() {
        val callback = buildCallback(
            authRepository = BrowseSignedInAuthRepository,
            meRepository = BrowseEmptyMeRepository,
        )

        val items = callback.privateBrowseLoadChildren(HypeMediaIds.favorites, pageSize = 20)

        assertThat(items).hasSize(1)
        val placeholder = items.single()
        assertThat(placeholder.mediaMetadata.title.toString())
            .isEqualTo(testContext.getString(R.string.auto_empty_favorites_title))
        assertThat(placeholder.mediaMetadata.isBrowsable).isTrue()
        assertThat(placeholder.mediaMetadata.isPlayable).isFalse()
    }

    @Test
    fun `expired signed-in browse of favorites falls back to sign-in placeholder`() {
        val callback = buildCallback(
            authRepository = BrowseSignedInAuthRepository,
            meRepository = BrowseUnauthorizedMeRepository,
        )

        val items = callback.privateBrowseLoadChildren(HypeMediaIds.favorites, pageSize = 20)

        assertThat(items).hasSize(1)
        assertThat(items.single().mediaMetadata.title.toString())
            .isEqualTo(testContext.getString(R.string.auto_signin_title))
    }

    @Test
    fun `private section load failure falls back to phone refresh placeholder`() {
        val callback = buildCallback(
            authRepository = BrowseSignedInAuthRepository,
            meRepository = BrowseFailingMeRepository,
        )

        val items = callback.privateBrowseLoadChildren(HypeMediaIds.favorites, pageSize = 20)

        assertThat(items).hasSize(1)
        assertThat(items.single().mediaMetadata.title.toString())
            .isEqualTo(testContext.getString(R.string.auto_private_unavailable_title))
    }

    @Test
    fun `private browse response from account A is discarded after account B signs in`() {
        val accountA = AuthSession(username = "account-a", token = "token-a")
        val accountB = AuthSession(username = "account-b", token = "token-b")
        val authRepository = MutableBrowseAuthRepository(accountA)
        val meRepository = DeferredFavoritesMeRepository()
        val callback = buildCallback(
            authRepository = authRepository,
            meRepository = meRepository,
        )

        withSession(callback) { session ->
            val resultFuture = callback.onGetChildren(
                session,
                controller(
                    packageName = testContext.packageName,
                    uid = Process.myUid(),
                    trusted = false,
                ),
                HypeMediaIds.favorites,
                /* page = */
                0,
                /* pageSize = */
                20,
                /* params = */
                null,
            )
            assertThat(meRepository.started.await(2, TimeUnit.SECONDS)).isTrue()

            authRepository.sessions.value = accountB
            meRepository.favoritesResult.complete(listOf(browseSampleTrack))

            val result = resultFuture.get(2, TimeUnit.SECONDS)
            assertThat(result.resultCode).isEqualTo(androidx.media3.session.LibraryResult.RESULT_SUCCESS)
            val items = checkNotNull(result.value)
            assertThat(items).hasSize(1)
            assertThat(items.single().mediaMetadata.title.toString())
                .isEqualTo(testContext.getString(R.string.auto_private_unavailable_title))
            assertThat(items.single().mediaId).doesNotContain(browseSampleTrack.id)
        }
    }

    @Test
    fun `late favorite result from account A cannot update account B`() {
        val accountA = AuthSession(username = "account-a", token = "token-a")
        val accountB = AuthSession(username = "account-b", token = "token-b")
        val authRepository = MutableBrowseAuthRepository(accountA)
        val meRepository = DeferredFavoriteToggleMeRepository()
        val playbackRepository = RecordingFavoritePlaybackRepository()
        val accountDataWriteGate = AccountDataWriteGate(initiallyActive = true)
        val favoriteSyncManager = FavoriteSyncManager(
            meRepository,
            playbackRepository,
            accountDataWriteGate,
        )
        val callback = buildCallback(
            authRepository = authRepository,
            meRepository = meRepository,
            favoriteSyncManager = favoriteSyncManager,
        )

        withSession(callback) { session ->
            awaitCondition { callback.privateHasActiveAccount() }
            val appController = controller(
                packageName = testContext.packageName,
                uid = Process.myUid(),
                trusted = false,
            )
            assertThat(callback.onConnect(session, appController).isAccepted).isTrue()

            val accountAItem = favoriteTestItem(isLoved = false)
            session.player.setMediaItem(accountAItem)
            val resultFuture = callback.onCustomCommand(
                session,
                appController,
                SessionCommand("dev.josu.hypecar.auto.action.TOGGLE_FAVORITE", Bundle.EMPTY),
                Bundle.EMPTY,
            )
            assertThat(meRepository.started.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(playbackRepository.favoriteStates).containsExactly(true)

            kotlinx.coroutines.runBlocking {
                accountDataWriteGate.deactivate()
                accountDataWriteGate.activate()
            }
            authRepository.sessions.value = accountB
            val accountBItem = favoriteTestItem(isLoved = true)
            session.player.setMediaItem(accountBItem)
            // A failed account-A request would normally publish a `false`
            // reconciliation. Completing only after B is active proves that
            // neither that edit nor the old override crosses the boundary.
            meRepository.toggleResult.complete(null)

            awaitCondition {
                shadowOf(Looper.getMainLooper()).idle()
                resultFuture.isDone
            }
            val result = resultFuture.get(2, TimeUnit.SECONDS)
            assertThat(result.resultCode).isEqualTo(SessionError.ERROR_PERMISSION_DENIED)
            assertThat(playbackRepository.favoriteStates).containsExactly(true)
            assertThat(callback.privateFavoriteStateFor(accountBItem)).isTrue()
        }
    }

    @Test
    fun `favorite publish is rejected when account gate changes after callback identity check`() {
        val account = AuthSession(username = "account-a", token = "token-a")
        val accountDataWriteGate = AccountDataWriteGate(initiallyActive = true)
        val authRepository = GateInvalidatingBrowseAuthRepository(account, accountDataWriteGate)
        val playbackRepository = RecordingFavoritePlaybackRepository()
        val favoriteSyncManager = FavoriteSyncManager(
            BrowseEmptyMeRepository,
            playbackRepository,
            accountDataWriteGate,
        )
        val callback = buildCallback(
            authRepository = authRepository,
            favoriteSyncManager = favoriteSyncManager,
        )

        withSession(callback) { session ->
            awaitCondition { callback.privateHasActiveAccount() }
            val appController = controller(
                packageName = testContext.packageName,
                uid = Process.myUid(),
                trusted = false,
            )
            assertThat(callback.onConnect(session, appController).isAccepted).isTrue()
            session.player.setMediaItem(favoriteTestItem(isLoved = false))

            val resultFuture = callback.onCustomCommand(
                session,
                appController,
                SessionCommand("dev.josu.hypecar.auto.action.TOGGLE_FAVORITE", Bundle.EMPTY),
                Bundle.EMPTY,
            )
            awaitCondition {
                shadowOf(Looper.getMainLooper()).idle()
                resultFuture.isDone
            }
            val result = resultFuture.get(2, TimeUnit.SECONDS)

            assertThat(result.resultCode).isEqualTo(SessionError.ERROR_PERMISSION_DENIED)
            assertThat(authRepository.invalidatedDuringIdentityCheck).isTrue()
            assertThat(playbackRepository.favoriteStates).isEmpty()
        }
    }

    @Test
    fun `track items use artist subtitle and retain blog loved metadata`() {
        val callback = buildCallback(catalogRepository = BrowseLatestCatalogRepository)

        val items = callback.privateBrowseLoadChildren(HypeMediaIds.latest, pageSize = 20)

        val first = items.first()
        assertThat(first.mediaMetadata.subtitle.toString()).isEqualTo(browseSampleTrack.artist)
        assertThat(first.mediaMetadata.albumTitle.toString()).isEqualTo(browseSampleTrack.postedBy)
        val extras = first.mediaMetadata.extras
        assertThat(extras).isNotNull()
        assertThat(extras!!.getInt("blog_id")).isEqualTo(browseSampleTrack.postedById)
        assertThat(extras.getString("blog_name")).isEqualTo(browseSampleTrack.postedBy)
        assertThat(extras.getInt("loved_count")).isEqualTo(browseSampleTrack.lovedCount)
    }

    @Test
    fun `track items keep artist subtitle when loved count is zero`() {
        val zeroLovedTrack = browseSampleTrack.copy(lovedCount = 0)
        val callback = buildCallback(
            catalogRepository = object : CatalogRepository by BrowseEmptyCatalogRepository {
                override suspend fun latest(mode: LatestMode, page: Int, count: Int, forceRefresh: Boolean) = listOf(zeroLovedTrack)
            },
        )

        val items = callback.privateBrowseLoadChildren(HypeMediaIds.latest, pageSize = 20)

        assertThat(items.single().mediaMetadata.subtitle.toString()).isEqualTo(zeroLovedTrack.artist)
    }
}

private val browseSampleTrack = Track(
    id = "39v49",
    artist = "L.A. Sagne",
    title = "Music In The Neighbourhood",
    lovedCount = 27,
    postedBy = "Destroy//Exist",
    postedById = 22246,
    postedCount = 3,
    postDescription = "After a run of singles.",
    datePostedEpochSeconds = 1774723952,
    postUrl = "https://www.destroyexist.com/2026/03/la-sagne-music-in-neighbourhood.html",
    itunesUrl = "https://hypem.com/go/itunes_search/L.A.%20Sagne",
)

@Suppress("UNCHECKED_CAST")
private fun HypeMediaLibraryCallback.privateBrowseLoadChildren(
    parentId: String,
    pageSize: Int,
): List<MediaItem> {
    val method = javaClass.getDeclaredMethod(
        "loadChildren",
        String::class.java,
        Int::class.javaPrimitiveType,
    )
    method.isAccessible = true
    return method.invoke(this, parentId, pageSize) as List<MediaItem>
}

private fun HypeMediaLibraryCallback.privateHasActiveAccount(): Boolean {
    val method = javaClass.getDeclaredMethod("activeAccountSnapshot")
    method.isAccessible = true
    return method.invoke(this) != null
}

private fun HypeMediaLibraryCallback.privateFavoriteStateFor(mediaItem: MediaItem): Boolean {
    val method = javaClass.getDeclaredMethod("favoriteStateFor", MediaItem::class.java)
    method.isAccessible = true
    return method.invoke(this, mediaItem) as Boolean
}

private fun awaitCondition(
    timeoutMs: Long = 2_000,
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (!condition() && System.nanoTime() < deadline) {
        Thread.sleep(5)
    }
    assertThat(condition()).isTrue()
}

private fun favoriteTestItem(isLoved: Boolean): MediaItem =
    MediaItem.Builder()
        .setMediaId(HypeMediaIds.track(browseSampleTrack.id))
        .setUri(browseSampleTrack.streamUrl())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(browseSampleTrack.title)
                .setArtist(browseSampleTrack.artist)
                .setExtras(
                    Bundle().apply {
                        putBoolean(MediaItemExtras.IsLoved, isLoved)
                    },
                )
                .build(),
        )
        .build()

private fun HypeMediaLibraryCallback.privateParamsWithHintsFor(
    parentId: String,
): MediaLibraryService.LibraryParams {
    val method = javaClass.getDeclaredMethod(
        "paramsWithHintsFor",
        String::class.java,
        MediaLibraryService.LibraryParams::class.java,
    )
    method.isAccessible = true
    return method.invoke(this, parentId, null) as MediaLibraryService.LibraryParams
}

private fun HypeMediaLibraryCallback.privateSearchParamsWithHints(): MediaLibraryService.LibraryParams {
    val method = javaClass.getDeclaredMethod(
        "searchParamsWithHints",
        MediaLibraryService.LibraryParams::class.java,
    )
    method.isAccessible = true
    return method.invoke(this, null) as MediaLibraryService.LibraryParams
}

private fun assertTransparentArtworkCorners(item: MediaItem) {
    val data = item.mediaMetadata.artworkData
    assertThat(data).isNotNull()
    val bitmap = BitmapFactory.decodeByteArray(data, 0, data!!.size)
    assertThat(bitmap).isNotNull()

    val cornerAlpha = listOf(
        Color.alpha(bitmap.getPixel(0, 0)),
        Color.alpha(bitmap.getPixel(bitmap.width - 1, 0)),
        Color.alpha(bitmap.getPixel(0, bitmap.height - 1)),
        Color.alpha(bitmap.getPixel(bitmap.width - 1, bitmap.height - 1)),
    )
    assertThat(cornerAlpha).containsExactly(0, 0, 0, 0)
}

private object BrowseEmptyCatalogRepository : CatalogRepository {
    override suspend fun latest(mode: LatestMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun popular(mode: PopularMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun track(trackId: String): Track = browseSampleTrack
    override suspend fun blogs(page: Int, count: Int): List<Blog> = emptyList()
    override suspend fun blog(blogId: Int): Blog = error("Not used")
    override suspend fun blogTracks(blogId: Int, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun user(username: String): User = error("Not used")
    override suspend fun userFavorites(username: String, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun userFriends(username: String, page: Int, count: Int): List<User> = emptyList()
}

private object BrowseLatestCatalogRepository : CatalogRepository {
    override suspend fun latest(mode: LatestMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> = listOf(browseSampleTrack)
    override suspend fun popular(mode: PopularMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun track(trackId: String): Track = browseSampleTrack
    override suspend fun blogs(page: Int, count: Int): List<Blog> = emptyList()
    override suspend fun blog(blogId: Int): Blog = error("Not used")
    override suspend fun blogTracks(blogId: Int, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun user(username: String): User = error("Not used")
    override suspend fun userFavorites(username: String, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun userFriends(username: String, page: Int, count: Int): List<User> = emptyList()
}

private object BrowseEmptyMeRepository : MeRepository {
    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = false
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private object BrowseUnauthorizedMeRepository : MeRepository {
    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> = error("HTTP 401 Unauthorized")
    override suspend fun toggleFavorite(trackId: String): Boolean? = false
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private object BrowseFailingMeRepository : MeRepository {
    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> = error("temporary network failure")
    override suspend fun toggleFavorite(trackId: String): Boolean? = false
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private object BrowseEmptySearchRepository : SearchRepository {
    override suspend fun searchTracks(query: SearchQuery, page: Int, count: Int): List<Track> = emptyList()
}

private object BrowseSignedInAuthRepository : AuthRepository {
    override val session: Flow<AuthSession?> = flowOf(AuthSession(username = "tester", token = "tok"))
    override suspend fun login(usernameOrEmail: String, password: String): Result<AuthSession> = error("not used")
    override suspend fun logout() = Unit
}

private object BrowseSignedOutAuthRepository : AuthRepository {
    override val session: Flow<AuthSession?> = flowOf(null)
    override suspend fun login(usernameOrEmail: String, password: String): Result<AuthSession> = error("not used")
    override suspend fun logout() = Unit
}

private class MutableBrowseAuthRepository(initialSession: AuthSession?) : AuthRepository {
    val sessions = MutableStateFlow(initialSession)
    override val session: StateFlow<AuthSession?> = sessions

    override suspend fun login(usernameOrEmail: String, password: String): Result<AuthSession> =
        error("not used")

    override suspend fun logout() {
        sessions.value = null
    }
}

private class GateInvalidatingBrowseAuthRepository(
    private val account: AuthSession,
    private val accountDataWriteGate: AccountDataWriteGate,
) : AuthRepository {
    private val collectionCount = AtomicInteger(0)

    @Volatile
    var invalidatedDuringIdentityCheck: Boolean = false
        private set

    override val session: Flow<AuthSession?> = flow {
        if (collectionCount.incrementAndGet() > 1) {
            accountDataWriteGate.deactivate()
            invalidatedDuringIdentityCheck = true
        }
        emit(account)
    }

    override suspend fun login(usernameOrEmail: String, password: String): Result<AuthSession> =
        error("not used")

    override suspend fun logout() = Unit
}

private class DeferredFavoritesMeRepository : MeRepository by BrowseEmptyMeRepository {
    val started = CountDownLatch(1)
    val favoritesResult = CompletableDeferred<List<Track>>()

    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> {
        started.countDown()
        return favoritesResult.await()
    }
}

private class DeferredFavoriteToggleMeRepository : MeRepository by BrowseEmptyMeRepository {
    val started = CountDownLatch(1)
    val toggleResult = CompletableDeferred<Boolean?>()

    override suspend fun toggleFavorite(trackId: String): Boolean? {
        started.countDown()
        return toggleResult.await()
    }
}

private class RecordingFavoritePlaybackRepository : PlaybackRepository {
    override val queue: StateFlow<PlaybackQueue> = MutableStateFlow(PlaybackQueue())
    val favoriteStates = CopyOnWriteArrayList<Boolean>()

    override suspend fun play(tracks: List<Track>, startIndex: Int) = Unit
    override suspend fun playFromTrack(track: Track) = Unit
    override suspend fun togglePlayPause() = Unit
    override suspend fun skipNext() = Unit
    override suspend fun skipPrevious() = Unit
    override suspend fun seekTo(positionMs: Long) = Unit
    override suspend fun toggleShuffle() = Unit
    override suspend fun cycleRepeatMode() = Unit

    override suspend fun updateFavorite(trackId: String, isLoved: Boolean) {
        favoriteStates += isLoved
    }
}

private object BrowseEmptyOfflineRepository : OfflineRepository {
    override val status: StateFlow<OfflineDownloadStatus> = MutableStateFlow(OfflineDownloadStatus())
    override suspend fun setEnabled(enabled: Boolean) = Unit
    override suspend fun setQuotaBytes(quotaBytes: Long) = Unit
    override suspend fun syncFavorites() = Unit
    override suspend fun clearDownloads() = Unit
    override fun cachedAudioUri(trackId: String): String? = null
}

private object BrowseNoOpPlaybackRepository : dev.josu.hypecar.core.model.repository.PlaybackRepository {
    override val queue: kotlinx.coroutines.flow.StateFlow<dev.josu.hypecar.core.model.PlaybackQueue> =
        kotlinx.coroutines.flow.MutableStateFlow(dev.josu.hypecar.core.model.PlaybackQueue())
    override suspend fun play(tracks: List<Track>, startIndex: Int) = Unit
    override suspend fun playFromTrack(track: Track) = Unit
    override suspend fun togglePlayPause() = Unit
    override suspend fun skipNext() = Unit
    override suspend fun skipPrevious() = Unit
    override suspend fun seekTo(positionMs: Long) = Unit
    override suspend fun toggleShuffle() = Unit
    override suspend fun cycleRepeatMode() = Unit
    override suspend fun updateFavorite(trackId: String, isLoved: Boolean) = Unit
}

private fun browseTestFavoriteSyncManager() =
    dev.josu.hypecar.core.data.repository.FavoriteSyncManager(BrowseEmptyMeRepository, BrowseNoOpPlaybackRepository)
