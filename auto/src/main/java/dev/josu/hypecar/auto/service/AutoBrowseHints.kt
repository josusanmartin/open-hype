package dev.josu.hypecar.auto.service

import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants

/**
 * Centralizes the Android Auto / Car App content-style hints that tell the
 * automotive HUD how to render browse nodes. Without these the car falls back
 * to its default rendering, which varies heavily by host. Applying them keeps
 * Open Hype compact and predictable on projected Android Auto screens.
 *
 * Reference: https://developer.android.com/training/cars/media#styling
 *
 * Media3 carries these hints through the metadata `extras` bundle to legacy
 * and current Android Auto hosts.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object AutoBrowseHints {
    /**
     * Extras for a browsable parent: tell the car to render its CHILDREN as
     * compact list rows (playable tracks and playlists) or category rows
     * (top-level navigation).
     */
    fun parentHints(childStyle: ChildStyle): Bundle = Bundle().apply {
        putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            when (childStyle) {
                ChildStyle.LIST_BROWSABLE -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
                ChildStyle.CATEGORY_LIST -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM
            },
        )
        putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            when (childStyle) {
                ChildStyle.LIST_BROWSABLE -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
                ChildStyle.CATEGORY_LIST -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            },
        )
    }

    /** Extras for a single browsable section tile to render itself as a category chip. */
    fun selfHintCategory(): Bundle = Bundle().apply {
        putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM,
        )
    }

    /**
     * Extras attached to a placeholder / error item so it renders as a single
     * full-width informational row (no artwork, no play affordance).
     */
    fun placeholderHints(): Bundle = Bundle().apply {
        putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
        )
    }

    enum class ChildStyle {
        /** Children are compact list rows. Best for playable tracks and browsable playlist names. */
        LIST_BROWSABLE,

        /** Children are flat list rows split into categories. Best for the root level. */
        CATEGORY_LIST,
    }
}
