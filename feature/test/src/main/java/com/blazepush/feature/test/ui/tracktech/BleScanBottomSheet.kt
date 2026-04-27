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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.blazepush.core.bluetooth.ScannedDevice
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.feature.test.viewmodel.GpsDataViewModel

enum class ScanSheetState { Scanning, Found, Empty, Connecting, Failed }

private enum class DeviceLabel(val label: String, val color: Color) {
    Recommended("Recommended", TrackTechColors.Purple),
    External("External GPS", TrackTechColors.Cyan),
    Unsupported("Unsupported", TrackTechColors.TextMuted),
}

private fun classifyDevice(name: String): DeviceLabel = when {
    name.contains("RaceChrono", ignoreCase = true) -> DeviceLabel.Recommended
    name.contains("GPS", ignoreCase = true) -> DeviceLabel.External
    else -> DeviceLabel.Unsupported
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
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val isScanning by gpsViewModel.isScanning.collectAsState()
    val scanResults by gpsViewModel.scanResults.collectAsState()
    val connectionState by gpsViewModel.connectionState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                    text = "SCAN DEVICES",
                    style = TrackTechTypography.RacingTitleMedium,
                    color = TrackTechColors.TextPrimary,
                )
                IconButton(onClick = {
                    gpsViewModel.stopScan()
                    onDismiss()
                }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
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
                    title = if (state == ScanSheetState.Failed) "RETRY" else "CONNECT",
                    subtitle = if (selectedDevice != null) selectedDevice!!.name else "SELECT A DEVICE",
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
                    .clickable(enabled = !isScanning) { gpsViewModel.startScan() }
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
                    text = "SCAN AGAIN",
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
                    text = "Choose a BLE GPS receiver for tests",
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
        ScanSheetState.Scanning -> "Searching nearby GPS receivers" to TrackTechColors.Cyan
        ScanSheetState.Found -> "Searching nearby GPS receivers · $foundCount found" to TrackTechColors.Cyan
        ScanSheetState.Empty -> "No devices found" to TrackTechColors.TextSecondary
        ScanSheetState.Connecting -> "Connecting to ${connectingTo ?: "device"}..." to TrackTechColors.Cyan
        ScanSheetState.Failed -> "Connection failed · Tap a device to retry" to TrackTechColors.Red
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
) {
    val label = classifyDevice(device.name)
    val unsupported = label == DeviceLabel.Unsupported
    val borderColor = if (selected) TrackTechColors.Purple else TrackTechColors.BorderAlpha60
    val shape = CutCornerPanelShape(cutSize = 8.dp, cutCorners = cutCornersAll)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(TrackTechColors.SurfaceDark, shape)
            .border(1.dp, borderColor, shape)
            .alpha(if (unsupported) 0.55f else 1f)
            .clickable(enabled = !unsupported, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectedRadio(selected = selected)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = device.name,
                        style = TrackTechTypography.UiTextLabel,
                        color = TrackTechColors.TextPrimary,
                    )
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = label.label,
                        style = TrackTechTypography.UiTextSmall,
                        color = label.color,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${device.rssi} dBm",
                    style = TrackTechTypography.UiTextSmall,
                    color = TrackTechColors.TextSecondary,
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
                contentDescription = "Selected",
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
