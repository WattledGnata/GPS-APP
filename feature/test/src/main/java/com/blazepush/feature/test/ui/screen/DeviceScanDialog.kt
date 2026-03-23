package com.blazepush.feature.test.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.blazepush.core.bluetooth.ScannedDevice
import com.blazepush.core.bluetooth.SignalStrength

/**
 * BLE设备扫描对话框
 *
 * @param isScanning 是否正在扫描
 * @param scanResults 扫描结果列表
 * @param onDismiss 关闭对话框回调
 * @param onStopScan 停止扫描回调
 * @param onDeviceClick 设备点击回调
 */
@Composable
fun DeviceScanDialog(
    isScanning: Boolean,
    scanResults: List<ScannedDevice>,
    onDismiss: () -> Unit,
    onStopScan: () -> Unit,
    onDeviceClick: (ScannedDevice) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // 标题栏
                ScanDialogTitle(
                    isScanning = isScanning,
                    deviceCount = scanResults.size,
                    onStopScan = onStopScan,
                    onClose = onDismiss
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 设备列表
                if (scanResults.isEmpty()) {
                    EmptyScanState(isScanning = isScanning)
                } else {
                    DeviceList(
                        devices = scanResults,
                        onDeviceClick = onDeviceClick
                    )
                }
            }
        }
    }
}

/**
 * 扫描对话框标题栏
 */
@Composable
private fun ScanDialogTitle(
    isScanning: Boolean,
    deviceCount: Int,
    onStopScan: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 标题和状态
        Column {
            Text(
                text = "扫描BLE设备",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 扫描状态指示器
                ScanningIndicator(isScanning = isScanning)

                Text(
                    text = when {
                        isScanning -> "正在扫描..."
                        deviceCount > 0 -> "找到 $deviceCount 台设备"
                        else -> "扫描完成"
                    },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 操作按钮
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isScanning) {
                TextButton(onClick = onStopScan) {
                    Text("停止扫描")
                }
            }

            IconButton(onClick = onClose) {
                Text(
                    text = "✕",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 扫描状态指示器
 */
@Composable
private fun ScanningIndicator(isScanning: Boolean) {
    val color = if (isScanning) {
        Color(0xFF4CAF50) // 绿色
    } else {
        Color(0xFF9E9E9E) // 灰色
    }

    Surface(
        modifier = Modifier.size(8.dp),
        shape = MaterialTheme.shapes.small,
        color = color
    ) {}
}

/**
 * 空状态提示
 */
@Composable
private fun EmptyScanState(isScanning: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = if (isScanning) {
                    "正在搜索附近的设备..."
                } else {
                    "未发现设备"
                },
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!isScanning) {
                Text(
                    text = "请确保设备已开启且在附近",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

/**
 * 设备列表
 */
@Composable
private fun DeviceList(
    devices: List<ScannedDevice>,
    onDeviceClick: (ScannedDevice) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(
            items = devices,
            key = { it.address }
        ) { device ->
            ScannedDeviceItem(
                device = device,
                onClick = { onDeviceClick(device) }
            )
        }
    }
}

/**
 * 单个扫描设备项
 */
@Composable
private fun ScannedDeviceItem(
    device: ScannedDevice,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 设备信息
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = device.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = device.address,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            // 信号强度
            SignalStrengthIndicator(
                strength = device.getSignalStrength(),
                rssi = device.rssi
            )
        }
    }
}

/**
 * 信号强度指示器
 */
@Composable
private fun SignalStrengthIndicator(
    strength: SignalStrength,
    rssi: Int
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 信号格数
        SignalBars(strength = strength)

        // RSSI值
        Text(
            text = "$rssi dBm",
            fontSize = 12.sp,
            color = Color.Gray
        )
    }
}

/**
 * 信号格数可视化
 */
@Composable
private fun SignalBars(strength: SignalStrength) {
    val barCount = when (strength) {
        SignalStrength.EXCELLENT -> 4
        SignalStrength.GOOD -> 3
        SignalStrength.FAIR -> 2
        SignalStrength.WEAK -> 1
    }

    val color = when (strength) {
        SignalStrength.EXCELLENT -> Color(0xFF4CAF50) // 绿色
        SignalStrength.GOOD -> Color(0xFF8BC34A) // 浅绿
        SignalStrength.FAIR -> Color(0xFFFF9800) // 橙色
        SignalStrength.WEAK -> Color(0xFFF44336) // 红色
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        repeat(4) { index ->
            val isActive = index < barCount
            val height = when (index) {
                0 -> 8.dp
                1 -> 12.dp
                2 -> 16.dp
                3 -> 20.dp
                else -> 8.dp
            }

            Surface(
                modifier = Modifier.width(4.dp),
                shape = RoundedCornerShape(2.dp),
                color = if (isActive) color else Color(0xFFE0E0E0)
            ) {
                Spacer(modifier = Modifier.height(height))
            }
        }
    }
}
