package dev.josu.hypecar.core.model

data class SearchQuery(
    val value: String,
    val sort: SearchSort = SearchSort.NEWEST,
)

enum class SearchSort(val apiValue: String) {
    NEWEST("latest"),
    MOST_FAVORITES("loved"),
    MOST_REBLOGGED("posted"),
}
