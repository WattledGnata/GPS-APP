package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blazepush.core.bluetooth.ScannedDevice
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.feature.test.R
import com.blazepush.feature.test.viewmodel.GpsDataViewModel

enum class ScanSheetState { Scanning, Found, Empty, Connecting, Failed }

private enum class DeviceLabel(val color: Color) {
    Recommended(TrackTechColors.Purple),
    External(TrackTechColors.Cyan),
    Unknown(TrackTechColors.TextSecondary),
}

private fun classifyDevice(name: String): DeviceLabel = when {
    name.contains("RaceChrono", ignoreCase = true) -> DeviceLabel.Recommended
    name.contains("GPS", ignoreCase = true) -> DeviceLabel.External
    else -> DeviceLabel.Unknown
}

internal fun rssiToBars(rssi: Int): Int = when {
    rssi >= -50 -> 4
    rssi >= -65 -> 3
    rssi >= -80 -> 2
    else -> 1
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleScanBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    gpsViewModel: GpsDataViewModel,
    onScanAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val isScanning by gpsViewModel.isScanning.collectAsState()
    val scanResults by gpsViewModel.scanResults.collectAsState()
    val connectionState by gpsViewModel.connectionState.collectAsState()
    // ble-device-memory（design Decision 7）：join 已保存设备——别名优先显示 + Last connected 标识
    val savedDevices by gpsViewModel.savedDevices.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val savedByAddress = remember(savedDevices) { savedDevices.associateBy { it.address } }
    val lastConnectedAddress = remember(savedDevices) {
        savedDevices.filter { it.lastConnectedAtMs != null }
            .maxByOrNull { it.lastConnectedAtMs!! }
            ?.address
    }

    var selectedDevice by remember { mutableStateOf<ScannedDevice?>(null) }
    var attemptedConnectAddress by remember { mutableStateOf<String?>(null) }
    var hasScannedOnce by remember { mutableStateOf(false) }

    if (isScanning) hasScannedOnce = true

    val state: ScanSheetState = remember(
        isScanning, scanResults, connectionState, attemptedConnectAddress, hasScannedOnce,
    ) {
        when {
            connectionState == ConnectionState.CONNECTING -> ScanSheetState.Connecting
            attemptedConnectAddress != null && connectionState == ConnectionState.DISCONNECTED ->
                ScanSheetState.Failed
            isScanning && scanResults.isEmpty() -> ScanSheetState.Scanning
            scanResults.isNotEmpty() -> ScanSheetState.Found
            !isScanning && scanResults.isEmpty() && hasScannedOnce -> ScanSheetState.Empty
            else -> ScanSheetState.Scanning
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            gpsViewModel.stopScan()
            onDismiss()
        },
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
                    text = stringResource(R.string.scan_devices_title),
                    style = TrackTechTypography.RacingTitleMedium,
                    color = TrackTechColors.TextPrimary,
                )
                IconButton(onClick = {
                    gpsViewModel.stopScan()
                    onDismiss()
                }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = TrackTechColors.TextSecondary,
                    )
                }
            }

            Subtitle(state = state, foundCount = scanResults.size, connectingTo = selectedDevice?.name)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
            ) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(scanResults) { device ->
                        DeviceRow(
                            device = device,
                            selected = selectedDevice?.address == device.address,
                            alias = savedByAddress[device.address]?.alias?.takeIf { it.isNotBlank() },
                            isLastConnected = device.address == lastConnectedAddress,
                            onClick = {
                                selectedDevice = device
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrimaryActionPanel(
                    title = if (state == ScanSheetState.Failed) {
                        stringResource(R.string.action_retry)
                    } else {
                        stringResource(R.string.action_connect)
                    },
                    subtitle = selectedDevice?.name ?: stringResource(R.string.action_select_device),
                    enabled = selectedDevice != null && connectionState != ConnectionState.CONNECTING,
                    onClick = {
                        selectedDevice?.let {
                            attemptedConnectAddress = it.address
                            gpsViewModel.connectDevice(it)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isScanning, onClick = onScanAgain)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.BluetoothSearching,
                    contentDescription = null,
                    tint = if (isScanning) TrackTechColors.TextMuted else TrackTechColors.Purple,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.action_scan_again),
                    style = TrackTechTypography.UiTextLabel,
                    color = if (isScanning) TrackTechColors.TextMuted else TrackTechColors.Purple,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = TrackTechColors.TextMuted,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.scan_devices_hint),
                    style = TrackTechTypography.UiTextSmall,
                    color = TrackTechColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun Subtitle(state: ScanSheetState, foundCount: Int, connectingTo: String?) {
    val (text, accent) = when (state) {
        ScanSheetState.Scanning -> stringResource(R.string.scan_searching) to TrackTechColors.Cyan
        ScanSheetState.Found -> stringResource(R.string.scan_found, foundCount) to TrackTechColors.Cyan
        ScanSheetState.Empty -> stringResource(R.string.scan_empty) to TrackTechColors.TextSecondary
        ScanSheetState.Connecting -> stringResource(
            R.string.scan_connecting,
            connectingTo ?: stringResource(R.string.scan_device_fallback),
        ) to TrackTechColors.Cyan
        ScanSheetState.Failed -> stringResource(R.string.scan_connection_failed) to TrackTechColors.Red
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state == ScanSheetState.Scanning || state == ScanSheetState.Connecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = accent,
                strokeWidth = 1.5.dp,
            )
            Spacer(Modifier.width(8.dp))
        } else if (state == ScanSheetState.Empty) {
            Icon(
                imageVector = Icons.Filled.BluetoothDisabled,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text = text, style = TrackTechTypography.UiTextSmall, color = accent)
    }
}

@Composable
private fun DeviceRow(
    device: ScannedDevice,
    selected: Boolean,
    onClick: () -> Unit,
    // ble-device-memory（design Decision 7 + UI 交互细化 §2）
    alias: String? = null,
    isLastConnected: Boolean = false,
) {
    val label = classifyDevice(device.name)
    val borderColor = if (selected) TrackTechColors.Purple else TrackTechColors.BorderAlpha60
    val shape = CutCornerPanelShape(cutSize = 8.dp, cutCorners = cutCornersAll)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(TrackTechColors.SurfaceDark, shape)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                SelectedRadio(selected = selected)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = alias ?: device.name,
                        style = TrackTechTypography.UiTextLabel,
                        color = TrackTechColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(2.dp))
                    // UI 交互细化 §2：Last connected（绿）与既有分类标签 " · " 拼接并列
                    Row {
                        if (isLastConnected) {
                            Text(
                                text = stringResource(R.string.scan_last_connected),
                                style = TrackTechTypography.UiTextSmall,
                                color = TrackTechColors.Green,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = when (label) {
                                DeviceLabel.Recommended -> stringResource(R.string.device_recommended)
                                DeviceLabel.External -> stringResource(R.string.device_external_gps)
                                DeviceLabel.Unknown -> stringResource(R.string.device_unknown)
                            },
                            style = TrackTechTypography.UiTextSmall,
                            color = label.color,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${device.rssi} dBm",
                    style = TrackTechTypography.UiTextSmall,
                    color = TrackTechColors.TextSecondary,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                SignalBars(bars = rssiToBars(device.rssi))
            }
        }
    }
}

@Composable
private fun SelectedRadio(selected: Boolean) {
    if (selected) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(TrackTechColors.Purple, shape = androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(R.string.action_selected),
                tint = TrackTechColors.TextPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(
                    width = 1.dp,
                    color = TrackTechColors.Border,
                    shape = androidx.compose.foundation.shape.CircleShape,
                ),
        )
    }
}

@Composable
private fun SignalBars(bars: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        repeat(4) { idx ->
            val on = idx < bars
            val barHeight = ((idx + 1) * 3 + 3).dp
            Box(
                modifier = Modifier
                    .padding(start = if (idx == 0) 0.dp else 1.5.dp)
                    .size(width = 3.dp, height = barHeight)
                    .background(if (on) TrackTechColors.Green else TrackTechColors.Border),
            )
        }
    }
}
