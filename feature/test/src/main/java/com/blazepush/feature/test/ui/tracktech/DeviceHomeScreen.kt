package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BatteryUnknown
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.blazepush.core.data.model.displayName
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.BatteryCapabilityState
import com.blazepush.core.domain.model.QualityLevel
import com.blazepush.core.domain.permission.PermissionRequestOutcome
import com.blazepush.core.domain.permission.RequiredBluetoothPermissions
import com.blazepush.feature.test.datastore.UserProfileRepository
import com.blazepush.feature.test.R
import com.blazepush.feature.test.viewmodel.GpsDataViewModel
import com.blazepush.feature.test.viewmodel.TestSessionViewModel
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

private data class HeroState(
    val title: String,
    val subtitle: String,
    val statusLine: String,
    val accent: Color,
)

@Composable
fun DeviceHomeScreen(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") onTabSelected: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    pendingShowScanSheet: Boolean = false,
    onPendingShowScanSheetConsumed: () -> Unit = {},
    sessionViewModel: TestSessionViewModel,
) {
    val gpsViewModel = koinInject<GpsDataViewModel>()
    val gpsData by gpsViewModel.gpsData.collectAsState()
    val connectionState by gpsViewModel.connectionState.collectAsState()
    val dataQuality by gpsViewModel.dataQuality.collectAsState()
    val connectedDeviceName by gpsViewModel.connectedDeviceName.collectAsState()
    val batteryCapability by gpsViewModel.batteryCapability.collectAsState()
    val lapGpsReadiness by sessionViewModel.lapGpsReadiness.collectAsState()
    // ble-device-memory：已保存设备（入口 subtitle + 管理 sheet）
    val savedDevices by gpsViewModel.savedDevices.collectAsState()

    var showSheet by remember { mutableStateOf(false) }
    var showSavedDevicesSheet by remember { mutableStateOf(false) }
    var showBluetoothSettingsDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val activity = context.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    val requiredBluetoothPermissions = remember {
        RequiredBluetoothPermissions.forSdk(Build.VERSION.SDK_INT)
    }
    var pendingScanAfterPermission by remember { mutableStateOf(false) }
    var pendingPermissionRequest by remember { mutableStateOf(emptyList<String>()) }

    fun hasAllBluetoothPermissions(): Boolean = requiredBluetoothPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun startScanNow() {
        pendingScanAfterPermission = false
        showSheet = true
        gpsViewModel.startScan()
    }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        when (val outcome = PermissionRequestOutcome.from(pendingPermissionRequest, result)) {
            PermissionRequestOutcome.AllGranted -> startScanNow()
            is PermissionRequestOutcome.MissingPermissions -> {
                val permanentlyDenied = activity != null && outcome.permissions.any {
                    !activity.shouldShowRequestPermissionRationale(it)
                }
                if (permanentlyDenied) {
                    showBluetoothSettingsDialog = true
                } else {
                    pendingScanAfterPermission = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.permission_bluetooth_scan_message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    val requestScan: () -> Unit = {
        val missingPermissions = requiredBluetoothPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) {
            startScanNow()
        } else {
            pendingScanAfterPermission = true
            pendingPermissionRequest = missingPermissions
            bluetoothPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    // 从系统设置返回时复检；若用户已补齐权限，直接完成刚才被拦住的扫描动作。
    DisposableEffect(lifecycleOwner, pendingScanAfterPermission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                pendingScanAfterPermission &&
                hasAllBluetoothPermissions()
            ) {
                showBluetoothSettingsDialog = false
                startScanNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 首开车手名引导（first-launch-driver-prompt capability）：仅首次启动弹一次。
    // 弹出即置 flag（不论用户选哪个 / 是否真设名），保证只弹一次。
    val userProfileRepository = koinInject<UserProfileRepository>()
    var showDriverPrompt by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!userProfileRepository.hasShownDriverNamePrompt.first()) {
            showDriverPrompt = true
            userProfileRepository.setDriverNamePromptShown()
        }
    }
    if (showDriverPrompt) {
        AlertDialog(
            onDismissRequest = { showDriverPrompt = false },
            title = { Text("设个车手名？") },
            text = { Text("livetiming 榜单会用你的车手名展示成绩。要不要现在设一个？") },
            confirmButton = {
                TextButton(onClick = {
                    showDriverPrompt = false
                    navController.navigate("settings")
                }) { Text("去设置") }
            },
            dismissButton = {
                TextButton(onClick = { showDriverPrompt = false }) { Text("以后再说") }
            },
        )
    }

    // Pager 架构下 Device page 未组合时 SharedFlow(replay=0) 事件会丢失，
    // 改由 Shell 持有 pending state，本页组合后消费并 reset。
    LaunchedEffect(pendingShowScanSheet) {
        if (pendingShowScanSheet) {
            requestScan()
            onPendingShowScanSheetConsumed()
        }
    }

    val qualityLabel = when (dataQuality.overall) {
        QualityLevel.EXCELLENT, QualityLevel.GOOD -> stringResource(R.string.quality_good_signal)
        QualityLevel.FAIR -> stringResource(R.string.quality_weak_signal)
        QualityLevel.POOR -> stringResource(R.string.quality_no_signal)
    }
    val frequencyHz = gpsData.frequency.toInt().coerceAtLeast(0)
    // ble-device-memory（UI 交互细化 §5）：CONNECTING 且设备名尚空 = 冷启动自动连接中
    val isAutoConnecting = connectionState == ConnectionState.CONNECTING && connectedDeviceName == null
    val gpsPresentation = remember(lapGpsReadiness, connectionState, isAutoConnecting) {
        GpsReadinessPresentationMapper.present(
            readiness = lapGpsReadiness,
            connectionState = connectionState,
            isReconnecting = isAutoConnecting,
        )
    }
    val hero = HeroState(
        title = stringResource(gpsPresentation.titleRes),
        subtitle = stringResource(gpsPresentation.detailRes),
        statusLine = "${frequencyHz}Hz · $qualityLabel",
        accent = when (gpsPresentation.tone) {
            GpsReadinessTone.READY -> TrackTechSemantic.ReadyAccent
            GpsReadinessTone.CONNECTING -> TrackTechSemantic.ConnectingAccent
            GpsReadinessTone.WAITING -> TrackTechSemantic.PrimaryActionAccent
        },
    )

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
                text = stringResource(R.string.screen_device),
                style = TrackTechTypography.RacingTitleLarge,
                color = TrackTechColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.action_settings),
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
            connectedName = connectedDeviceName,
        )

        ConnectedDeviceCard(
            connectionState = connectionState,
            gpsPresentation = gpsPresentation,
            deviceName = connectedDeviceName ?: stringResource(R.string.label_no_device),
            onScanClick = requestScan,
            onDisconnectClick = {
                gpsViewModel.disconnect()
            },
            batteryCapability = batteryCapability,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TrackTechRow(
                leadingIcon = Icons.Filled.GpsFixed,
                title = stringResource(R.string.label_gps_details),
                subtitle = "$qualityLabel · ${gpsData.satelliteCount} sats · ${frequencyHz}Hz",
                onClick = { navController.navigate("gps_details") },
            )
            // ble-device-memory（UI 交互细化 §1）：已保存设备管理入口
            TrackTechRow(
                leadingIcon = Icons.Filled.Bluetooth,
                title = stringResource(R.string.label_saved_devices),
                subtitle = if (savedDevices.isEmpty()) {
                    "None yet"
                } else {
                    val latest = savedDevices.filter { it.lastConnectedAtMs != null }
                        .maxByOrNull { it.lastConnectedAtMs!! }
                    "${savedDevices.size} device${if (savedDevices.size > 1) "s" else ""}" +
                        (latest?.let { " · ${it.displayName}" } ?: "")
                },
                onClick = { showSavedDevicesSheet = true },
            )
            TrackTechRow(
                leadingIcon = Icons.Filled.Settings,
                title = "SETTINGS",
                subtitle = "车手显示名 · 更多设置",
                onClick = { navController.navigate("settings") },
            )
        }

        Spacer(Modifier.height(16.dp))
    }

    BleScanBottomSheet(
        visible = showSheet,
        onDismiss = { showSheet = false },
        gpsViewModel = gpsViewModel,
        onScanAgain = requestScan,
    )

    if (showBluetoothSettingsDialog) {
        AlertDialog(
            onDismissRequest = {
                showBluetoothSettingsDialog = false
                pendingScanAfterPermission = false
            },
            title = { Text(stringResource(R.string.permission_bluetooth_required_title)) },
            text = { Text(stringResource(R.string.permission_bluetooth_settings_message)) },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            },
                        )
                    }.onFailure {
                        pendingScanAfterPermission = false
                        Toast.makeText(
                            context,
                            context.getString(R.string.permission_bluetooth_settings_failed),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }) { Text(stringResource(R.string.action_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBluetoothSettingsDialog = false
                    pendingScanAfterPermission = false
                }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // ble-device-memory（design Decision 5）：已保存设备管理 sheet
    SavedDevicesSheet(
        visible = showSavedDevicesSheet,
        onDismiss = { showSavedDevicesSheet = false },
        gpsViewModel = gpsViewModel,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = hero.subtitle,
                style = TrackTechTypography.UiTextSmall,
                color = TrackTechColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = hero.statusLine,
                style = TrackTechTypography.UiTextSmall,
                color = TrackTechColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                ConnectionState.CONNECTED -> connectedName ?: stringResource(R.string.label_ble_connected)
                ConnectionState.CONNECTING -> stringResource(R.string.label_ble_connecting)
                else -> stringResource(R.string.label_ble_idle)
            },
            accentColor = TrackTechColors.Cyan,
            valueSize = MetricSize.Medium,
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            label = stringResource(R.string.label_satellites),
            value = satelliteCount.toString(),
            unit = null,
            status = if (satelliteCount >= 6) {
                stringResource(R.string.speed_status_ready)
            } else {
                stringResource(R.string.label_low)
            },
            accentColor = TrackTechColors.Cyan,
            valueSize = MetricSize.Medium,
            valueKind = MetricKind.Mechanical,
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            label = stringResource(R.string.label_rate),
            value = frequencyHz.toString(),
            unit = "Hz",
            status = if (frequencyHz >= 10) {
                stringResource(R.string.label_good)
            } else {
                stringResource(R.string.label_slow)
            },
            accentColor = TrackTechColors.Cyan,
            valueSize = MetricSize.Medium,
            valueKind = MetricKind.Mechanical,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ConnectedDeviceCard(
    connectionState: ConnectionState,
    gpsPresentation: GpsReadinessPresentation,
    deviceName: String,
    onScanClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    batteryCapability: BatteryCapabilityState = BatteryCapabilityState.Pending,
) {
    val isConnected = connectionState == ConnectionState.CONNECTED
    val isReady = gpsPresentation.tone == GpsReadinessTone.READY
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
                text = stringResource(R.string.label_connected_device),
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Purple,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (isConnected) deviceName else stringResource(R.string.label_not_connected),
                style = TrackTechTypography.RacingTitleMedium,
                color = TrackTechColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (isReady) TrackTechColors.Green else TrackTechColors.TextMuted),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(gpsPresentation.shortLabelRes),
                    style = TrackTechTypography.UiTextSmall,
                    color = TrackTechColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 电量指示器：仅已连接时显示
            if (isConnected || batteryCapability is BatteryCapabilityState.Available) {
                Spacer(Modifier.height(10.dp))
                BatteryIndicator(batteryCapability = batteryCapability)
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
                        text = stringResource(R.string.action_scan),
                        style = TrackTechTypography.UiTextLabel,
                        color = TrackTechColors.Purple,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                        text = stringResource(R.string.action_disconnect),
                        style = TrackTechTypography.UiTextLabel,
                        color = if (isConnected) TrackTechColors.Red else TrackTechColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 外接 GPS 设备电量指示器。
 * Pending / Available / Unsupported / Failed 四态绝不折叠。
 *
 * 图标映射：>=95=BatteryFull, >=80=Battery6Bar, >=60=Battery5Bar,
 *           >=40=Battery4Bar, >=20=Battery3Bar, >=10=Battery2Bar,
 *           >=1=Battery1Bar, ==0=BatteryAlert
 * 颜色：>20% 白色，<=20% TrackTechColors.Red，N/A 灰色
 */
@Composable
private fun BatteryIndicator(batteryCapability: BatteryCapabilityState) {
    val batteryPercent = (batteryCapability as? BatteryCapabilityState.Available)?.percent
    val (icon, tint) = when (batteryPercent) {
        null -> Icons.Filled.BatteryUnknown to TrackTechColors.TextMuted
        in 95..100 -> Icons.Filled.BatteryFull to TrackTechColors.TextPrimary
        in 80..94 -> Icons.Filled.Battery6Bar to TrackTechColors.TextPrimary
        in 60..79 -> Icons.Filled.Battery5Bar to TrackTechColors.TextPrimary
        in 40..59 -> Icons.Filled.Battery4Bar to TrackTechColors.TextPrimary
        in 20..39 -> Icons.Filled.Battery3Bar to TrackTechColors.TextPrimary
        in 10..19 -> Icons.Filled.Battery2Bar to TrackTechColors.Red
        in 1..9 -> Icons.Filled.Battery1Bar to TrackTechColors.Red
        0 -> Icons.Filled.BatteryAlert to TrackTechColors.Red
        else -> Icons.Filled.BatteryUnknown to TrackTechColors.TextMuted
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = when (batteryCapability) {
                BatteryCapabilityState.Pending -> "Battery pending"
                is BatteryCapabilityState.Available -> "Battery ${batteryCapability.percent}%"
                BatteryCapabilityState.Unsupported -> "Battery unsupported"
                BatteryCapabilityState.Failed -> "Battery failed"
            },
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        if (batteryPercent != null) {
            MetricNumber(
                value = batteryPercent.toString(),
                unit = "%",
                size = MetricSize.Small,
                kind = MetricKind.Mechanical,
                valueColor = tint,
            )
        } else {
            Text(
                text = batteryCapability.displayLabel(),
                style = TrackTechTypography.UiTextSmall,
                color = TrackTechColors.TextMuted,
            )
        }
    }
}
