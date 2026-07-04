package dev.josu.hypecar.core.model

enum class LatestMode(val apiValue: String) {
    ALL("all"),
    FRESHEST("fresh"),
    NO_REMIXES("noremix"),
    ONLY_REMIXES("remix"),
}

enum class PopularMode(val apiValue: String) {
    NOW("now"),
    NO_REMIXES("noremix"),
    ONLY_REMIXES("remix"),
    LAST_WEEK("lastweek"),
}

enum class FeedMode(val apiValue: String) {
    ALL("all"),
    FRIENDS("friends"),
    BLOGS("blogs"),
}
