package com.race.gps

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.race.gps.data.model.BluetoothData
import com.race.gps.service.BluetoothManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.race.gps.utils.AppPreferences
import androidx.compose.foundation.clickable

class RealTimeDataActivity : ComponentActivity() {
    private lateinit var deviceName: String
    private val TAG = "RaceChronoGPS"
    
    // Bluetooth Manager
    private lateinit var bluetoothManager: BluetoothManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set status bar to black/immersive style
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.BLACK

        deviceName = intent.getStringExtra("device_name") ?: "Unknown Device"
        
        Log.d(TAG, "Starting RealTimeDataActivity")

        // Initialize Bluetooth Manager
        bluetoothManager = BluetoothManager.getInstance(this)

        setContent {
            // Add MaterialTheme with light color scheme for better visibility
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF6200EE),
                    background = Color.White,
                    surface = Color.White,
                    onPrimary = Color.White,
                    onBackground = Color.Black,
                    onSurface = Color.Black
                )
            ) {
                // Observe bluetooth data
                val bluetoothData by bluetoothManager.bluetoothDataFlow.collectAsState()
                
                RealTimeDataScreen(
                    deviceName = deviceName,
                    bluetoothData = bluetoothData,
                    onBackClick = { finish() }
                )
            }
        }
    }
}

@Composable
fun SettingsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedMode by remember { 
        mutableStateOf(AppPreferences.getMockMode(context)) 
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置数据源") },
        text = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedMode = AppPreferences.MOCK_MODE_REAL }
                        .padding(vertical = 8.dp)
                ) {
                    RadioButton(
                        selected = selectedMode == AppPreferences.MOCK_MODE_REAL,
                        onClick = { selectedMode = AppPreferences.MOCK_MODE_REAL }
                    )
                    Text("真实蓝牙设备 (Real Device)")
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedMode = AppPreferences.MOCK_MODE_PROTOCOL }
                        .padding(vertical = 8.dp)
                ) {
                    RadioButton(
                        selected = selectedMode == AppPreferences.MOCK_MODE_PROTOCOL,
                        onClick = { selectedMode = AppPreferences.MOCK_MODE_PROTOCOL }
                    )
                    Text("底层协议模拟 (Protocol Mock)")
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedMode = AppPreferences.MOCK_MODE_SIMPLE }
                        .padding(vertical = 8.dp)
                ) {
                    RadioButton(
                        selected = selectedMode == AppPreferences.MOCK_MODE_SIMPLE,
                        onClick = { selectedMode = AppPreferences.MOCK_MODE_SIMPLE }
                    )
                    Text("简单数据模拟 (Simple Mock)")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    AppPreferences.setMockMode(context, selectedMode)
                    onDismiss()
                    android.widget.Toast.makeText(context, "设置已保存，请重启应用生效", android.widget.Toast.LENGTH_LONG).show()
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RealTimeDataScreen(
    deviceName: String,
    bluetoothData: BluetoothData,
    onBackClick: () -> Unit
) {
    var showSettingsDialog by remember { mutableStateOf(false) }

    if (showSettingsDialog) {
        SettingsDialog(onDismiss = { showSettingsDialog = false })
    }

    // Helper to format time
    val formattedTime = remember(bluetoothData.time) {
        val date = java.util.Date(bluetoothData.time)
        val sdf = java.text.SimpleDateFormat("HH:mm:ss\nyyyy/MM/dd", java.util.Locale.getDefault())
        sdf.format(date)
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    Text(text = "实时数据")
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings"
                        )
                    }
                }
            )
        }
    ) {paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Device info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = deviceName,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = "Bluetooth LE GPS",
                        fontSize = 16.sp,
                        color = Color.Gray
                    )
                    Text(
                        text = if (bluetoothData.isConnected) "已连接，已锁定 ${bluetoothData.satelliteCount} 颗卫星" else "未连接",
                        fontSize = 14.sp,
                        color = if (bluetoothData.isConnected) Color.Green else Color.Red,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // GPS Receiver info
            Text(
                text = "GPS接收器",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Grid layout for real-time data
            Column(modifier = Modifier.fillMaxWidth()) {
                // First row: Time, Satellite Count, DOP
                Row(modifier = Modifier.fillMaxWidth()) {
                    DataCard(
                        title = "时间",
                        value = formattedTime,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    DataCard(
                        title = "卫星数量",
                        value = bluetoothData.satelliteCount.toString(),
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    DataCard(
                        title = "坐标误差",
                        value = bluetoothData.dop,
                        subtitle = "DOP",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Second row: Position Type, Azimuth, Altitude
                Row(modifier = Modifier.fillMaxWidth()) {
                    DataCard(
                        title = "定位类型",
                        value = bluetoothData.positionType.toString(),
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    DataCard(
                        title = "方位角",
                        value = bluetoothData.azimuth.toString(),
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    DataCard(
                        title = "海拔",
                        value = bluetoothData.altitude,
                        subtitle = "m",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Third row: Altitude Error, Latitude, Longitude
                Row(modifier = Modifier.fillMaxWidth()) {
                    DataCard(
                        title = "海拔误差",
                        value = bluetoothData.altitudeError,
                        subtitle = "DOP",
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    DataCard(
                        title = "纬度",
                        value = bluetoothData.latitude,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    DataCard(
                        title = "经度",
                        value = bluetoothData.longitude,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Fourth row: Elapsed Time, Distance, Speed, GPS Frequency
                Row(modifier = Modifier.fillMaxWidth()) {
                    DataCard(
                        title = "经过时间",
                        value = bluetoothData.elapsedTime,
                        subtitle = "s",
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    DataCard(
                        title = "路程",
                        value = bluetoothData.distance,
                        subtitle = "km",
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    DataCard(
                        title = "车辆速度",
                        value = String.format("%.1f", bluetoothData.speed),
                        subtitle = "kph",
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    DataCard(
                        title = "GPS频率",
                        value = bluetoothData.frequency,
                        subtitle = "Hz",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun DataCard(
    title: String,
    value: String,
    subtitle: String = "",
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(120.dp) // Set fixed height for all cards
            .fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center // Center content vertically
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = value,
                fontSize = 20.sp, // Slightly smaller font to fit better
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, // Ensure text is centered
                maxLines = 2, // Limit to 2 lines to prevent overflow
                overflow = TextOverflow.Ellipsis // Add ellipsis if text is too long
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
