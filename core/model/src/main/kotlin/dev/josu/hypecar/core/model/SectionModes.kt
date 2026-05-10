package dev.josu.hypecar.core.model

enum class LatestMode(val apiValue: String, val displayLabel: String) {
    ALL("all", "All"),
    FRESHEST("fresh", "Freshest"),
    NO_REMIXES("noremix", "No remixes"),
    ONLY_REMIXES("remix", "Only remixes"),
}

enum class PopularMode(val apiValue: String, val displayLabel: String) {
    NOW("now", "Now"),
    NO_REMIXES("noremix", "No remixes"),
    ONLY_REMIXES("remix", "Only remixes"),
    LAST_WEEK("lastweek", "Last week"),
}

enum class FeedMode(val apiValue: String, val displayLabel: String) {
    ALL("all", "All"),
    FRIENDS("friends", "Friends"),
    BLOGS("blogs", "Blogs"),
}
