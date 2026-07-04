package dev.josu.hypecar.auto.service

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.media.utils.MediaConstants
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaLibraryService
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.josu.hypecar.auto.HypeMediaIds
import dev.josu.hypecar.auto.R
import dev.josu.hypecar.core.model.AuthSession
import dev.josu.hypecar.core.model.Blog
import dev.josu.hypecar.core.model.FeedItem
import dev.josu.hypecar.core.model.FeedMode
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
class HypeMediaLibraryCallbackBrowseTest {
    private val testContext: android.content.Context get() = ApplicationProvider.getApplicationContext()

    private fun buildCallback(
        authRepository: AuthRepository = BrowseSignedInAuthRepository,
        meRepository: MeRepository = BrowseEmptyMeRepository,
        catalogRepository: CatalogRepository = BrowseEmptyCatalogRepository,
        searchRepository: SearchRepository = BrowseEmptySearchRepository,
    ): HypeMediaLibraryCallback = HypeMediaLibraryCallback(
        context = testContext,
        catalogRepository = catalogRepository,
        meRepository = meRepository,
        searchRepository = searchRepository,
        offlineRepository = BrowseEmptyOfflineRepository,
        authRepository = authRepository,
        favoriteSyncManager = browseTestFavoriteSyncManager(),
        okHttpClient = BrowseTestOkHttpClient,
    )

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

        assertThat(extras.getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
            .isEqualTo(MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        assertThat(extras.getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
            .isEqualTo(MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    @Test
    fun `search results request compact list rows for Auto hosts`() {
        val callback = buildCallback()

        val params = callback.privateSearchParamsWithHints()
        val extras = params.extras

        assertThat(extras.getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE))
            .isEqualTo(MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        assertThat(extras.getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE))
            .isEqualTo(MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
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

private object BrowseEmptyOfflineRepository : OfflineRepository {
    override val status: StateFlow<OfflineDownloadStatus> = MutableStateFlow(OfflineDownloadStatus())
    override suspend fun setEnabled(enabled: Boolean) = Unit
    override suspend fun setQuotaBytes(quotaBytes: Long) = Unit
    override suspend fun syncFavorites() = Unit
    override suspend fun clearDownloads() = Unit
    override fun cachedAudioUri(trackId: String): String? = null
}

private val BrowseTestOkHttpClient: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder().build()

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
