package dev.josu.hypecar.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A slim banner that announces "you're offline — showing cached data" any
 * time the device leaves [dev.josu.hypecar.core.model.repository.Connectivity.Online].
 *
 * Animates in/out with a vertical slide so it can sit above a navigation bar
 * (`Column { ConnectivityBanner(); BottomBar() }`) without permanently
 * adding chrome height when the network is fine. Sets `liveRegion = Polite`
 * so TalkBack announces appearances without interrupting the user.
 *
 * Visual style is sourced from the dark token table to read clearly on both
 * the cream daytime canvas and the dark nighttime canvas.
 */
@Composable
fun ConnectivityBanner(
    isOffline: Boolean,
    modifier: Modifier = Modifier,
    isLimited: Boolean = false,
) {
    val visible = isOffline || isLimited
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val labelRes = if (isOffline) {
            R.string.connectivity_banner_offline
        } else {
            R.string.connectivity_banner_limited
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isOffline) BannerBgOffline else BannerBgLimited)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = BannerTextColor,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(labelRes),
                color = BannerTextColor,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private val BannerBgOffline = Color(0xFF5A2A14)
private val BannerBgLimited = Color(0xFF3E3622)
private val BannerTextColor = Color(0xFFFFE3CF)
