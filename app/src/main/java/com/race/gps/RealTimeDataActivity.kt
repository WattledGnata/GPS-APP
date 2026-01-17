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
    private val TAG = "RaceChronoGPS"
    private val RACECHRONO_SERVICE_UUID = "00001ff8-0000-1000-8000-00805f9b34fb"
    private val RACECHRONO_CHARACTERISTIC_UUID = "00000003-0000-1000-8000-00805f9b34fb"
    private val RACECHRONO_TIME_CHARACTERISTIC_UUID = "00000004-0000-1000-8000-00805f9b34fb"
    
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
        
        Log.d(TAG, "Starting RealTimeDataActivity for device: $deviceName ($deviceAddress)")

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
        Log.d(TAG, "Attempting to connect to device: $deviceAddress")
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        
        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled, cannot connect to device")
            return
        }
        
        val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
        Log.d(TAG, "Found remote device: ${device.name} (${device.address})")
        
        // Check BLUETOOTH_CONNECT permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "BLUETOOTH_CONNECT permission not granted")
            return
        }
        
        // Connect to GATT server
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
        Log.d(TAG, "Connecting to GATT server...")
    }

    // Parse GPS data from BLE characteristic (main characteristic - 00000003)
    private fun parseGpsData(data: ByteArray) {
        Log.d(TAG, "Received GPS main data, size: ${data.size}")
        
        // According to ESP32 code, the main characteristic sends 20 bytes of data
        if (data.size < 20) {
            Log.d(TAG, "Invalid GPS main data size: ${data.size}, expected 20")
            return
        }

        // Extract sync bits (first 3 bits of first byte)
        val syncBits = (data[0].toInt() shr 5) and 0x07
        Log.d(TAG, "Sync bits: $syncBits")
        
        // Extract time since hour start (21 bits total)
        val timeSinceHourStart = ((data[0].toInt() and 0x1F) shl 16) or 
                                 (data[1].toInt() shl 8) or 
                                 data[2].toInt()
        Log.d(TAG, "Time since hour start: $timeSinceHourStart")
        
        // Extract fix quality and satellite count from 4th byte
        val fixQuality = (data[3].toInt() shr 6) and 0x03
        val satellites = data[3].toInt() and 0x3F
        
        Log.d(TAG, "Raw data[3]: ${data[3]}, satellites: $satellites, fixQuality: $fixQuality")
        
        // Extract latitude (4 bytes, big endian)
        val latitude = ((data[4].toInt() and 0xFF) shl 24) or 
                      ((data[5].toInt() and 0xFF) shl 16) or 
                      ((data[6].toInt() and 0xFF) shl 8) or 
                       (data[7].toInt() and 0xFF)
        
        // Extract longitude (4 bytes, big endian)
        val longitude = ((data[8].toInt() and 0xFF) shl 24) or 
                       ((data[9].toInt() and 0xFF) shl 16) or 
                       ((data[10].toInt() and 0xFF) shl 8) or 
                        (data[11].toInt() and 0xFF)
        
        // Extract altitude (2 bytes, big endian)
        val altitude = ((data[12].toInt() and 0xFF) shl 8) or (data[13].toInt() and 0xFF)
        val altitudeMeters = altitude / 10.0 - 500.0 // Convert to meters with offset
        
        // Extract speed (2 bytes, big endian)
        val speed = ((data[14].toInt() and 0xFF) shl 8) or (data[15].toInt() and 0xFF)
        val speedKmh = if (speed < 0x8000) {
            // Speed is in km/h * 100
            speed / 100.0
        } else {
            // Speed is in km/h * 10
            (speed and 0x7FFF) / 10.0
        }
        
        // Extract bearing (2 bytes, big endian)
        val bearing = ((data[16].toInt() and 0xFF) shl 8) or (data[17].toInt() and 0xFF)
        val bearingDegrees = bearing / 100.0
        
        // Extract HDOP and VDOP (1 byte each)
        val hdop = data[18].toInt() / 10.0 // HDOP * 10
        val vdop = data[19].toInt() / 10.0 // VDOP * 10
        
        // Update real-time data state
        _realTimeData.value = _realTimeData.value.copy(
            time = System.currentTimeMillis(),
            satelliteCount = satellites,
            dop = String.format("%.2f", hdop),
            positionType = fixQuality,
            azimuth = bearingDegrees.toInt(),
            altitude = String.format("%.1f", altitudeMeters),
            altitudeError = String.format("%.2f", vdop),
            latitude = String.format("%.7f", latitude / 10000000.0),
            longitude = String.format("%.7f", longitude / 10000000.0),
            // Calculate elapsed time, distance, and speed from GPS data
            elapsedTime = "0.0",
            distance = "0.0",
            speed = speedKmh.toInt()
        )
    }
    
    // Parse GPS time data from BLE characteristic (time characteristic - 00000004)
    private fun parseGpsTimeData(data: ByteArray) {
        Log.d(TAG, "Received GPS time data, size: ${data.size}")
        
        // According to ESP32 code, the time characteristic sends 3 bytes of data
        if (data.size < 3) {
            Log.d(TAG, "Invalid GPS time data size: ${data.size}, expected 3")
            return
        }
        
        // Extract sync bits (first 3 bits of first byte)
        val syncBits = (data[0].toInt() shr 5) and 0x07
        
        // Extract date and hour (21 bits total)
        val dateAndHour = ((data[0].toInt() and 0x1F) shl 16) or 
                         (data[1].toInt() shl 8) or 
                          data[2].toInt()
        
        // Log the parsed time data
        Log.d(TAG, "GPS time data - Sync bits: $syncBits, DateAndHour: $dateAndHour")
        
        // dateAndHour is calculated as: (Year-2000)*8928 + (Month-1)*744 + (Day-1)*24 + Hour
        // We can optionally decode this to actual date/time if needed
        // For now, we'll just log it for debugging purposes
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
                
                // Enable notifications for both GPS main characteristic and GPS time characteristic
                // as defined in the ESP32 code
                
                // 1. Enable GPS main characteristic (00000003)
                val mainCharacteristic = service.getCharacteristic(java.util.UUID.fromString(RACECHRONO_CHARACTERISTIC_UUID))
                if (mainCharacteristic != null) {
                    Log.d(TAG, "Found GPS main characteristic: ${mainCharacteristic.uuid}")
                    
                    if (ActivityCompat.checkSelfPermission(
                            this@RealTimeDataActivity,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.e(TAG, "BLUETOOTH_CONNECT permission not granted")
                        return
                    }
                    
                    // Enable notifications for GPS main characteristic
                    val mainSuccess = gatt.setCharacteristicNotification(mainCharacteristic, true)
                    Log.d(TAG, "Enabled main characteristic notifications: $mainSuccess")
                    
                    // Enable descriptor for notifications
                    val mainDescriptor = mainCharacteristic.getDescriptor(java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    if (mainDescriptor != null) {
                        mainDescriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        val mainDescSuccess = gatt.writeDescriptor(mainDescriptor)
                        Log.d(TAG, "Wrote main characteristic descriptor: $mainDescSuccess")
                    }
                } else {
                    Log.e(TAG, "GPS main characteristic not found with UUID: $RACECHRONO_CHARACTERISTIC_UUID")
                }
                
                // 2. Enable GPS time characteristic (00000004)
                val timeCharacteristic = service.getCharacteristic(java.util.UUID.fromString(RACECHRONO_TIME_CHARACTERISTIC_UUID))
                if (timeCharacteristic != null) {
                    Log.d(TAG, "Found GPS time characteristic: ${timeCharacteristic.uuid}")
                    
                    if (ActivityCompat.checkSelfPermission(
                            this@RealTimeDataActivity,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.e(TAG, "BLUETOOTH_CONNECT permission not granted")
                        return
                    }
                    
                    // Enable notifications for GPS time characteristic
                    val timeSuccess = gatt.setCharacteristicNotification(timeCharacteristic, true)
                    Log.d(TAG, "Enabled time characteristic notifications: $timeSuccess")
                    
                    // Enable descriptor for notifications
                    val timeDescriptor = timeCharacteristic.getDescriptor(java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    if (timeDescriptor != null) {
                        timeDescriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        val timeDescSuccess = gatt.writeDescriptor(timeDescriptor)
                        Log.d(TAG, "Wrote time characteristic descriptor: $timeDescSuccess")
                    }
                } else {
                    Log.e(TAG, "GPS time characteristic not found with UUID: $RACECHRONO_TIME_CHARACTERISTIC_UUID")
                    Log.e(TAG, "Available characteristics in service ${service.uuid}:")
                    service.characteristics.forEach { char ->
                        Log.e(TAG, "  - ${char.uuid}, properties: ${char.properties}")
                    }
                }
            } else {
                Log.e(TAG, "Failed to discover services: status=$status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(gatt, characteristic)
            // Parse the received data and update UI based on characteristic type
            val data = characteristic.value
            val charUuid = characteristic.uuid.toString()
            
            Log.d(TAG, "Characteristic changed: $charUuid, data size: ${data.size}")
            Log.d(TAG, "Data: ${data.joinToString { String.format("%02X", it) }}")
            
            if (charUuid.equals(RACECHRONO_CHARACTERISTIC_UUID, ignoreCase = true)) {
                // This is the GPS main characteristic (00000003)
                // It contains the full GPS data (20 bytes)
                parseGpsData(data)
            } else if (charUuid.equals(RACECHRONO_TIME_CHARACTERISTIC_UUID, ignoreCase = true)) {
                // This is the GPS time characteristic (00000004)
                // It contains time data (3 bytes) including sync bits and date/hour
                parseGpsTimeData(data)
            }
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