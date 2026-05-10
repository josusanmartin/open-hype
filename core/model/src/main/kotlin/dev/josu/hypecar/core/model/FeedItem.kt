package dev.josu.hypecar.core.model

data class FeedItem(
    val track: Track,
    val source: String = track.viaUser ?: track.postedBy,
)
