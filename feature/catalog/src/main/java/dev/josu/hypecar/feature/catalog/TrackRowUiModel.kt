package dev.josu.hypecar.feature.catalog

import dev.josu.hypecar.core.model.Track

/**
 * Pre-formatted, locale-independent fields for a catalog row.
 *
 * The stats line used to be assembled here as a hardcoded English string
 * (`"27   ·   reposted 3x"`). Counts now travel raw and the row composable
 * formats them with `pluralStringResource(R.plurals.track_row_stats, …)` so
 * translations work and pluralization is correct.
 */
data class TrackRowUiModel(
    val coverArtUrl: String?,
    val coverArtWidthDp: Int,
    val lovedCount: Int,
    val postedCount: Int,
    val titleLine: String,
    val artistLine: String,
    val sourceLabel: String,
    val description: String,
    val rank: Int?,
) {
    companion object {
        private const val FixedCoverArtWidthDp = 104

        fun from(track: Track): TrackRowUiModel = TrackRowUiModel(
            coverArtUrl = track.bestThumbnail(),
            coverArtWidthDp = FixedCoverArtWidthDp,
            lovedCount = track.lovedCount,
            postedCount = track.postedCount,
            titleLine = track.title,
            artistLine = track.artist,
            sourceLabel = track.postedBy,
            description = track.postDescription,
            rank = track.rank,
        )
    }
}
