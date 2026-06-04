package com.blazepush.simulator.ui
// @IgnoreFormatCheck

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blazepush.simulator.data.TestScenario
import com.blazepush.simulator.viewmodel.SimulatorViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulatorScreen(
    viewModel: SimulatorViewModel = viewModel(),
    onRequestPermission: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // 权限检查
    LaunchedEffect(Unit) {
        viewModel.checkPermissions(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GPS模拟器") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 权限状态
            PermissionStatusCard(
                hasPermissions = uiState.hasPermissions,
                onRequestPermission = onRequestPermission
            )

            // 蓝牙状态
            BluetoothStatusCard(uiState.isBluetoothEnabled)

            // 控制按钮
            ControlCard(
                isAdvertising = uiState.isAdvertising,
                isServerReady = uiState.isServerReady,
                connectedDevices = uiState.connectedDevices.size,
                activeDeviceCount = uiState.connectedDevices.size, // 使用活跃设备数
                onStartAdvertising = {
                    if (uiState.hasPermissions) {
                        viewModel.startAdvertising(context)
                    } else {
                        Toast.makeText(context, "缺少必要权限", Toast.LENGTH_SHORT).show()
                    }
                },
                onStopAdvertising = { viewModel.stopAdvertising() }
            )

            // 场景选择
            ScenarioCard(
                currentScenario = uiState.currentScenario,
                onScenarioChange = {
                    viewModel.disableReplayMode()
                    viewModel.setScenario(it)
                }
            )

            ReplayEntryCard(
                isReplayMode = uiState.isReplayMode,
                isAdvertising = uiState.isAdvertising,
                isReplayPlaying = uiState.isReplayPlaying,
                onEnableReplay = { viewModel.enableReplayMode() },
                onTriggerReplayOnce = { viewModel.triggerReplayOnce() },
                onTriggerRealRoadReplay = { viewModel.triggerRealRoadReplayOnce() },
                onTriggerRealRoadSuccessReplay = { viewModel.triggerRealRoadSuccessReplayOnce() },
            )

            // 速度控制
            SpeedControlCard(
                mode = uiState.speedMode,
                targetSpeed = uiState.targetSpeed,
                acceleration = uiState.speedAcceleration,
                currentSpeed = uiState.currentSpeed,
                status = uiState.speedStatus,
                onModeChange = { viewModel.setSpeedMode(it) },
                onTargetSpeedChange = { viewModel.setTargetSpeed(it) },
                onAccelerationChange = { viewModel.setSpeedAcceleration(it) }
            )

            // 参数设置
            ParametersCard(
                frequency = uiState.frequency,
                satellites = uiState.satellites,
                initialSpeed = uiState.initialSpeed,
                onFrequencyChange = { viewModel.setFrequency(it) },
                onSatellitesChange = { viewModel.setSatellites(it) },
                onInitialSpeedChange = { viewModel.setInitialSpeed(it) }
            )

            // 数据预览
            DataPreviewCard(
                dataSourceLabel = uiState.dataSourceLabel,
                currentSpeed = uiState.currentSpeed,
                currentLatitude = uiState.currentLatitude,
                currentLongitude = uiState.currentLongitude
            )
        }
    }
}

@Composable
fun PermissionStatusCard(
    hasPermissions: Boolean,
    onRequestPermission: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (hasPermissions) Color(0xFF4CAF50) else Color(0xFFF44336)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (hasPermissions) "✓ 权限已授予" else "✗ 缺少权限",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            if (!hasPermissions) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "GPS模拟器需要以下权限：",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "• 蓝牙连接 (BLE广播)\n• 蓝牙扫描\n• 位置权限 (系统要求)",
                        color = Color.White,
                        fontSize = 11.sp
                    )

                    Button(
                        onClick = onRequestPermission,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFFF44336)
                        )
                    ) {
                        Text("请求权限", fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun BluetoothStatusCard(isEnabled: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) Color(0xFF2196F3) else Color(0xFF9E9E9E)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isEnabled) "蓝牙已开启" else "蓝牙未开启",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
fun ControlCard(
    isAdvertising: Boolean,
    isServerReady: Boolean,
    connectedDevices: Int,
    activeDeviceCount: Int = connectedDevices,
    onStartAdvertising: () -> Unit,
    onStopAdvertising: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "BLE控制",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStartAdvertising,
                    enabled = !isAdvertising,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("开始广播")
                }
                Button(
                    onClick = onStopAdvertising,
                    enabled = isAdvertising,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF44336)
                    )
                ) {
                    Text("停止广播")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "状态: ${
                    when {
                        !isAdvertising -> "未广播"
                        !isServerReady -> "服务器初始化中..."
                        else -> "广播中 | 已连接: $connectedDevices 台设备 | 活跃: $activeDeviceCount 台"
                    }
                }",
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    !isServerReady -> Color(0xFFFF9800)
                    connectedDevices > 0 && connectedDevices == activeDeviceCount -> Color(0xFF4CAF50)
                    connectedDevices > 0 && connectedDevices != activeDeviceCount -> Color(0xFFFF9800)
                    else -> Color(0xFF9E9E9E)
                }
            )
        }
    }
}

@Composable
fun ScenarioCard(
    currentScenario: TestScenario,
    onScenarioChange: (TestScenario) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "测试场景",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            TestScenario.values().forEach { scenario ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = currentScenario == scenario,
                        onClick = { onScenarioChange(scenario) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(scenario.displayName)
                }
            }
        }
    }
}

@Composable
fun ReplayEntryCard(
    isReplayMode: Boolean,
    isAdvertising: Boolean,
    isReplayPlaying: Boolean,
    onEnableReplay: () -> Unit,
    onTriggerReplayOnce: () -> Unit,
    onTriggerRealRoadReplay: () -> Unit,
    onTriggerRealRoadSuccessReplay: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "圈速模拟",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when {
                    !isReplayMode -> "使用现有 replay 链路输出圈速模拟数据"
                    !isAdvertising -> "已启用圈速回放数据源 · 请先开始 BLE 广播"
                    isReplayPlaying -> "正在单次播放 replay frames…"
                    else -> "已启用 · 默认 idle，点 PLAY ONCE 单次播放一段"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onEnableReplay,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isReplayMode
            ) {
                Text(if (isReplayMode) "已启用圈速模拟回放" else "启用圈速模拟回放")
            }
            if (isReplayMode) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onTriggerReplayOnce,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isAdvertising && !isReplayPlaying,
                ) {
                    Text(if (isReplayPlaying) "PLAYING…" else "PLAY ONCE")
                }
                // 真实路测 0-100 回放(2026-06-05):2026-06-03 23:11 真车数据(25Hz/53s,
                // 蠕动 3 次起步+全力到 99)——0-100 桌面验证用真实形态,不再手拖滑块
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onTriggerRealRoadReplay,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isAdvertising && !isReplayPlaying,
                ) {
                    Text(if (isReplayPlaying) "PLAYING…" else "真实0-100路测回放(25Hz)")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onTriggerRealRoadSuccessReplay,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isAdvertising && !isReplayPlaying,
                ) {
                    Text(if (isReplayPlaying) "PLAYING…" else "真实0-100·过百版(预期≈7.95s)")
                }
            }
        }
    }
}

@Composable
fun ParametersCard(
    frequency: Int,
    satellites: Int,
    initialSpeed: Float,
    onFrequencyChange: (Int) -> Unit,
    onSatellitesChange: (Int) -> Unit,
    onInitialSpeedChange: (Float) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "参数设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 频率
            Text("发送频率: $frequency Hz")
            Slider(
                value = frequency.toFloat(),
                onValueChange = { onFrequencyChange(it.toInt()) },
                valueRange = 1f..25f,
                steps = 23
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 卫星数
            Text("卫星数量: $satellites")
            Slider(
                value = satellites.toFloat(),
                onValueChange = { onSatellitesChange(it.toInt()) },
                valueRange = 4f..20f,
                steps = 15
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 初始速度
            Text("初始速度: ${initialSpeed.toInt()} km/h")
            Slider(
                value = initialSpeed,
                onValueChange = { onInitialSpeedChange(it) },
                valueRange = 0f..100f,
                steps = 20
            )
        }
    }
}

@Composable
fun DataPreviewCard(
    dataSourceLabel: String,
    currentSpeed: Float,
    currentLatitude: Double,
    currentLongitude: Double
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "数据预览",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text("数据源: $dataSourceLabel")
            Text("速度: ${"%.1f".format(currentSpeed)} km/h")
            Text("纬度: ${"%.7f".format(currentLatitude)}")
            Text("经度: ${"%.7f".format(currentLongitude)}")
        }
    }
}
