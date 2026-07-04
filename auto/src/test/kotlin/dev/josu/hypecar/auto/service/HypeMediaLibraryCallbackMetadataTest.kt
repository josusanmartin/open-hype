package dev.josu.hypecar.auto.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.auto.HypeMediaIds
import dev.josu.hypecar.core.model.AuthSession
import dev.josu.hypecar.core.model.Blog
import dev.josu.hypecar.core.model.FeedItem
import dev.josu.hypecar.core.model.LatestMode
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
import dev.josu.hypecar.core.model.repository.SearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class HypeMediaLibraryCallbackMetadataTest {
    private val testContext: android.content.Context get() = ApplicationProvider.getApplicationContext()

    private val callback = HypeMediaLibraryCallback(
        context = testContext,
        catalogRepository = EmptyCatalogRepository,
        meRepository = EmptyMeRepository,
        searchRepository = EmptySearchRepository,
        offlineRepository = EmptyOfflineRepository,
        authRepository = SignedInAuthRepository,
        favoriteSyncManager = metadataTestFavoriteSyncManager(),
        okHttpClient = TestOkHttpClient,
    )

    @Test
    fun `browsable media items explicitly mark playable false`() {
        val item = callback.privateBrowsableItem(mediaId = "section:test", title = "Test")

        assertThat(item.mediaMetadata.isBrowsable).isEqualTo(true)
        assertThat(item.mediaMetadata.isPlayable).isEqualTo(false)
    }

    @Test
    fun `playable media items explicitly mark browsable false`() {
        val item = callback.privatePlayableItem(sampleTrack)

        assertThat(item.mediaMetadata.isPlayable).isEqualTo(true)
        assertThat(item.mediaMetadata.isBrowsable).isEqualTo(false)
    }

    @Test
    fun `Auto media button preferences explicitly reserve previous and next transport slots`() {
        val buttons = callback.privateMediaButtonPreferences()

        val previous = buttons.single { it.playerCommand == Player.COMMAND_SEEK_TO_PREVIOUS }
        val next = buttons.single { it.playerCommand == Player.COMMAND_SEEK_TO_NEXT }

        assertThat(previous.slots.contains(CommandButton.SLOT_BACK)).isTrue()
        assertThat(next.slots.contains(CommandButton.SLOT_FORWARD)).isTrue()
    }

    @Test
    fun `phone notification media button preferences keep transport slots and put the heart in overflow`() {
        val buttons = callback.privateMediaButtonPreferences()

        assertThat(buttons.map { it.displayName.toString() }).containsExactly(
            "Previous",
            "Next",
            "Favorite",
        ).inOrder()
        val favorite = buttons.single { it.displayName.toString() == "Favorite" }
        assertThat(favorite.slots.contains(CommandButton.SLOT_OVERFLOW)).isTrue()
        assertThat(favorite.slots.contains(CommandButton.SLOT_FORWARD)).isFalse()
        assertThat(favorite.slots.contains(CommandButton.SLOT_BACK)).isFalse()
    }

    @Test
    fun `car media button preferences expose favorite without replacing skip next`() {
        val buttons = callback.privateCarMediaButtonPreferences(loved = false)

        assertThat(buttons.map { it.displayName.toString() }).containsExactly(
            "Previous",
            "Favorite",
            "Next",
        ).inOrder()
        assertThat(buttons[0].slots.contains(CommandButton.SLOT_BACK)).isTrue()
        assertThat(buttons[1].sessionCommand?.customAction).contains("TOGGLE_FAVORITE")
        assertThat(buttons[1].slots.contains(CommandButton.SLOT_BACK_SECONDARY)).isTrue()
        assertThat(buttons[1].slots.contains(CommandButton.SLOT_FORWARD_SECONDARY)).isTrue()
        assertThat(buttons[1].slots.contains(CommandButton.SLOT_OVERFLOW)).isTrue()
        assertThat(buttons[2].slots.contains(CommandButton.SLOT_FORWARD)).isTrue()
    }

    @Test
    fun `car media button favorite preference switches icon when loved`() {
        val buttons = callback.privateCarMediaButtonPreferences(loved = true)

        val favorite = buttons.single { it.sessionCommand?.customAction?.contains("TOGGLE_FAVORITE") == true }

        assertThat(favorite.displayName.toString()).isEqualTo("Unfavorite")
        assertThat(favorite.icon).isEqualTo(CommandButton.ICON_HEART_FILLED)
    }

    @Test
    fun `Auto custom action layout stays empty because car hosts render media button preferences`() {
        val buttons = callback.privateNowPlayingLayout(loved = true)

        assertThat(buttons).isEmpty()
    }

    @Test
    fun `Auto custom action layout also stays empty when favorite is off`() {
        val buttons = callback.privateNowPlayingLayout(loved = false)

        assertThat(buttons).isEmpty()
    }

    @Test
    fun `phone notification custom layout exposes only favorite action`() {
        val buttons = callback.privateNotificationCustomLayout(loved = false)

        assertThat(buttons.map { it.displayName.toString() }).containsExactly("Favorite")
        assertThat(buttons.single().icon).isEqualTo(CommandButton.ICON_HEART_UNFILLED)
        assertThat(buttons.single().slots.contains(CommandButton.SLOT_OVERFLOW)).isTrue()
    }

    @Test
    fun `favorite state prefers remembered Auto toggle over stale media metadata`() {
        val item = MediaItem.Builder()
            .setMediaId(HypeMediaIds.track("fav"))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Title")
                    .setArtist("Artist")
                    .setExtras(
                        Bundle().apply {
                            putBoolean("is_loved", false)
                            putString("blog_name", "Blog")
                        },
                    )
                    .build(),
            )
            .build()

        assertThat(callback.privateFavoriteStateFor(item)).isFalse()

        callback.privateRememberFavoriteState(trackId = "fav", loved = true)

        assertThat(callback.privateFavoriteStateFor(item)).isTrue()
    }

    @Test
    fun `Auto favorite toggle result reports io error when repository cannot confirm`() = runBlocking {
        val result = resolveAutoFavoriteToggle(
            meRepository = PlaylistsMeRepository(emptyList()),
            trackId = "fav",
            originalLoved = false,
        )

        assertThat(result.confirmedLoved).isFalse()
        assertThat(result.sessionResult.resultCode).isEqualTo(SessionError.ERROR_IO)
    }

    @Test
    fun `Auto inline artwork is downsampled and cached`() {
        val networkCalls = AtomicInteger(0)
        val artworkBytes = testJpegBytes(width = 1600, height = 1000)
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    networkCalls.incrementAndGet()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(artworkBytes.toResponseBody("image/jpeg".toMediaType()))
                        .build()
                },
            )
            .build()
        val callback = HypeMediaLibraryCallback(
            context = testContext,
            catalogRepository = EmptyCatalogRepository,
            meRepository = EmptyMeRepository,
            searchRepository = EmptySearchRepository,
            offlineRepository = EmptyOfflineRepository,
            authRepository = SignedInAuthRepository,
            favoriteSyncManager = metadataTestFavoriteSyncManager(),
            okHttpClient = client,
        )
        val item = MediaItem.Builder()
            .setMediaId(HypeMediaIds.track("art"))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setArtworkUri(Uri.parse("https://art.example/cover.jpg"))
                    .build(),
            )
            .build()

        val first = callback.privateWithInlineArtworkForTests(item)
        val second = callback.privateWithInlineArtworkForTests(item)
        val firstArtwork = first.mediaMetadata.artworkData!!
        val decoded = BitmapFactory.decodeByteArray(firstArtwork, 0, firstArtwork.size)!!

        assertThat(networkCalls.get()).isEqualTo(1)
        assertThat(second.mediaMetadata.artworkData).isEqualTo(firstArtwork)
        assertThat(maxOf(decoded.width, decoded.height)).isAtMost(512)
    }

    @Test
    fun `loadChildren returns empty list when repository fails`() {
        val failingCallback = HypeMediaLibraryCallback(
            context = testContext,
            catalogRepository = FailingCatalogRepository,
            meRepository = EmptyMeRepository,
            searchRepository = EmptySearchRepository,
            offlineRepository = EmptyOfflineRepository,
            authRepository = SignedInAuthRepository,
            favoriteSyncManager = metadataTestFavoriteSyncManager(),
            okHttpClient = TestOkHttpClient,
        )

        val items = failingCallback.privateLoadChildren(HypeMediaIds.latest, pageSize = 20)

        assertThat(items).isEmpty()
    }

    @Test
    fun `loadItem resolves playable tracks for Auto item lookup`() {
        val trackCallback = HypeMediaLibraryCallback(
            context = testContext,
            catalogRepository = TrackCatalogRepository,
            meRepository = EmptyMeRepository,
            searchRepository = EmptySearchRepository,
            offlineRepository = EmptyOfflineRepository,
            authRepository = SignedInAuthRepository,
            favoriteSyncManager = metadataTestFavoriteSyncManager(),
            okHttpClient = TestOkHttpClient,
        )

        val item = trackCallback.privateLoadItem(HypeMediaIds.track(sampleTrack.id))

        assertThat(item).isNotNull()
        assertThat(item?.mediaId).isEqualTo(HypeMediaIds.track(sampleTrack.id))
        assertThat(item?.localConfiguration?.uri.toString()).isEqualTo(sampleTrack.streamUrl())
        assertThat(item?.mediaMetadata?.title.toString()).isEqualTo(sampleTrack.title)
    }

    @Test
    fun `resolveMediaItems expands id only Auto playback requests`() {
        val trackCallback = HypeMediaLibraryCallback(
            context = testContext,
            catalogRepository = TrackCatalogRepository,
            meRepository = EmptyMeRepository,
            searchRepository = EmptySearchRepository,
            offlineRepository = EmptyOfflineRepository,
            authRepository = SignedInAuthRepository,
            favoriteSyncManager = metadataTestFavoriteSyncManager(),
            okHttpClient = TestOkHttpClient,
        )
        val idOnlyItem = MediaItem.Builder()
            .setMediaId(HypeMediaIds.track(sampleTrack.id))
            .build()

        val items = trackCallback.privateResolveMediaItems(listOf(idOnlyItem))

        assertThat(items).hasSize(1)
        assertThat(items.single().mediaId).isEqualTo(HypeMediaIds.track(sampleTrack.id))
        assertThat(items.single().localConfiguration?.uri.toString()).isEqualTo(sampleTrack.streamUrl())
    }

    @Test
    fun `Auto playback expands a selected latest track into a section queue`() {
        val trackCallback = HypeMediaLibraryCallback(
            context = testContext,
            catalogRepository = LatestCatalogRepository,
            meRepository = EmptyMeRepository,
            searchRepository = EmptySearchRepository,
            offlineRepository = EmptyOfflineRepository,
            authRepository = SignedInAuthRepository,
            favoriteSyncManager = metadataTestFavoriteSyncManager(),
            okHttpClient = TestOkHttpClient,
        )
        val selectedItem = MediaItem.Builder()
            .setMediaId(HypeMediaIds.track(secondTrack.id, HypeMediaIds.latest))
            .build()

        val playbackItems = trackCallback.privateResolveMediaItemsWithStartPosition(
            mediaItems = listOf(selectedItem),
            startIndex = 0,
            startPositionMs = 0L,
        )

        assertThat(playbackItems.mediaItems).hasSize(3)
        assertThat(playbackItems.startIndex).isEqualTo(1)
        assertThat(playbackItems.mediaItems.map { HypeMediaIds.parseTrackId(it.mediaId) })
            .containsExactly(sampleTrack.id, secondTrack.id, thirdTrack.id)
            .inOrder()
    }

    @Test
    fun `Auto playback refetches the original source page when track media id encodes pg`() {
        val pagedRepo = PagedLatestCatalogRepository()
        val trackCallback = HypeMediaLibraryCallback(
            context = testContext,
            catalogRepository = pagedRepo,
            meRepository = EmptyMeRepository,
            searchRepository = EmptySearchRepository,
            offlineRepository = EmptyOfflineRepository,
            authRepository = SignedInAuthRepository,
            favoriteSyncManager = metadataTestFavoriteSyncManager(),
            okHttpClient = TestOkHttpClient,
        )
        val mediaId = HypeMediaIds.track(
            id = "page2-1",
            sourceId = HypeMediaIds.latest,
            sourcePage = 2,
        )
        val selectedItem = MediaItem.Builder().setMediaId(mediaId).build()

        val playbackItems = trackCallback.privateResolveMediaItemsWithStartPosition(
            mediaItems = listOf(selectedItem),
            startIndex = 0,
            startPositionMs = 0L,
        )

        assertThat(playbackItems.mediaItems).hasSize(2)
        assertThat(playbackItems.mediaItems.map { HypeMediaIds.parseTrackId(it.mediaId) })
            .containsExactly("page2-0", "page2-1").inOrder()
        assertThat(playbackItems.startIndex).isEqualTo(1)
        // Crucial: latest() was invoked with apiPage = sourcePage + 1 = 3, not 1.
        assertThat(pagedRepo.requestedApiPages).contains(3)
    }

    @Test
    fun `Auto search playback expands a selected search result into the result queue`() {
        val trackCallback = HypeMediaLibraryCallback(
            context = testContext,
            catalogRepository = EmptyCatalogRepository,
            meRepository = EmptyMeRepository,
            searchRepository = SearchTracksRepository,
            offlineRepository = EmptyOfflineRepository,
            authRepository = SignedInAuthRepository,
            favoriteSyncManager = metadataTestFavoriteSyncManager(),
            okHttpClient = TestOkHttpClient,
        )
        val selectedItem = MediaItem.Builder()
            .setMediaId(HypeMediaIds.track(thirdTrack.id, HypeMediaIds.search("lunon")))
            .build()

        val playbackItems = trackCallback.privateResolveMediaItemsWithStartPosition(
            mediaItems = listOf(selectedItem),
            startIndex = 0,
            startPositionMs = 0L,
        )

        assertThat(playbackItems.mediaItems).hasSize(2)
        assertThat(playbackItems.startIndex).isEqualTo(1)
        assertThat(playbackItems.mediaItems.map { HypeMediaIds.parseTrackId(it.mediaId) })
            .containsExactly(secondTrack.id, thirdTrack.id)
            .inOrder()
    }

    @Test
    fun `resolveMediaItemsWithStartPosition falls back to single-item when source page lookup fails`() {
        // Repository fails on every fetch; manager should fall back to the resolved single item.
        val callback = HypeMediaLibraryCallback(
            context = testContext,
            catalogRepository = TrackCatalogRepository,
            meRepository = EmptyMeRepository,
            searchRepository = EmptySearchRepository,
            offlineRepository = EmptyOfflineRepository,
            authRepository = SignedInAuthRepository,
            favoriteSyncManager = metadataTestFavoriteSyncManager(),
            okHttpClient = TestOkHttpClient,
        )
        val selectedItem = MediaItem.Builder()
            .setMediaId(HypeMediaIds.track(sampleTrack.id, HypeMediaIds.latest, sourcePage = 99))
            .build()

        val playbackItems = callback.privateResolveMediaItemsWithStartPosition(
            mediaItems = listOf(selectedItem),
            startIndex = 0,
            startPositionMs = 0L,
        )

        // Page 99 returns nothing (LatestCatalogRepository ignores page); selected track
        // is not found in the empty source queue, so we fall back to the single-item path.
        assertThat(playbackItems.mediaItems).hasSize(1)
        assertThat(HypeMediaIds.parseTrackId(playbackItems.mediaItems.single().mediaId))
            .isEqualTo(sampleTrack.id)
    }

    @Test
    fun `resolveMediaItems leaves items with localConfiguration alone`() {
        val callback = HypeMediaLibraryCallback(
            context = testContext,
            catalogRepository = EmptyCatalogRepository,
            meRepository = EmptyMeRepository,
            searchRepository = EmptySearchRepository,
            offlineRepository = EmptyOfflineRepository,
            authRepository = SignedInAuthRepository,
            favoriteSyncManager = metadataTestFavoriteSyncManager(),
            okHttpClient = TestOkHttpClient,
        )
        val prebuilt = MediaItem.Builder()
            .setMediaId("track:already-resolved")
            .setUri("https://hypem.com/serve/public/already-resolved")
            .build()

        val resolved = callback.privateResolveMediaItems(listOf(prebuilt))

        // Same item passes through.
        assertThat(resolved.single().mediaId).isEqualTo("track:already-resolved")
        assertThat(resolved.single().localConfiguration?.uri.toString())
            .isEqualTo("https://hypem.com/serve/public/already-resolved")
    }

    @Test
    fun `resolveMediaItemsWithStartPosition coerces invalid startIndex inside the resolved range`() {
        val callback = HypeMediaLibraryCallback(
            context = testContext,
            catalogRepository = EmptyCatalogRepository,
            meRepository = EmptyMeRepository,
            searchRepository = EmptySearchRepository,
            offlineRepository = EmptyOfflineRepository,
            authRepository = SignedInAuthRepository,
            favoriteSyncManager = metadataTestFavoriteSyncManager(),
            okHttpClient = TestOkHttpClient,
        )
        val resolvedItem = MediaItem.Builder()
            .setMediaId(HypeMediaIds.track("only"))
            .setUri("https://hypem.com/serve/public/only")
            .build()

        val playbackItems = callback.privateResolveMediaItemsWithStartPosition(
            mediaItems = listOf(resolvedItem),
            startIndex = 999,
            startPositionMs = 12_345L,
        )

        assertThat(playbackItems.mediaItems).hasSize(1)
        assertThat(playbackItems.startIndex).isEqualTo(0)
        assertThat(playbackItems.startPositionMs).isEqualTo(12_345L)
    }

    @Test
    fun `loadItem on a section id returns a browsable section item`() {
        val callback = HypeMediaLibraryCallback(
            context = testContext,
            catalogRepository = EmptyCatalogRepository,
            meRepository = EmptyMeRepository,
            searchRepository = EmptySearchRepository,
            offlineRepository = EmptyOfflineRepository,
            authRepository = SignedInAuthRepository,
            favoriteSyncManager = metadataTestFavoriteSyncManager(),
            okHttpClient = TestOkHttpClient,
        )

        val item = callback.privateLoadItem(HypeMediaIds.favorites)

        assertThat(item).isNotNull()
        assertThat(item?.mediaId).isEqualTo(HypeMediaIds.favorites)
        assertThat(item?.mediaMetadata?.isBrowsable).isTrue()
    }

    @Test
    fun `loadItem on a playlist id returns a browsable playlist item with the resolved name`() {
        val callback = HypeMediaLibraryCallback(
            context = testContext,
            catalogRepository = EmptyCatalogRepository,
            meRepository = PlaylistsMeRepository(playlists = listOf(Playlist(id = 5, name = "Late nights"))),
            searchRepository = EmptySearchRepository,
            offlineRepository = EmptyOfflineRepository,
            authRepository = SignedInAuthRepository,
            favoriteSyncManager = metadataTestFavoriteSyncManager(),
            okHttpClient = TestOkHttpClient,
        )

        val item = callback.privateLoadItem(HypeMediaIds.playlist(5))

        assertThat(item).isNotNull()
        assertThat(item?.mediaId).isEqualTo(HypeMediaIds.playlist(5))
        assertThat(item?.mediaMetadata?.isBrowsable).isTrue()
        assertThat(item?.mediaMetadata?.title.toString()).isEqualTo("Late nights")
    }

    @Test
    fun `loadItem on a playlist id with no matching name falls back to default title`() {
        val callback = HypeMediaLibraryCallback(
            context = testContext,
            catalogRepository = EmptyCatalogRepository,
            meRepository = EmptyMeRepository,
            searchRepository = EmptySearchRepository,
            offlineRepository = EmptyOfflineRepository,
            authRepository = SignedInAuthRepository,
            favoriteSyncManager = metadataTestFavoriteSyncManager(),
            okHttpClient = TestOkHttpClient,
        )

        val item = callback.privateLoadItem(HypeMediaIds.playlist(999))

        assertThat(item).isNotNull()
        assertThat(item?.mediaMetadata?.title.toString()).isEqualTo("Playlist 999")
    }

    @Test
    fun `loadChildren on a search-prefixed parentId returns search results`() {
        val callback = HypeMediaLibraryCallback(
            context = testContext,
            catalogRepository = EmptyCatalogRepository,
            meRepository = EmptyMeRepository,
            searchRepository = SearchTracksRepository,
            offlineRepository = EmptyOfflineRepository,
            authRepository = SignedInAuthRepository,
            favoriteSyncManager = metadataTestFavoriteSyncManager(),
            okHttpClient = TestOkHttpClient,
        )

        val items = callback.privateLoadChildren(
            parentId = HypeMediaIds.search("lunon"),
            pageSize = 20,
        )

        // SearchTracksRepository returns 2 fixed tracks regardless of query.
        assertThat(items.map { HypeMediaIds.parseTrackId(it.mediaId) })
            .containsExactly(secondTrack.id, thirdTrack.id).inOrder()
    }

    @Test
    fun `loadChildren on a playlist-prefixed parentId returns the playlist tracks`() {
        val callback = HypeMediaLibraryCallback(
            context = testContext,
            catalogRepository = EmptyCatalogRepository,
            meRepository = PlaylistTracksMeRepository(tracks = listOf(sampleTrack, secondTrack)),
            searchRepository = EmptySearchRepository,
            offlineRepository = EmptyOfflineRepository,
            authRepository = SignedInAuthRepository,
            favoriteSyncManager = metadataTestFavoriteSyncManager(),
            okHttpClient = TestOkHttpClient,
        )

        val items = callback.privateLoadChildren(
            parentId = HypeMediaIds.playlist(42),
            pageSize = 20,
        )

        assertThat(items.map { HypeMediaIds.parseTrackId(it.mediaId) })
            .containsExactly(sampleTrack.id, secondTrack.id).inOrder()
    }

    @Test
    fun `loadChildren on a section_playlists parentId returns browsable playlist items`() {
        val callback = HypeMediaLibraryCallback(
            context = testContext,
            catalogRepository = EmptyCatalogRepository,
            meRepository = PlaylistsMeRepository(
                playlists = listOf(Playlist(id = 1, name = "Mixes"), Playlist(id = 2, name = "Sets")),
            ),
            searchRepository = EmptySearchRepository,
            offlineRepository = EmptyOfflineRepository,
            authRepository = SignedInAuthRepository,
            favoriteSyncManager = metadataTestFavoriteSyncManager(),
            okHttpClient = TestOkHttpClient,
        )

        val items = callback.privateLoadChildren(
            parentId = HypeMediaIds.playlists,
            pageSize = 20,
        )

        assertThat(items).hasSize(2)
        assertThat(items.all { it.mediaMetadata.isBrowsable == true }).isTrue()
        assertThat(items.map { it.mediaMetadata.title.toString() })
            .containsExactly("Mixes", "Sets").inOrder()
    }

    @Test
    fun `loadChildren under an unknown parentId yields an empty list`() {
        val callback = HypeMediaLibraryCallback(
            context = testContext,
            catalogRepository = EmptyCatalogRepository,
            meRepository = EmptyMeRepository,
            searchRepository = EmptySearchRepository,
            offlineRepository = EmptyOfflineRepository,
            authRepository = SignedInAuthRepository,
            favoriteSyncManager = metadataTestFavoriteSyncManager(),
            okHttpClient = TestOkHttpClient,
        )

        val items = callback.privateLoadChildren(parentId = "totally:unknown", pageSize = 20)

        assertThat(items).isEmpty()
    }
}

private fun HypeMediaLibraryCallback.privateBrowsableItem(
    mediaId: String,
    title: String,
): MediaItem {
    val method = javaClass.getDeclaredMethod(
        "browsableItem",
        String::class.java,
        String::class.java,
    )
    method.isAccessible = true
    return method.invoke(this, mediaId, title) as MediaItem
}

private fun HypeMediaLibraryCallback.privatePlayableItem(track: Track): MediaItem {
    val method = javaClass.getDeclaredMethod(
        "toPlayableItem",
        Track::class.java,
        String::class.java,
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
    )
    method.isAccessible = true
    return method.invoke(this, track, null, 0, 0) as MediaItem
}

@Suppress("UNCHECKED_CAST")
private fun HypeMediaLibraryCallback.privateMediaButtonPreferences(loved: Boolean = false): List<CommandButton> {
    val method = javaClass.getDeclaredMethod(
        "buildMediaButtonPreferences",
        Boolean::class.javaPrimitiveType,
    )
    method.isAccessible = true
    return method.invoke(this, loved) as List<CommandButton>
}

@Suppress("UNCHECKED_CAST")
private fun HypeMediaLibraryCallback.privateCarMediaButtonPreferences(loved: Boolean): List<CommandButton> {
    val method = javaClass.getDeclaredMethod(
        "buildCarMediaButtonPreferences",
        Boolean::class.javaPrimitiveType,
    )
    method.isAccessible = true
    return method.invoke(this, loved) as List<CommandButton>
}

@Suppress("UNCHECKED_CAST")
private fun HypeMediaLibraryCallback.privateNowPlayingLayout(loved: Boolean): List<CommandButton> {
    val method = javaClass.getDeclaredMethod(
        "buildNowPlayingLayout",
        Boolean::class.javaPrimitiveType,
    )
    method.isAccessible = true
    return method.invoke(this, loved) as List<CommandButton>
}

@Suppress("UNCHECKED_CAST")
private fun HypeMediaLibraryCallback.privateNotificationCustomLayout(loved: Boolean): List<CommandButton> {
    val method = javaClass.getDeclaredMethod(
        "buildNotificationCustomLayout",
        Boolean::class.javaPrimitiveType,
    )
    method.isAccessible = true
    return method.invoke(this, loved) as List<CommandButton>
}

private fun HypeMediaLibraryCallback.privateFavoriteStateFor(mediaItem: MediaItem): Boolean {
    val method = javaClass.getDeclaredMethod(
        "favoriteStateFor",
        MediaItem::class.java,
    )
    method.isAccessible = true
    return method.invoke(this, mediaItem) as Boolean
}

private fun HypeMediaLibraryCallback.privateRememberFavoriteState(trackId: String, loved: Boolean) {
    val method = javaClass.getDeclaredMethod(
        "rememberFavoriteState",
        String::class.java,
        Boolean::class.javaPrimitiveType,
    )
    method.isAccessible = true
    method.invoke(this, trackId, loved)
}

private fun HypeMediaLibraryCallback.privateWithInlineArtworkForTests(mediaItem: MediaItem): MediaItem {
    val method = javaClass.getDeclaredMethod(
        "withInlineArtworkForTests",
        MediaItem::class.java,
    )
    method.isAccessible = true
    return method.invoke(this, mediaItem) as MediaItem
}

@Suppress("UNCHECKED_CAST")
private fun HypeMediaLibraryCallback.privateLoadChildren(
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

private fun HypeMediaLibraryCallback.privateLoadItem(mediaId: String): MediaItem? {
    val method = javaClass.getDeclaredMethod("loadItem", String::class.java)
    method.isAccessible = true
    return method.invoke(this, mediaId) as MediaItem?
}

@Suppress("UNCHECKED_CAST")
private fun HypeMediaLibraryCallback.privateResolveMediaItems(mediaItems: List<MediaItem>): List<MediaItem> {
    val method = javaClass.getDeclaredMethod("resolveMediaItems", List::class.java)
    method.isAccessible = true
    return method.invoke(this, mediaItems) as List<MediaItem>
}

private fun HypeMediaLibraryCallback.privateResolveMediaItemsWithStartPosition(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
): MediaSession.MediaItemsWithStartPosition {
    val method = javaClass.getDeclaredMethod(
        "resolveMediaItemsWithStartPosition",
        List::class.java,
        Int::class.javaPrimitiveType,
        Long::class.javaPrimitiveType,
    )
    method.isAccessible = true
    return method.invoke(this, mediaItems, startIndex, startPositionMs) as MediaSession.MediaItemsWithStartPosition
}

private val sampleTrack = Track(
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

private val secondTrack = sampleTrack.copy(
    id = "abc123",
    artist = "Shara Lunon",
    title = "Nourishment",
)

private val thirdTrack = sampleTrack.copy(
    id = "def456",
    artist = "Melvin Gibbs",
    title = "Holy Ground",
)

private object EmptyCatalogRepository : CatalogRepository {
    override suspend fun latest(mode: LatestMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun popular(mode: PopularMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun track(trackId: String): Track = sampleTrack
    override suspend fun blogs(page: Int, count: Int): List<Blog> = emptyList()
    override suspend fun blog(blogId: Int): Blog = error("Not used")
    override suspend fun blogTracks(blogId: Int, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun user(username: String): User = error("Not used")
    override suspend fun userFavorites(username: String, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun userFriends(username: String, page: Int, count: Int): List<User> = emptyList()
}

private object FailingCatalogRepository : CatalogRepository {
    override suspend fun latest(mode: LatestMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> = error("Network unavailable")
    override suspend fun popular(mode: PopularMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> = error("Network unavailable")
    override suspend fun track(trackId: String): Track = error("Network unavailable")
    override suspend fun blogs(page: Int, count: Int): List<Blog> = error("Network unavailable")
    override suspend fun blog(blogId: Int): Blog = error("Network unavailable")
    override suspend fun blogTracks(blogId: Int, page: Int, count: Int): List<Track> = error("Network unavailable")
    override suspend fun user(username: String): User = error("Network unavailable")
    override suspend fun userFavorites(username: String, page: Int, count: Int): List<Track> = error("Network unavailable")
    override suspend fun userFriends(username: String, page: Int, count: Int): List<User> = error("Network unavailable")
}

private object TrackCatalogRepository : CatalogRepository {
    override suspend fun latest(mode: LatestMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun popular(mode: PopularMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun track(trackId: String): Track = sampleTrack.takeIf { it.id == trackId } ?: error("Unknown track")
    override suspend fun blogs(page: Int, count: Int): List<Blog> = emptyList()
    override suspend fun blog(blogId: Int): Blog = error("Not used")
    override suspend fun blogTracks(blogId: Int, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun user(username: String): User = error("Not used")
    override suspend fun userFavorites(username: String, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun userFriends(username: String, page: Int, count: Int): List<User> = emptyList()
}

private object LatestCatalogRepository : CatalogRepository {
    override suspend fun latest(mode: LatestMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> =
        listOf(sampleTrack, secondTrack, thirdTrack)

    override suspend fun popular(mode: PopularMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun track(trackId: String): Track = listOf(sampleTrack, secondTrack, thirdTrack)
        .firstOrNull { it.id == trackId }
        ?: error("Unknown track")
    override suspend fun blogs(page: Int, count: Int): List<Blog> = emptyList()
    override suspend fun blog(blogId: Int): Blog = error("Not used")
    override suspend fun blogTracks(blogId: Int, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun user(username: String): User = error("Not used")
    override suspend fun userFavorites(username: String, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun userFriends(username: String, page: Int, count: Int): List<User> = emptyList()
}

private object EmptyMeRepository : MeRepository {
    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = false
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: dev.josu.hypecar.core.model.FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private object EmptySearchRepository : SearchRepository {
    override suspend fun searchTracks(query: SearchQuery, page: Int, count: Int): List<Track> = emptyList()
}

private object SearchTracksRepository : SearchRepository {
    override suspend fun searchTracks(query: SearchQuery, page: Int, count: Int): List<Track> =
        listOf(secondTrack, thirdTrack)
}

private object SignedInAuthRepository : AuthRepository {
    override val session: Flow<AuthSession?> = flowOf(AuthSession(username = "tester", token = "tok"))
    override suspend fun login(usernameOrEmail: String, password: String): Result<AuthSession> = error("not used")
    override suspend fun logout() = Unit
}

private object EmptyOfflineRepository : OfflineRepository {
    override val status: StateFlow<OfflineDownloadStatus> = MutableStateFlow(OfflineDownloadStatus())
    override suspend fun setEnabled(enabled: Boolean) = Unit
    override suspend fun setQuotaBytes(quotaBytes: Long) = Unit
    override suspend fun syncFavorites() = Unit
    override suspend fun clearDownloads() = Unit
    override fun cachedAudioUri(trackId: String): String? = null
}

/**
 * Returns a fresh 2-track page per latest() call and records which API page
 * was requested, so tests can verify page-aware selection actually fetches
 * the right page (not always page 1).
 */
private class PagedLatestCatalogRepository : CatalogRepository {
    val requestedApiPages = mutableListOf<Int>()

    override suspend fun latest(mode: LatestMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> {
        requestedApiPages += page
        // page is 1-based (apiPage). Source page index = page - 1.
        val sourcePage = page - 1
        return listOf(
            sampleTrack.copy(id = "page$sourcePage-0"),
            sampleTrack.copy(id = "page$sourcePage-1"),
        )
    }

    override suspend fun popular(mode: PopularMode, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun track(trackId: String): Track = error("not used")
    override suspend fun blogs(page: Int, count: Int): List<Blog> = emptyList()
    override suspend fun blog(blogId: Int): Blog = error("not used")
    override suspend fun blogTracks(blogId: Int, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun user(username: String): User = error("not used")
    override suspend fun userFavorites(username: String, page: Int, count: Int): List<Track> = emptyList()
    override suspend fun userFriends(username: String, page: Int, count: Int): List<User> = emptyList()
}

private class PlaylistsMeRepository(
    private val playlists: List<Playlist>,
) : MeRepository {
    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<Playlist> = playlists
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun feed(mode: dev.josu.hypecar.core.model.FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private class PlaylistTracksMeRepository(
    private val tracks: List<Track>,
) : MeRepository {
    override suspend fun favorites(page: Int, count: Int, forceRefresh: Boolean): List<Track> = emptyList()
    override suspend fun toggleFavorite(trackId: String): Boolean? = null
    override suspend fun playlistNames(): List<Playlist> = emptyList()
    override suspend fun playlist(playlistId: Int, page: Int, count: Int, forceRefresh: Boolean): List<Track> = tracks
    override suspend fun feed(mode: dev.josu.hypecar.core.model.FeedMode, page: Int, count: Int, forceRefresh: Boolean): List<FeedItem> = emptyList()
    override suspend fun history(page: Int, count: Int): List<Track> = emptyList()
}

private val TestOkHttpClient: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder().build()

private fun testJpegBytes(width: Int, height: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.rgb(220, 92, 34))
    return try {
        java.io.ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
            output.toByteArray()
        }
    } finally {
        bitmap.recycle()
    }
}

private object MetadataNoOpPlaybackRepository : dev.josu.hypecar.core.model.repository.PlaybackRepository {
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

private fun metadataTestFavoriteSyncManager() =
    dev.josu.hypecar.core.data.repository.FavoriteSyncManager(EmptyMeRepository, MetadataNoOpPlaybackRepository)
