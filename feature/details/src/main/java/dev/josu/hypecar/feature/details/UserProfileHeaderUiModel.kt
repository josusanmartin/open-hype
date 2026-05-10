package dev.josu.hypecar.feature.details

import dev.josu.hypecar.core.model.User

data class UserProfileHeaderUiModel(
    val title: String,
    val handle: String,
    val stats: List<String>,
    val summaryLine: String,
) {
    companion object {
        fun from(user: User): UserProfileHeaderUiModel {
            val stats = listOf(
                "${user.favoritesCount} favorites",
                "${user.followersCount} followers",
                "${user.followingCount} following",
            )

            return UserProfileHeaderUiModel(
                title = user.fullName ?: user.username,
                handle = "@${user.username}",
                stats = stats,
                summaryLine = stats.joinToString(" · "),
            )
        }
    }
}
