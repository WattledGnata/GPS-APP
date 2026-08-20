// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blazepush.core.data.model.BluetoothDeviceModel
import com.blazepush.core.data.model.displayName
import com.blazepush.feature.test.R
import com.blazepush.feature.test.viewmodel.GpsDataViewModel

/**
 * ble-device-memory round（design Decision 5 + UI 交互细化 §3/§4）：已保存设备管理 sheet。
 * 列表行：displayName（连接中行加绿点 Connected）/ address · 相对时间 / 行尾 ✏（改名）🗑（删除）。
 * 改名 = AlertDialog + OutlinedTextField 预填，清空保存 = 还原固件名；
 * 删除 = AlertDialog 二次确认（user 2026-06-06 拍板），不断开当前连接。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedDevicesSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    gpsViewModel: GpsDataViewModel,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val savedDevices by gpsViewModel.savedDevices.collectAsState()
    val connectedAddress by gpsViewModel.connectedDeviceAddress.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var renameTarget by remember { mutableStateOf<BluetoothDeviceModel?>(null) }
    var deleteTarget by remember { mutableStateOf<BluetoothDeviceModel?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = TrackTechColors.Surface,
        contentColor = TrackTechColors.TextPrimary,
        shape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.saved_devices_title),
                    style = TrackTechTypography.RacingTitleMedium,
                    color = TrackTechColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = TrackTechColors.TextSecondary,
                    )
                }
            }

            Text(
                text = if (savedDevices.isEmpty()) {
                    stringResource(R.string.saved_devices_empty_detail)
                } else {
                    stringResource(R.string.device_saved_count, savedDevices.size)
                },
                style = TrackTechTypography.UiTextSmall,
                color = TrackTechColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
            ) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(savedDevices, key = { it.address }) { device ->
                        SavedDeviceRow(
                            device = device,
                            isConnected = device.address == connectedAddress,
                            onRename = { renameTarget = device },
                            onDelete = { deleteTarget = device },
                        )
                    }
                }
            }

            Spacer(Modifier.size(8.dp))
        }
    }

    renameTarget?.let { target ->
        RenameAliasDialog(
            device = target,
            onConfirm = { newAlias ->
                gpsViewModel.setAlias(target.address, newAlias)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.confirm_delete_device)) },
            text = {
                Text(
                    stringResource(R.string.confirm_delete_device_message, target.displayName)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    gpsViewModel.deleteSavedDevice(target.address)
                    deleteTarget = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun SavedDeviceRow(
    device: BluetoothDeviceModel,
    isConnected: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = CutCornerPanelShape(cutSize = 8.dp, cutCorners = cutCornersAll)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(TrackTechColors.SurfaceDark, shape)
            .border(1.dp, TrackTechColors.BorderAlpha60, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.displayName,
                        style = TrackTechTypography.UiTextLabel,
                        color = TrackTechColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isConnected) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(TrackTechColors.Green, CircleShape),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.state_connected),
                            style = TrackTechTypography.UiTextSmall,
                            color = TrackTechColors.Green,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.size(2.dp))
                Text(
                    text = "${device.address} · ${formatLastConnected(device.lastConnectedAtMs)}",
                    style = TrackTechTypography.UiTextSmall,
                    color = TrackTechColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onRename) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.action_rename),
                    tint = TrackTechColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = TrackTechColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun RenameAliasDialog(
    device: BluetoothDeviceModel,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember(device.address) { mutableStateOf(device.alias.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_alias_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.device_alias_message, device.name ?: device.address),
                    style = TrackTechTypography.UiTextSmall,
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    placeholder = { Text(device.name ?: device.address) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(input.takeIf { it.isNotBlank() }) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * 最近连接相对时间（UI 交互细化 §3）：<1min "Just now" / <1h "Xm ago" / <24h "Xh ago" / 其他 "MMM d"。
 * lastConnectedAtMs 为 null（migration 历史行）显示 "—"。
 */
@Composable
internal fun formatLastConnected(lastConnectedAtMs: Long?, nowMs: Long = System.currentTimeMillis()): String {
    if (lastConnectedAtMs == null) return "—"
    val deltaMs = nowMs - lastConnectedAtMs
    return when {
        deltaMs < 60_000L -> stringResource(R.string.time_just_now)
        deltaMs < 3_600_000L -> stringResource(R.string.time_minutes_ago, deltaMs / 60_000L)
        deltaMs < 86_400_000L -> stringResource(R.string.time_hours_ago, deltaMs / 3_600_000L)
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            .format(java.util.Date(lastConnectedAtMs))
    }
}
