package com.blazepush.feature.test.ui.screen
// @IgnoreFormatCheck

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.GpsData
import com.blazepush.core.domain.model.QualityLevel
import com.blazepush.feature.test.ui.components.DataQualityCard
import com.blazepush.feature.test.viewmodel.GpsDataViewModel
import org.koin.androidx.compose.koinViewModel
import androidx.compose.runtime.LaunchedEffect

/**
 * 设备连接页面（transitional fallback）。
 *
 * Track Tech V2 后 [com.blazepush.feature.test.ui.tracktech.DeviceHomeScreen] 是全局连接入口。
 * 本屏保留作旧 [com.blazepush.feature.test.ui.TestNavRoute.Connection] 路由的 transitional fallback，
 * 旧代码若仍跳到 Connection 路由不会报错，但 4 tab shell 内已不应再触达。
 *
 * 计划在 future round 删除。
 */
@Deprecated("Use DeviceHomeScreen in TrackTech App Shell")
@Composable
fun DeviceConnectionScreen(
    onConnected: () -> Unit,
    gpsDataViewModel: GpsDataViewModel = koinViewModel()
) {
    val gpsData by gpsDataViewModel.gpsData.collectAsState()
    val connectionState by gpsDataViewModel.connectionState.collectAsState()
    val dataQuality by gpsDataViewModel.dataQuality.collectAsState()
    val isScanning by gpsDataViewModel.isScanning.collectAsState()
    val scanResults by gpsDataViewModel.scanResults.collectAsState()

    var showScanDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "GPS 设备",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        // 连接状态卡片
        ConnectionStatusCard(
            gpsData = gpsData,
            connectionState = connectionState,
            onScanClick = { showScanDialog = true },
            scanResultsCount = scanResults.size
        )

        // GPS信号质量卡片
        if (connectionState == ConnectionState.CONNECTED) {
            GpsSignalCard(gpsData)

            if (dataQuality.overall == QualityLevel.POOR) {
                Spacer(modifier = Modifier.height(8.dp))
                DataQualityCard(quality = dataQuality)
            }
        }

        // 开始测试按钮
        Button(
            onClick = onConnected,
            enabled = connectionState == ConnectionState.CONNECTED && gpsData.isTestReady,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(
                text = when {
                    connectionState != ConnectionState.CONNECTED -> "等待设备连接..."
                    !gpsData.isTestReady -> "等待GPS就绪（卫星: ${gpsData.satelliteCount}）"
                    else -> "开始测试 →"
                },
                fontSize = 16.sp
            )
        }
    }

    // 设备扫描对话框
    if (showScanDialog) {
        DeviceScanDialog(
            isScanning = isScanning,
            scanResults = scanResults,
            onDismiss = {
                showScanDialog = false
                gpsDataViewModel.stopScan()
            },
            onStopScan = {
                gpsDataViewModel.stopScan()
            },
            onDeviceClick = { device ->
                gpsDataViewModel.connectDevice(device)
                showScanDialog = false
            }
        )

        // 自动开始扫描
        LaunchedEffect(Unit) {
            gpsDataViewModel.startScan()
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    gpsData: GpsData,
    connectionState: ConnectionState,
    onScanClick: () -> Unit,
    scanResultsCount: Int
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("设备状态", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                // 扫描按钮
                if (connectionState != ConnectionState.CONNECTED) {
                    OutlinedButton(
                        onClick = onScanClick,
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("扫描设备", fontSize = 12.sp)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (statusText, statusColor) = when (connectionState) {
                    ConnectionState.CONNECTED -> "已连接" to Color(0xFF4CAF50)
                    ConnectionState.CONNECTING -> "连接中..." to Color(0xFFFF9800)
                    ConnectionState.DISCONNECTED -> "未连接" to Color(0xFFF44336)
                    ConnectionState.DISCONNECTING -> "断开中..." to Color(0xFFFF9800)
                }
                Surface(
                    modifier = Modifier.size(10.dp),
                    shape = MaterialTheme.shapes.small,
                    color = statusColor
                ) {}
                Text(statusText, color = statusColor, fontWeight = FontWeight.Medium)

                // 显示找到的设备数量
                if (connectionState == ConnectionState.DISCONNECTED && scanResultsCount > 0) {
                    Text(
                        "找到 $scanResultsCount 台设备",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }

            if (connectionState == ConnectionState.CONNECTED) {
                Text("频率: ${gpsData.frequency} Hz", fontSize = 14.sp, color = Color.Gray)
            }

            gpsData.errorMessage?.let {
                Text(it, fontSize = 12.sp, color = Color(0xFFF44336))
            }
        }
    }
}

@Composable
private fun GpsSignalCard(gpsData: GpsData) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GPS 信号", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("卫星数量", fontSize = 12.sp, color = Color.Gray)
                    Text("${gpsData.satelliteCount}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("HDOP", fontSize = 12.sp, color = Color.Gray)
                    Text(String.format("%.1f", gpsData.hdop), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("就绪状态", fontSize = 12.sp, color = Color.Gray)
                    Text(
                        if (gpsData.isTestReady) "就绪" else "等待",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (gpsData.isTestReady) Color(0xFF4CAF50) else Color(0xFFFF9800)
                    )
                }
            }
        }
    }
}
