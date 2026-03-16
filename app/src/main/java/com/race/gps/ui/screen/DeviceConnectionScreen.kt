package com.race.gps.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.race.gps.bluetooth.ConnectionState
import com.race.gps.domain.model.GpsData
import com.race.gps.viewmodel.GpsDataViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * 设备连接页面
 * 显示GPS设备连接状态和信号质量，连接成功后可进入测试
 */
@Composable
fun DeviceConnectionScreen(
    onConnected: () -> Unit,
    gpsDataViewModel: GpsDataViewModel = koinViewModel()
) {
    val gpsData by gpsDataViewModel.gpsData.collectAsState()
    val connectionState by gpsDataViewModel.connectionState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("GPS 设备", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        // 连接状态卡片
        ConnectionStatusCard(gpsData, connectionState)

        // GPS信号质量卡片
        if (connectionState == ConnectionState.CONNECTED) {
            GpsSignalCard(gpsData)
        }

        Spacer(modifier = Modifier.weight(1f))

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
}

@Composable
private fun ConnectionStatusCard(gpsData: GpsData, connectionState: ConnectionState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("设备状态", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (statusText, statusColor) = when (connectionState) {
                    ConnectionState.CONNECTED -> "已连接" to Color(0xFF4CAF50)
                    ConnectionState.CONNECTING -> "连接中..." to Color(0xFFFF9800)
                    ConnectionState.DISCONNECTED -> "未连接" to Color(0xFFF44336)
                }
                Surface(
                    modifier = Modifier.size(10.dp),
                    shape = MaterialTheme.shapes.small,
                    color = statusColor
                ) {}
                Text(statusText, color = statusColor, fontWeight = FontWeight.Medium)
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
