package dev.josu.hypecar.feature.catalog

import dev.josu.hypecar.core.model.Track

data class TrackRowUiModel(
    val coverArtUrl: String?,
    val coverArtWidthDp: Int,
    val statsLine: String,
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
            statsLine = "${track.lovedCount}   ·   reposted ${track.postedCount}x",
            titleLine = track.title,
            artistLine = track.artist,
            sourceLabel = track.postedBy,
            description = track.postDescription,
            rank = track.rank,
        )
    }
}
