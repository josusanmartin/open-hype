package dev.josu.hypecar.feature.details

import dev.josu.hypecar.core.model.User

/**
 * User profile header data. Carries the raw counts (favorites, followers,
 * following) so the consuming composable can format them with
 * `pluralStringResource(R.plurals.user_profile_*)` — the previous model
 * pre-baked the English "N favorites" strings here, bypassing the
 * translation system.
 */
data class UserProfileHeaderUiModel(
    val title: String,
    val handle: String,
    val favoritesCount: Int,
    val followersCount: Int,
    val followingCount: Int,
) {
    companion object {
        fun from(user: User): UserProfileHeaderUiModel = UserProfileHeaderUiModel(
            title = user.fullName ?: user.username,
            handle = "@${user.username}",
            favoritesCount = user.favoritesCount,
            followersCount = user.followersCount,
            followingCount = user.followingCount,
        )
    }
}
