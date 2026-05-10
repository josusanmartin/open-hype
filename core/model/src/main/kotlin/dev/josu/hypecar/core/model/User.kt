package dev.josu.hypecar.core.model

data class User(
    val username: String,
    val fullName: String? = null,
    val avatarUrl: String? = null,
    val favoritesCount: Int = 0,
    val followersCount: Int = 0,
    val followingCount: Int = 0,
    val isFriend: Boolean? = null,
    val isFollower: Boolean? = null,
)
