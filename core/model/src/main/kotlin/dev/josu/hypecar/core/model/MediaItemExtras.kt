package dev.josu.hypecar.core.model

/**
 * Keys for the extras `Bundle` carried on Media3 `MediaMetadata`. Shared
 * between the phone playback engine (which stamps them when building media
 * items) and the Android Auto service (which reads them to render the
 * favorite heart and browse metadata). Keeping them here means both modules
 * agree on the contract without depending on each other.
 */
object MediaItemExtras {
    const val IsLoved = "is_loved"
    const val BlogId = "blog_id"
    const val BlogName = "blog_name"
    const val LovedCount = "loved_count"
}
