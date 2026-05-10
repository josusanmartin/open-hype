package dev.josu.hypecar.core.model

data class SearchQuery(
    val value: String,
    val sort: SearchSort = SearchSort.NEWEST,
)

enum class SearchSort(val apiValue: String, val displayLabel: String) {
    NEWEST("latest", "Newest"),
    MOST_FAVORITES("loved", "Most favorited"),
    MOST_REBLOGGED("posted", "Most reblogged"),
}
