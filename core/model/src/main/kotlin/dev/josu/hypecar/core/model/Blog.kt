package dev.josu.hypecar.core.model

data class Blog(
    val id: Int,
    val name: String,
    val url: String,
    val followerCount: Int,
    val trackCount: Int,
    val imageUrl: String? = null,
    val imageUrlSmall: String? = null,
    val featured: Boolean = false,
    val following: Boolean = false,
)
