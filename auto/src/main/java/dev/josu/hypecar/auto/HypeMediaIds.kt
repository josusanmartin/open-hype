package dev.josu.hypecar.auto

import java.net.URLDecoder
import java.net.URLEncoder

object HypeMediaIds {
    const val root = "root"
    const val latest = "section:latest"
    const val popular = "section:popular"
    const val favorites = "section:favorites"
    const val feed = "section:feed"
    const val playlists = "section:playlists"
    const val history = "section:history"

    /**
     * Umbrella browsable section introduced as part of the Android Auto
     * top-level reduction (six tabs → four). Children are [feed], [playlists],
     * and [history]. The three are individually browsable directly via their
     * own section ids too, so existing callers continue to work.
     */
    const val more = "section:more"

    /** Sub-set of [latest]/[popular]/[history] surfaced under a "Discover" parent. Optional; kept as a no-op alias today. */
    const val discoverPlaceholder = "section:more"

    fun playlist(id: Int): String = "playlist:$id"

    fun track(id: String): String = "track:$id"

    fun track(id: String, sourceId: String, sourcePage: Int = 0): String {
        val base = "${track(id)}?src=${sourceId.urlEncode()}"
        return if (sourcePage > 0) "$base&pg=$sourcePage" else base
    }

    fun search(query: String): String = "search:${query.urlEncode()}"

    fun parsePlaylistId(mediaId: String): Int? =
        mediaId.removePrefix("playlist:").takeIf { mediaId.startsWith("playlist:") }?.toIntOrNull()

    fun parseTrackId(mediaId: String): String? =
        mediaId.removePrefix("track:")
            .substringBefore("?")
            .takeIf { mediaId.startsWith("track:") && it.isNotBlank() }

    fun parseTrackSourceId(mediaId: String): String? {
        if (!mediaId.contains("?")) return null
        val params = mediaId.substringAfter("?")
        return params.split("&")
            .firstOrNull { it.startsWith("src=") }
            ?.removePrefix("src=")
            ?.takeIf { it.isNotBlank() }
            ?.urlDecode()
    }

    fun parseTrackSourcePage(mediaId: String): Int {
        if (!mediaId.contains("?")) return 0
        val params = mediaId.substringAfter("?")
        return params.split("&")
            .firstOrNull { it.startsWith("pg=") }
            ?.removePrefix("pg=")
            ?.toIntOrNull()
            ?.coerceAtLeast(0)
            ?: 0
    }

    fun parseSearchQuery(mediaId: String): String? =
        mediaId.removePrefix("search:")
            .takeIf { mediaId.startsWith("search:") && it.isNotBlank() }
            ?.urlDecode()

    private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    private fun String.urlDecode(): String = URLDecoder.decode(this, Charsets.UTF_8.name())
}
