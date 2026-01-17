package com.race.gps

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RealTimeDataActivity : ComponentActivity() {
    private lateinit var deviceName: String
    private lateinit var deviceAddress: String
    private var bluetoothGatt: BluetoothGatt? = null
    private val TAG = "RealTimeDataActivity"
    private val RACECHRONO_SERVICE_UUID = "00001ff8-0000-1000-8000-00805f9b34fb"
    private val RACECHRONO_CHARACTERISTIC_UUID = "00002ff8-0000-1000-8000-00805f9b34fb"
    
    // Real-time data state
    private val _realTimeData = mutableStateOf(RealTimeData())
    val realTimeData by _realTimeData

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set status bar to black/immersive style
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.BLACK

        deviceName = intent.getStringExtra("device_name") ?: "Unknown Device"
        deviceAddress = intent.getStringExtra("device_address") ?: ""

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
                RealTimeDataScreen(
                    deviceName = deviceName,
                    realTimeData = realTimeData,
                    onBackClick = { finish() }
                )
            }
        }

        // Connect to BLE device to get real GPS data
        connectToDevice()
    }

    // BLE connection and GPS data handling
    private fun connectToDevice() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
        
        // Check BLUETOOTH_CONNECT permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "BLUETOOTH_CONNECT permission not granted")
            return
        }
        
        // Connect to GATT server
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
        Log.d(TAG, "Connecting to GATT server...")
    }

    // Parse GPS data from BLE characteristic
    private fun parseGpsData(data: ByteArray) {
        if (data.size < 40) {
            Log.d(TAG, "Invalid GPS data size: ${data.size}, expected 40")
            return
        }

        // Check sync bits first
        val syncBits = data[0].toInt() and 0x07
        if (syncBits != 0x07) {
            Log.d(TAG, "Invalid sync bits: $syncBits, expected 0x07")
            return
        }

        // Extract data according to RaceChrono_ESP32_M9N.ino protocol
        // Use proper little-endian byte order for all multi-byte values
        val timeSinceHourStart = ByteBuffer.wrap(data, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val fixQuality = (data[5].toInt() shr 6) and 0x03
        val satellites = data[5].toInt() and 0x3F
        
        // Latitude and Longitude in 1e-7 degrees
        val latitude = ByteBuffer.wrap(data, 6, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val longitude = ByteBuffer.wrap(data, 10, 4).order(ByteOrder.LITTLE_ENDIAN).int
        
        // Altitude in centimeters
        val altitude = ByteBuffer.wrap(data, 14, 4).order(ByteOrder.LITTLE_ENDIAN).int
        
        // Speed in centimeters per second
        val speed = ByteBuffer.wrap(data, 18, 4).order(ByteOrder.LITTLE_ENDIAN).int
        
        // Heading in degrees * 100
        val heading = ByteBuffer.wrap(data, 22, 4).order(ByteOrder.LITTLE_ENDIAN).int
        
        // Dilution of precision values in centimeters
        val hdop = ByteBuffer.wrap(data, 26, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val vdop = ByteBuffer.wrap(data, 28, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val accuracy = ByteBuffer.wrap(data, 30, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        
        // Update real-time data state
        _realTimeData.value = _realTimeData.value.copy(
            time = System.currentTimeMillis(),
            satelliteCount = satellites,
            dop = String.format("%.2f", hdop / 100.0),
            positionType = fixQuality,
            azimuth = heading / 100,
            altitude = String.format("%.1f", altitude / 100.0),
            altitudeError = String.format("%.2f", vdop / 100.0),
            latitude = String.format("%.7f", latitude / 10000000.0),
            longitude = String.format("%.7f", longitude / 10000000.0),
            // Calculate elapsed time, distance, and speed from GPS data
            elapsedTime = "0.0",
            distance = "0.0",
            speed = (speed / 100) // Convert to km/h: (cm/s / 100) * 3.6 = km/h
        )
    }


    // Bluetooth GATT callback for real data
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            Log.d(TAG, "Connection state changed: status=$status, newState=$newState")
            
            // Check if connection failed due to an error
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Connection error: status=$status")
                return
            }
            
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server")
                if (ActivityCompat.checkSelfPermission(
                        this@RealTimeDataActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "BLUETOOTH_CONNECT permission not granted")
                    return
                }
                // Discover services to find our GPS characteristic
                gatt.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server")
                // Update UI to show disconnected state
                runOnUiThread {
                    Toast.makeText(this@RealTimeDataActivity, "蓝牙连接已断开", Toast.LENGTH_SHORT).show()
                }
            } else if (newState == BluetoothGatt.STATE_CONNECTING) {
                Log.d(TAG, "Connecting to GATT server...")
            } else if (newState == BluetoothGatt.STATE_DISCONNECTING) {
                Log.d(TAG, "Disconnecting from GATT server...")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            Log.d(TAG, "Services discovered: status=$status")
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Log all discovered services for debugging
                gatt.services.forEach { service ->
                    Log.d(TAG, "Discovered service: ${service.uuid}")
                    service.characteristics.forEach { char ->
                        Log.d(TAG, "  Characteristic: ${char.uuid}, properties: ${char.properties}")
                    }
                }
                
                val service = gatt.getService(java.util.UUID.fromString(RACECHRONO_SERVICE_UUID))
                if (service == null) {
                    Log.e(TAG, "RaceChrono service not found")
                    return
                }
                
                service?.let { 
                    val characteristic = it.getCharacteristic(java.util.UUID.fromString(RACECHRONO_CHARACTERISTIC_UUID))
                    if (characteristic == null) {
                        Log.e(TAG, "RaceChrono characteristic not found")
                        return
                    }
                    
                    characteristic?.let {char ->
                        if (ActivityCompat.checkSelfPermission(
                                this@RealTimeDataActivity,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted")
                            return
                        }
                        
                        // Enable notifications for the GPS characteristic
                        val success = gatt.setCharacteristicNotification(char, true)
                        Log.d(TAG, "Enabled notifications: $success")
                        
                        // Also enable the descriptor for notifications
                        val descriptor = char.getDescriptor(java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            val descSuccess = gatt.writeDescriptor(descriptor)
                            Log.d(TAG, "Wrote notification descriptor: $descSuccess")
                        }
                    }
                }
            } else {
                Log.e(TAG, "Failed to discover services: status=$status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(gatt, characteristic)
            // Parse the received data and update UI
            val data = characteristic.value
            Log.d(TAG, "Characteristic changed, data size: ${data.size}")
            parseGpsData(data)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        bluetoothGatt?.close()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RealTimeDataScreen(
    deviceName: String,
    realTimeData: RealTimeData,
    onBackClick: () -> Unit
) {
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
                    IconButton(onClick = { /* TODO: Implement settings */ }) {
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
                        text = "已连接，已锁定 ${realTimeData.satelliteCount} 颗卫星",
                        fontSize = 14.sp,
                        color = Color.Green,
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
                        value = realTimeData.formattedTime,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    DataCard(
                        title = "卫星数量",
                        value = realTimeData.satelliteCount.toString(),
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    DataCard(
                        title = "坐标误差",
                        value = realTimeData.dop,
                        subtitle = "DOP",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Second row: Position Type, Azimuth, Altitude
                Row(modifier = Modifier.fillMaxWidth()) {
                    DataCard(
                        title = "定位类型",
                        value = realTimeData.positionType.toString(),
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    DataCard(
                        title = "方位角",
                        value = realTimeData.azimuth.toString(),
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    DataCard(
                        title = "海拔",
                        value = realTimeData.altitude,
                        subtitle = "m",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Third row: Altitude Error, Latitude, Longitude
                Row(modifier = Modifier.fillMaxWidth()) {
                    DataCard(
                        title = "海拔误差",
                        value = realTimeData.altitudeError,
                        subtitle = "DOP",
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    DataCard(
                        title = "纬度",
                        value = realTimeData.latitude,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    DataCard(
                        title = "经度",
                        value = realTimeData.longitude,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Fourth row: Elapsed Time, Distance, Speed
                Row(modifier = Modifier.fillMaxWidth()) {
                    DataCard(
                        title = "经过时间",
                        value = realTimeData.elapsedTime,
                        subtitle = "s",
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    DataCard(
                        title = "路程",
                        value = realTimeData.distance,
                        subtitle = "km",
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    DataCard(
                        title = "车辆速度",
                        value = realTimeData.speed.toString(),
                        subtitle = "kph",
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

data class RealTimeData(
    val time: Long = System.currentTimeMillis(),
    val satelliteCount: Int = 0,
    val dop: String = "0.00",
    val positionType: Int = 0,
    val azimuth: Int = 0,
    val altitude: String = "0.0",
    val altitudeError: String = "0.00",
    val latitude: String = "0.0",
    val longitude: String = "0.0",
    val elapsedTime: String = "0.0",
    val distance: String = "0.0",
    val speed: Int = 0
) {
    val formattedTime: String
        get() {
            val date = java.util.Date(time)
            val sdf = java.text.SimpleDateFormat("HH:mm:ss\nyyyy/MM/dd", java.util.Locale.getDefault())
            return sdf.format(date)
        }
}