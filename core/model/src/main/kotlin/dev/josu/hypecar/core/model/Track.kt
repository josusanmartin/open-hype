package dev.josu.hypecar.core.model

data class Track(
    val id: String,
    val artist: String,
    val title: String,
    val lovedCount: Int,
    val postedBy: String,
    val postedById: Int,
    val postedCount: Int,
    val postDescription: String,
    val datePostedEpochSeconds: Long,
    val postUrl: String,
    val itunesUrl: String,
    val thumbnails: TrackThumbnails = TrackThumbnails(),
    val rank: Int? = null,
    val viaUser: String? = null,
    val viaQuery: String? = null,
    val isLoved: Boolean = false,
    val audioUnavailable: Boolean = false,
    val mediaType: String? = null,
) {
    fun streamUrl(apiKey: String? = null): String {
        val base = "https://hypem.com/serve/public/$id"
        return if (apiKey.isNullOrBlank()) base else "$base?key=$apiKey"
    }

    fun bestThumbnail(): String? = thumbnails.large ?: thumbnails.medium ?: thumbnails.small
}

data class TrackThumbnails(
    val small: String? = null,
    val medium: String? = null,
    val large: String? = null,
)
