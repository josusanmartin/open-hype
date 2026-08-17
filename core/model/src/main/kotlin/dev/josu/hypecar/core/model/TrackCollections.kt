package dev.josu.hypecar.core.model

/**
 * Appends a newly fetched page while keeping exactly one row per track id.
 * Existing order is stable, overlapping rows receive the freshest metadata,
 * and genuinely new rows are appended in server order.
 */
fun List<Track>.mergePageByTrackId(fresh: List<Track>): List<Track> {
    if (isEmpty()) return fresh.distinctBy(Track::id)
    if (fresh.isEmpty()) return distinctBy(Track::id)

    val merged = LinkedHashMap<String, Track>(size + fresh.size)
    forEach { track -> merged[track.id] = track }
    fresh.forEach { track -> merged[track.id] = track }
    return merged.values.toList()
}

/** Removes account-personal favorite state while retaining public metadata. */
fun List<Track>.withoutPersonalFavoriteState(): List<Track> =
    map { track ->
        if (track.isLoved) track.copy(isLoved = false) else track
    }
