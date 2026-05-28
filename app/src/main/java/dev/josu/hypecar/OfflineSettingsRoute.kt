package dev.josu.hypecar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.josu.hypecar.core.model.repository.OfflineDownloadStatus
import dev.josu.hypecar.core.model.repository.OfflineRepository
import dev.josu.hypecar.core.ui.hypeTokens
import dev.josu.hypecar.core.ui.pressFeedback
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private val OfflineQuotaOptions = listOf(
    250L * 1024L * 1024L,
    500L * 1024L * 1024L,
    1024L * 1024L * 1024L,
    2L * 1024L * 1024L * 1024L,
)

enum class OfflineSyncStatus {
    SYNCING,
    SYNCED,
    WAITING,
    OFF,
    ;

    companion object {
        fun from(status: OfflineDownloadStatus): OfflineSyncStatus = when {
            status.isSyncing -> SYNCING
            status.lastSyncedAtEpochSeconds != null -> SYNCED
            status.isEnabled -> WAITING
            else -> OFF
        }
    }
}

data class OfflineSettingsUiModel(
    val enabled: Boolean,
    val quotaBytes: Long,
    val usedBytes: Long,
    val downloadedTrackCount: Int,
    val syncStatus: OfflineSyncStatus,
    val isSyncing: Boolean,
    val error: String?,
) {
    companion object {
        fun fromStatus(status: OfflineDownloadStatus): OfflineSettingsUiModel =
            OfflineSettingsUiModel(
                enabled = status.isEnabled,
                quotaBytes = status.quotaBytes,
                usedBytes = status.usedBytes,
                downloadedTrackCount = status.downloadedTrackCount,
                syncStatus = OfflineSyncStatus.from(status),
                isSyncing = status.isSyncing,
                error = status.error,
            )
    }
}

@Composable
internal fun formatBytesLabel(bytes: Long): String {
    val mb = bytes / 1024L / 1024L
    return if (mb >= 1024L && mb % 1024L == 0L) {
        stringResource(R.string.settings_size_gb, mb / 1024L)
    } else {
        stringResource(R.string.settings_size_mb, mb)
    }
}

@Composable
internal fun usedLabel(bytes: Long): String =
    stringResource(R.string.settings_used_label, formatBytesLabel(bytes))

@Composable
internal fun quotaQuotaLabel(quotaBytes: Long): String =
    stringResource(R.string.settings_used_of_quota, formatBytesLabel(quotaBytes))

@Composable
internal fun downloadedCountLabel(count: Int): String =
    stringResource(R.string.settings_downloaded_count, count)

@Composable
internal fun syncStatusLabel(status: OfflineSyncStatus): String =
    stringResource(
        when (status) {
            OfflineSyncStatus.SYNCING -> R.string.settings_status_syncing
            OfflineSyncStatus.SYNCED -> R.string.settings_status_synced
            OfflineSyncStatus.WAITING -> R.string.settings_status_waiting
            OfflineSyncStatus.OFF -> R.string.settings_status_off
        },
    )

@HiltViewModel
class OfflineSettingsViewModel @Inject constructor(
    private val offlineRepository: OfflineRepository,
) : ViewModel() {
    val status: StateFlow<OfflineDownloadStatus> = offlineRepository.status

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { offlineRepository.setEnabled(enabled) }
    }

    fun setQuota(bytes: Long) {
        viewModelScope.launch { offlineRepository.setQuotaBytes(bytes) }
    }

    fun syncNow() {
        viewModelScope.launch { offlineRepository.syncFavorites() }
    }

    fun clearDownloads() {
        viewModelScope.launch { offlineRepository.clearDownloads() }
    }
}

@Composable
fun OfflineSettingsRoute(
    viewModel: OfflineSettingsViewModel = hiltViewModel(),
    compactMode: Boolean = false,
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val model = OfflineSettingsUiModel.fromStatus(status)
    var showClearConfirm by remember { mutableStateOf(false) }
    val onClearRequested: () -> Unit = { showClearConfirm = true }
    val onClearConfirmed: () -> Unit = {
        showClearConfirm = false
        viewModel.clearDownloads()
    }
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.offline_clear_confirm_title)) },
            text = { Text(stringResource(R.string.offline_clear_confirm_body)) },
            confirmButton = {
                TextButton(onClick = onClearConfirmed) {
                    Text(stringResource(R.string.offline_clear_confirm_action), color = Color(0xFFFF7A70))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (compactMode) {
        AutomotiveOfflineSettingsScreen(
            model = model,
            onEnabledChange = viewModel::setEnabled,
            onQuotaSelected = viewModel::setQuota,
            onSyncNow = viewModel::syncNow,
            onClear = onClearRequested,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF24201D),
                        Color(0xFF0F0F10),
                        Color(0xFF090909),
                    ),
                ),
            )
            // Edge-to-edge is on, so reserve the status bar height before the headline.
            .statusBarsPadding()
            .padding(horizontal = 18.dp)
            .padding(top = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            color = Color.White,
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
        )
        Text(
            text = stringResource(R.string.settings_subtitle),
            color = Color(0xFFA9A29C),
            style = MaterialTheme.typography.bodyMedium,
        )
        OfflineListeningPanel(
            model = model,
            onEnabledChange = viewModel::setEnabled,
            onQuotaSelected = viewModel::setQuota,
            onSyncNow = viewModel::syncNow,
            onClear = onClearRequested,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "v${BuildConfig.VERSION_NAME}",
            color = Color(0xFF6F6760),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun AutomotiveOfflineSettingsScreen(
    model: OfflineSettingsUiModel,
    onEnabledChange: (Boolean) -> Unit,
    onQuotaSelected: (Long) -> Unit,
    onSyncNow: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF21150F),
                        Color(0xFF15110F),
                        Color(0xFF0B0B0C),
                    ),
                ),
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                color = Color.White,
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
            )
            Text(
                text = stringResource(R.string.settings_aaos_subtitle),
                color = Color(0xFFE5D2C8),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF121112),
            contentColor = Color.White,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_offline_listening),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        )
                        Text(
                            text = syncStatusLabel(model.syncStatus),
                            color = Color(0xFFE3D2C7),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Switch(
                        checked = model.enabled,
                        onCheckedChange = onEnabledChange,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_storage_limit),
                        color = Color(0xFFFFB08A),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    )
                    OfflineQuotaOptions.forEach { quota ->
                        CompactQuotaOption(
                            label = formatBytesLabel(quota),
                            selected = model.quotaBytes == quota,
                            enabled = model.enabled,
                            onClick = { onQuotaSelected(quota) },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "${usedLabel(model.usedBytes)} · ${downloadedCountLabel(model.downloadedTrackCount)}",
                        color = Color(0xFFE3D2C7),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    CompactActionButton(
                        label = stringResource(
                            if (model.isSyncing) R.string.settings_action_syncing else R.string.settings_action_sync_short,
                        ),
                        enabled = model.enabled && !model.isSyncing,
                        primary = true,
                        onClick = onSyncNow,
                    )
                    CompactActionButton(
                        label = stringResource(R.string.settings_action_clear_short),
                        enabled = true,
                        primary = false,
                        onClick = onClear,
                    )
                }

                model.error?.let {
                    Text(
                        text = it,
                        color = Color(0xFFFF7A70),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactQuotaOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Color(0xFFEBDDFF) else Color.Transparent)
            .border(1.dp, if (selected) Color(0xFFEBDDFF) else Color(0xFF756D7A), shape)
            .pressFeedback(enabled = enabled, pressedScale = 0.95f, label = "compactQuotaPress")
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = when {
                selected -> Color(0xFF21192A)
                enabled -> Color(0xFFE3D2C7)
                else -> Color(0xFF5F5863)
            },
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun CompactActionButton(
    label: String,
    enabled: Boolean,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                when {
                    !enabled -> Color(0xFF201A18)
                    primary -> hypeTokens.brand.primary
                    else -> Color.Transparent
                },
            )
            .border(
                1.dp,
                if (primary) Color.Transparent else Color(0xFF8D8492),
                shape,
            )
            .pressFeedback(enabled = enabled, pressedScale = 0.96f, label = "compactActionPress")
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            color = when {
                !enabled -> Color(0xFF544946)
                primary -> Color(0xFF19110E)
                else -> Color(0xFFFFB08A)
            },
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun OfflineListeningPanel(
    model: OfflineSettingsUiModel,
    onEnabledChange: (Boolean) -> Unit,
    onQuotaSelected: (Long) -> Unit,
    onSyncNow: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_offline_listening),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = stringResource(
                        if (model.enabled) R.string.settings_offline_listening_on else R.string.settings_offline_listening_off,
                    ),
                    color = Color(0xFFADA6A1),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Switch(
                checked = model.enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = hypeTokens.brand.primary,
                    uncheckedThumbColor = Color(0xFFE2DAD3),
                    uncheckedTrackColor = Color(0xFF3A3330),
                    uncheckedBorderColor = Color(0xFF4F4744),
                ),
            )
        }

        SettingsDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = stringResource(R.string.settings_storage_limit),
                color = Color(0xFFFF8F58),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = formatBytesLabel(model.quotaBytes),
                color = Color(0xFFE3D8D0),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        QuotaPillRow(
            selectedQuotaBytes = model.quotaBytes,
            enabled = model.enabled,
            onQuotaSelected = onQuotaSelected,
        )

        StorageUsageCard(model = model)

        model.error?.let {
            Text(
                text = it,
                color = Color(0xFFFF7A70),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SettingsDivider(modifier = Modifier.padding(top = 2.dp))
        SettingsActionRow(
            label = stringResource(
                if (model.isSyncing) R.string.settings_action_syncing else R.string.settings_action_sync_now,
            ),
            enabled = model.enabled && !model.isSyncing,
            icon = Icons.Default.Sync,
            onClick = onSyncNow,
        )
        SettingsDivider()
        SettingsActionRow(
            label = stringResource(R.string.settings_action_clear),
            enabled = true,
            icon = Icons.Default.DeleteOutline,
            onClick = onClear,
        )
    }
}

@Composable
private fun QuotaPillRow(
    selectedQuotaBytes: Long,
    enabled: Boolean,
    onQuotaSelected: (Long) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OfflineQuotaOptions.forEach { quota ->
            StorageLimitPill(
                label = formatBytesLabel(quota),
                selected = selectedQuotaBytes == quota,
                enabled = enabled,
                onClick = { onQuotaSelected(quota) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StorageLimitPill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                when {
                    selected -> Color(0x1AFF8A47)
                    else -> Color.Transparent
                },
            )
            .border(
                width = 1.dp,
                color = when {
                    selected -> hypeTokens.brand.primary
                    enabled -> Color(0xFF4E4742)
                    else -> Color(0xFF2E2926)
                },
                shape = shape,
            )
            .pressFeedback(enabled = enabled, pressedScale = 0.96f, label = "quotaPillPress")
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                selected -> Color(0xFFFFA06A)
                enabled -> Color(0xFFD8D0CA)
                else -> Color(0xFF665E58)
            },
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}

@Composable
private fun StorageUsageCard(model: OfflineSettingsUiModel) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xCC1B1918),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsRoundIcon(icon = Icons.Default.GraphicEq)
                Text(
                    text = usedLabel(model.usedBytes),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = quotaQuotaLabel(model.quotaBytes),
                    color = Color(0xFFB9B1AB),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsRoundIcon(icon = Icons.Default.FavoriteBorder)
                Text(
                    text = downloadedCountLabel(model.downloadedTrackCount),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun SettingsRoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(Color(0xFF3A2A22)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFFFF9B62),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun SettingsActionRow(
    label: String,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .pressFeedback(enabled = enabled, pressedScale = 0.985f, label = "settingsActionRowPress")
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) hypeTokens.brand.primary else Color(0xFF524943),
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = label,
            color = if (enabled) Color.White else Color(0xFF5A514D),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = if (enabled) Color(0xFFC9C0BA) else Color(0xFF524943),
            modifier = Modifier.size(30.dp),
        )
    }
}

@Composable
private fun SettingsDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0xFF2D2A28)),
    )
}
