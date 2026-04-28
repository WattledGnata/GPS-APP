package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.QualityLevel
import com.blazepush.feature.test.viewmodel.GpsDataViewModel
import org.koin.compose.koinInject

private data class HeroState(
    val title: String,
    val subtitle: String,
    val statusLine: String,
    val accent: Color,
)

private fun deriveHeroState(
    connectionState: ConnectionState,
    isTestReady: Boolean,
    frequencyHz: Int,
    qualityLabel: String,
): HeroState = when {
    connectionState == ConnectionState.CONNECTED && isTestReady -> HeroState(
        title = "READY TO TEST",
        subtitle = "GPS locked · BLE connected",
        statusLine = "${frequencyHz}Hz · Quality $qualityLabel",
        accent = TrackTechSemantic.ReadyAccent,
    )
    connectionState == ConnectionState.CONNECTED -> HeroState(
        title = "WAITING FOR GPS LOCK",
        subtitle = "BLE connected · acquiring GPS fix",
        statusLine = "${frequencyHz}Hz · Quality $qualityLabel",
        accent = TrackTechSemantic.ConnectingAccent,
    )
    connectionState == ConnectionState.CONNECTING -> HeroState(
        title = "CONNECTING…",
        subtitle = "Establishing BLE link",
        statusLine = "—",
        accent = TrackTechColors.TextSecondary,
    )
    else -> HeroState(
        title = "CONNECT GPS DEVICE",
        subtitle = "BLE disconnected · GPS no fix",
        statusLine = "Tap SCAN to find devices",
        accent = TrackTechSemantic.PrimaryActionAccent,
    )
}

@Composable
fun DeviceHomeScreen(
    @Suppress("UNUSED_PARAMETER") navController: NavController,
    modifier: Modifier = Modifier,
) {
    val gpsViewModel = koinInject<GpsDataViewModel>()
    val gpsData by gpsViewModel.gpsData.collectAsState()
    val connectionState by gpsViewModel.connectionState.collectAsState()
    val dataQuality by gpsViewModel.dataQuality.collectAsState()
    val context = LocalContext.current

    var showSheet by remember { mutableStateOf(false) }
    var lastConnectedName by remember { mutableStateOf<String?>(null) }

    // 监听 cross-tab gating 触发的自动展开 sheet 事件
    LaunchedEffect(Unit) {
        TrackTechEventBus.showScanSheetEvent.collect {
            showSheet = true
            gpsViewModel.startScan()
        }
    }

    val qualityLabel = when (dataQuality.overall) {
        QualityLevel.EXCELLENT -> "Excellent"
        QualityLevel.GOOD -> "Good"
        QualityLevel.FAIR -> "Fair"
        QualityLevel.POOR -> "Poor"
    }
    val frequencyHz = gpsData.frequency.toInt().coerceAtLeast(0)
    val hero = remember(connectionState, gpsData.isTestReady, frequencyHz, qualityLabel) {
        deriveHeroState(connectionState, gpsData.isTestReady, frequencyHz, qualityLabel)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TrackTechColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Device",
                style = TrackTechTypography.RacingTitleLarge,
                color = TrackTechColors.TextPrimary,
            )
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = TrackTechColors.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }

        ReadinessHero(hero = hero)

        QuickStatusRow(
            connectionState = connectionState,
            satelliteCount = gpsData.satelliteCount,
            frequencyHz = frequencyHz,
            qualityLabel = qualityLabel,
            connectedName = lastConnectedName,
        )

        ConnectedDeviceCard(
            connectionState = connectionState,
            isTestReady = gpsData.isTestReady,
            deviceName = lastConnectedName ?: "RaceChrono GPS",
            onScanClick = {
                showSheet = true
                gpsViewModel.startScan()
            },
            onDisconnectClick = {
                lastConnectedName = null
                gpsViewModel.disconnect()
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TrackTechRow(
                leadingIcon = Icons.Filled.GpsFixed,
                title = "GPS DETAILS",
                subtitle = "$qualityLabel · ${gpsData.satelliteCount} sats · ${frequencyHz}Hz",
                onClick = { navController.navigate("gps_details") },
            )
            TrackTechRow(
                leadingIcon = Icons.Filled.HealthAndSafety,
                title = "DIAGNOSTICS",
                subtitle = "Protocol OK",
                onClick = {
                    Toast.makeText(context, "Diagnostics · coming in next round", Toast.LENGTH_SHORT).show()
                },
            )
            TrackTechRow(
                leadingIcon = Icons.Filled.Settings,
                title = "SETTINGS",
                subtitle = "Units · Voice · Auto reconnect",
                onClick = {
                    Toast.makeText(context, "Settings · coming in next round", Toast.LENGTH_SHORT).show()
                },
            )
        }

        Spacer(Modifier.height(16.dp))
    }

    BleScanBottomSheet(
        visible = showSheet,
        onDismiss = { showSheet = false },
        gpsViewModel = gpsViewModel,
    )
}

@Composable
private fun ReadinessHero(hero: HeroState) {
    CutCornerPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        cutSize = 16.dp,
        cutCorners = cutCornersDiagonal,
        contentPadding = 20.dp,
        borderColor = hero.accent.copy(alpha = 0.6f),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(hero.accent),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = hero.title,
                    style = TrackTechTypography.RacingTitleMedium,
                    color = hero.accent,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = hero.subtitle,
                style = TrackTechTypography.UiTextSmall,
                color = TrackTechColors.TextSecondary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = hero.statusLine,
                style = TrackTechTypography.UiTextSmall,
                color = TrackTechColors.TextMuted,
            )
        }
    }
}

@Composable
private fun QuickStatusRow(
    connectionState: ConnectionState,
    satelliteCount: Int,
    frequencyHz: Int,
    @Suppress("UNUSED_PARAMETER") qualityLabel: String,
    connectedName: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetricTile(
            label = "BLE",
            value = when (connectionState) {
                ConnectionState.CONNECTED -> "ON"
                ConnectionState.CONNECTING -> "…"
                else -> "—"
            },
            unit = null,
            status = when (connectionState) {
                ConnectionState.CONNECTED -> connectedName ?: "Connected"
                ConnectionState.CONNECTING -> "Connecting"
                else -> "Idle"
            },
            accentColor = TrackTechColors.Cyan,
            valueSize = MetricSize.Medium,
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            label = "SATS",
            value = satelliteCount.toString(),
            unit = null,
            status = if (satelliteCount >= 6) "Ready" else "Low",
            accentColor = TrackTechColors.Cyan,
            valueSize = MetricSize.Medium,
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            label = "RATE",
            value = frequencyHz.toString(),
            unit = "Hz",
            status = if (frequencyHz >= 10) "Good" else "Slow",
            accentColor = TrackTechColors.Cyan,
            valueSize = MetricSize.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ConnectedDeviceCard(
    connectionState: ConnectionState,
    isTestReady: Boolean,
    deviceName: String,
    onScanClick: () -> Unit,
    onDisconnectClick: () -> Unit,
) {
    val isConnected = connectionState == ConnectionState.CONNECTED
    val statusText = when {
        isConnected && isTestReady -> "Ready for Test"
        isConnected -> "Waiting for GPS Lock"
        connectionState == ConnectionState.CONNECTING -> "Connecting…"
        else -> "Disconnected"
    }
    CutCornerPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        cutSize = 12.dp,
        cutCorners = cutCornersDiagonal,
        borderColor = TrackTechColors.Purple,
        contentPadding = 18.dp,
    ) {
        Column {
            Text(
                text = "CONNECTED DEVICE",
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Purple,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (isConnected) deviceName else "Not connected",
                style = TrackTechTypography.RacingTitleMedium,
                color = TrackTechColors.TextPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (isTestReady) TrackTechColors.Green else TrackTechColors.TextMuted),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusText,
                    style = TrackTechTypography.UiTextSmall,
                    color = TrackTechColors.TextSecondary,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clickable(onClick = onScanClick)
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.BluetoothSearching,
                        contentDescription = null,
                        tint = TrackTechColors.Purple,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "SCAN",
                        style = TrackTechTypography.UiTextLabel,
                        color = TrackTechColors.Purple,
                    )
                }
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(CutCornerPanelShape(cutSize = 8.dp, cutCorners = cutCornersDiagonal))
                        .border(
                            1.dp,
                            if (isConnected) TrackTechColors.Red else TrackTechColors.Border,
                            CutCornerPanelShape(cutSize = 8.dp, cutCorners = cutCornersDiagonal),
                        )
                        .clickable(enabled = isConnected, onClick = onDisconnectClick)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "DISCONNECT",
                        style = TrackTechTypography.UiTextLabel,
                        color = if (isConnected) TrackTechColors.Red else TrackTechColors.TextMuted,
                    )
                }
            }
        }
    }
}
