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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModelProvider
import com.race.gps.TestActivity.Companion.TAG
import com.race.gps.data.model.CarModel
import com.race.gps.data.model.TestRecord
import com.race.gps.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TestActivity : ComponentActivity() {
    companion object {
        const val TAG = "RaceChronoGPS"
        
        // BLE UUIDs from ESP32 code
        private const val RACECHRONO_SERVICE_UUID = "00001ff8-0000-1000-8000-00805f9b34fb"
        private const val RACECHRONO_CHARACTERISTIC_UUID = "00000003-0000-1000-8000-00805f9b34fb"
        private const val RACECHRONO_TIME_CHARACTERISTIC_UUID = "00000004-0000-1000-8000-00805f9b34fb"
        private const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"
    }
    
    private lateinit var mainViewModel: MainViewModel
    
    // BLE variables
    private var bluetoothGatt: BluetoothGatt? = null
    private var gpsTime = 0L // GPS time in milliseconds since hour start
    private var lastGpsTimeUpdate = 0L // Local time when we last received GPS time
    
    // Test timing using GPS clock
    private var testStartTimeGps = 0L // GPS time when test started
    private var testEndTimeGps = 0L // GPS time when test completed
    private var isSpeedThresholdReached = false // Whether speed threshold (100km/h) has been reached
    private var isTestReady = false // Whether we have valid GPS time to start the test

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val testType = intent.getStringExtra("test_type")
        val deviceName = intent.getStringExtra("device_name")
        val deviceAddress = intent.getStringExtra("device_address")
        
        Log.d(TAG, "Starting TestActivity with testType: $testType, deviceName: $deviceName, deviceAddress: $deviceAddress")

        mainViewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[MainViewModel::class.java]
        
        // Connect to BLE device to get GPS time
        connectToDevice(deviceAddress)

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
                TestScreen(
                    testType = testType ?: "Unknown Test",
                    deviceName = deviceName ?: "Unknown Device",
                    deviceAddress = deviceAddress ?: "",
                    mainViewModel = mainViewModel,
                    isTestReady = isTestReady,
                    onTestStart = { startTestWithGpsClock() },
                    onTestStop = { stopTestWithGpsClock() }
                )
            }
        }
    }
    
    // Connect to BLE device to get GPS time
    private fun connectToDevice(deviceAddress: String?) {
        if (deviceAddress.isNullOrEmpty()) {
            Log.e(TAG, "Device address is null or empty")
            return
        }
        
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            return
        }
        
        val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted")
            return
        }
        
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
        Log.d(TAG, "Connecting to GATT server for GPS time...")
    }
    
    // Start test using GPS clock
    private fun startTestWithGpsClock() {
        if (!isTestReady) {
            Log.e(TAG, "Test not ready: no valid GPS time available")
            return
        }
        
        // Get current GPS time
        val currentGpsTime = calculateCurrentGpsTime()
        testStartTimeGps = currentGpsTime
        testEndTimeGps = 0
        isSpeedThresholdReached = false
        
        Log.d(TAG, "Test started with GPS time: $testStartTimeGps")
    }
    
    // Stop test using GPS clock
    private fun stopTestWithGpsClock() {
        // Get current GPS time
        val currentGpsTime = calculateCurrentGpsTime()
        testEndTimeGps = currentGpsTime
        
        val elapsedTime = if (testEndTimeGps > testStartTimeGps) {
            (testEndTimeGps - testStartTimeGps) / 1000.0 // Convert to seconds
        } else {
            0.0
        }
        
        Log.d(TAG, "Test stopped with GPS time: $testEndTimeGps, elapsed: $elapsedTime seconds")
    }
    
    // Calculate current GPS time based on last known GPS time and local time elapsed
    private fun calculateCurrentGpsTime(): Long {
        val localTimeElapsed = System.currentTimeMillis() - lastGpsTimeUpdate
        return gpsTime + localTimeElapsed
    }
    
    // Bluetooth GATT callback for GPS time
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            Log.d(TAG, "Connection state changed: status=$status, newState=$newState")
            
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Connection error: status=$status")
                return
            }
            
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server")
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
                gatt.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server")
                bluetoothGatt = null
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            Log.d(TAG, "Services discovered: status=$status")
            
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Failed to discover services: status=$status")
                return
            }
            
            // Find the RaceChrono service
            val service = gatt.getService(java.util.UUID.fromString(RACECHRONO_SERVICE_UUID))
            if (service == null) {
                Log.e(TAG, "RaceChrono service not found")
                return
            }
            
            // Enable notifications for GPS time characteristic
            val timeCharacteristic = service.getCharacteristic(java.util.UUID.fromString(RACECHRONO_TIME_CHARACTERISTIC_UUID))
            if (timeCharacteristic != null) {
                Log.d(TAG, "Found GPS time characteristic: ${timeCharacteristic.uuid}")
                
                if (ActivityCompat.checkSelfPermission(
                        this@TestActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "BLUETOOTH_CONNECT permission not granted")
                    return
                }
                
                // Enable notifications
                val success = gatt.setCharacteristicNotification(timeCharacteristic, true)
                Log.d(TAG, "Enabled GPS time notifications: $success")
                
                // Enable descriptor
                val descriptor = timeCharacteristic.getDescriptor(java.util.UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))
                if (descriptor != null) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    val descSuccess = gatt.writeDescriptor(descriptor)
                    Log.d(TAG, "Wrote GPS time descriptor: $descSuccess")
                }
            }
            
            // Enable notifications for GPS main characteristic (to get speed)
            val mainCharacteristic = service.getCharacteristic(java.util.UUID.fromString(RACECHRONO_CHARACTERISTIC_UUID))
            if (mainCharacteristic != null) {
                Log.d(TAG, "Found GPS main characteristic: ${mainCharacteristic.uuid}")
                
                if (ActivityCompat.checkSelfPermission(
                        this@TestActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "BLUETOOTH_CONNECT permission not granted")
                    return
                }
                
                // Enable notifications
                val success = gatt.setCharacteristicNotification(mainCharacteristic, true)
                Log.d(TAG, "Enabled GPS main notifications: $success")
                
                // Enable descriptor
                val descriptor = mainCharacteristic.getDescriptor(java.util.UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))
                if (descriptor != null) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    val descSuccess = gatt.writeDescriptor(descriptor)
                    Log.d(TAG, "Wrote GPS main descriptor: $descSuccess")
                }
            }
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(gatt, characteristic)
            val data = characteristic.value
            val charUuid = characteristic.uuid.toString()
            
            Log.d(TAG, "Characteristic changed: $charUuid, data size: ${data.size}")
            
            if (charUuid.equals(RACECHRONO_TIME_CHARACTERISTIC_UUID, ignoreCase = true)) {
                // Parse GPS time data (3 bytes)
                parseGpsTimeData(data)
            } else if (charUuid.equals(RACECHRONO_CHARACTERISTIC_UUID, ignoreCase = true)) {
                // Parse GPS main data (20 bytes) to get speed
                parseGpsMainData(data)
            }
        }
    }
    
    // Parse GPS time data from BLE characteristic
    private fun parseGpsTimeData(data: ByteArray) {
        if (data.size < 3) {
            Log.e(TAG, "Invalid GPS time data size: ${data.size}")
            return
        }
        
        // Extract sync bits and dateAndHour from 3 bytes
        val syncBits = (data[0].toInt() shr 5) and 0x07
        val dateAndHour = ((data[0].toInt() and 0x1F) shl 16) or 
                         (data[1].toInt() shl 8) or 
                          data[2].toInt()
        
        // Note: dateAndHour contains (Year-2000)*8928 + (Month-1)*744 + (Day-1)*24 + Hour
        // For our purposes, we don't need to decode the full date, just use it to validate GPS time
        
        // Update GPS time - we'll use the sync bits to estimate time since hour start
        // In a real implementation, we'd use the full GPS time protocol
        // For simplicity, we'll set a dummy GPS time for now
        // TODO: Implement proper GPS time parsing
        gpsTime = System.currentTimeMillis() % (3600 * 1000) // Milliseconds since hour start
        lastGpsTimeUpdate = System.currentTimeMillis()
        
        // Mark test as ready once we have valid GPS time
        isTestReady = true
        
        Log.d(TAG, "GPS time updated: $gpsTime ms, syncBits: $syncBits, dateAndHour: $dateAndHour")
    }
    
    // Parse GPS main data to get speed
    private fun parseGpsMainData(data: ByteArray) {
        if (data.size < 20) {
            Log.e(TAG, "Invalid GPS main data size: ${data.size}")
            return
        }
        
        // Extract speed from GPS main data (bytes 15-16, big endian)
        val speed = ((data[14].toInt() and 0xFF) shl 8) or (data[15].toInt() and 0xFF)
        val speedKmh = if (speed < 0x8000) {
            // Speed is in km/h * 100
            speed / 100.0
        } else {
            // Speed is in km/h * 10
            (speed and 0x7FFF) / 10.0
        }
        
        // Check if speed threshold (100km/h) is reached for acceleration test
        if (!isSpeedThresholdReached && speedKmh >= 100.0) {
            isSpeedThresholdReached = true
            stopTestWithGpsClock()
            
            // Calculate elapsed time in seconds
            val elapsedTime = if (testEndTimeGps > testStartTimeGps) {
                (testEndTimeGps - testStartTimeGps) / 1000.0
            } else {
                0.0
            }
            
            Log.d(TAG, "Speed threshold reached (${speedKmh}km/h), test completed in $elapsedTime seconds")
            // TODO: Update UI with test result
        }
        
        Log.d(TAG, "Current speed from GPS: ${speedKmh}km/h")
    }

    
    override fun onDestroy() {
        super.onDestroy()
        // Close BLE connection
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt?.close()
        }
    }
}

@Composable
@Preview
fun TestScreen(
    testType: String = "Sample Test",
    deviceName: String = "Unknown Device",
    deviceAddress: String = "",
    mainViewModel: MainViewModel? = null,
    isTestReady: Boolean = true,
    onTestStart: () -> Unit = {},
    onTestStop: () -> Unit = {}
) {
    val carModels by mainViewModel?.carModels?.observeAsState(emptyList()) ?: remember { mutableStateOf(emptyList()) }
    var selectedCarModel by remember { mutableStateOf<CarModel?>(null) }
    var showCarSelectionDialog by remember { mutableStateOf(true) }
    var showAddCarDialog by remember { mutableStateOf(false) }
    var newCarBrand by remember { mutableStateOf("") }
    var newCarName by remember { mutableStateOf("") }
    var newCarYear by remember { mutableStateOf("") }
    var newCarDescription by remember { mutableStateOf("") }
    
    // Test state variables - now using GPS clock managed by TestActivity
    var testResult by remember { mutableStateOf("Not Started") }
    var isTestRunning by remember { mutableStateOf(false) }
    var currentSpeed by remember { mutableStateOf(0.0) }
    var testCompleted by remember { mutableStateOf(false) }
    
    //成绩弹窗状态
    var showResultDialog by remember { mutableStateOf(false) }
    var lastTestResult by remember { mutableStateOf("0.0") }
    var dialogCountdown by remember { mutableStateOf(5) }
    
    // Test state management using GPS clock
    LaunchedEffect(isTestRunning) {
        if (isTestRunning) {
            Log.d(TAG, "Test started: $testType")
            testResult = "Running..."
            testCompleted = false
            testCompleted = false
            
            // Use GPS clock for timing - call the provided callback
            onTestStart()
            
            Log.d(TAG, "Test waiting for real GPS data from BLE device: $deviceName")
        } else {
            Log.d(TAG, "Test stopped: $testType")
            // Use GPS clock for timing - call the provided callback
            onTestStop()
        }
    }
    
    //弹窗倒计时
    LaunchedEffect(showResultDialog) {
        if (showResultDialog) {
            Log.d(TAG, "Starting result dialog countdown")
            dialogCountdown = 5
            repeat(5) {
                delay(1000)
                dialogCountdown--
                Log.v(TAG, "Dialog countdown: $dialogCountdown")
            }
            Log.d(TAG, "Result dialog countdown finished, closing dialog")
            showResultDialog = false
        }
    }

    // Car selection dialog
    if (showCarSelectionDialog) {
        AlertDialog(
            onDismissRequest = { /* Do not allow dismissal, must select a car */ },
            title = { Text(text = "选择车型") },
            text = {
                Column {
                    Text(text = "请选择车型或创建新车型。")
            Spacer(modifier = Modifier.height(16.dp))
            LazyColumn(modifier = Modifier.height(200.dp)) {
                items(carModels) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clickable { 
                                Log.d(TAG, "Selected car model: ${it.brand} ${it.name} (${it.year})")
                                selectedCarModel = it
                                showCarSelectionDialog = false
                            }
                    ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(text = it.name, fontWeight = FontWeight.Bold)
                                    Text(text = "${it.brand} - ${it.year}")
                                    if (it.description.isNotEmpty()) {
                                        Text(text = it.description, fontSize = 12.sp, color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showAddCarDialog = true
                }) {
                    Text(text = "创建新车型")
                }
            }
        )
    }
    
    // Add car dialog
    if (showAddCarDialog) {
        AlertDialog(
            onDismissRequest = { showAddCarDialog = false },
            title = { Text(text = "创建新车型") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newCarBrand,
                        onValueChange = { newCarBrand = it },
                        label = { Text(text = "品牌") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = newCarName,
                        onValueChange = { newCarName = it },
                        label = { Text(text = "型号") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = newCarYear,
                        onValueChange = { newCarYear = it },
                        label = { Text(text = "年份") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = newCarDescription,
                        onValueChange = { newCarDescription = it },
                        label = { Text(text = "描述（可选）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newCarBrand.isNotEmpty() && newCarName.isNotEmpty() && newCarYear.isNotEmpty()) {
                        val newCar = CarModel(
                            name = newCarName,
                            brand = newCarBrand,
                            year = newCarYear,
                            description = newCarDescription
                        )
                        Log.d(TAG, "Creating new car model: $newCar")
                        mainViewModel?.addCarModel(newCar)
                        selectedCarModel = newCar
                        showAddCarDialog = false
                        showCarSelectionDialog = false
                        // Reset fields
                        newCarBrand = ""
                        newCarName = ""
                        newCarYear = ""
                        newCarDescription = ""
                    }
                }) {
                    Text(text = "创建")
                }
            },
            dismissButton = {
                Button(onClick = { 
                    showAddCarDialog = false
                }) {
                    Text(text = "取消")
                }
            }
        )
    }
    
    //成绩弹窗
    if (showResultDialog) {
        Log.d(TAG, "Showing test result dialog with result: $lastTestResult, countdown: $dialogCountdown")
        AlertDialog(
            onDismissRequest = { showResultDialog = false },
            title = { Text(text = "测试完成！") },
            text = {
                Column {
                    Text(text = "测试类型: $testType", fontSize = 18.sp, modifier = Modifier.padding(bottom = 16.dp))
                    Text(text = "成绩: $lastTestResult", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                    Text(text = "自动关闭倒计时: $dialogCountdown 秒", fontSize = 14.sp, color = Color.Gray)
                }
            },
            confirmButton = {
                Button(onClick = { showResultDialog = false }) {
                    Text(text = "确定")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "测试类型:",
            fontSize = 24.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = testType,
            fontSize = 24.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        
        // Display selected car
        selectedCarModel?.let {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "已选择车型:", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(text = "${it.brand} ${it.name} (${it.year})")
                    if (it.description.isNotEmpty()) {
                        Text(text = it.description, fontSize = 14.sp, color = Color.Gray)
                    }
                }
            }
        }

        // Current Speed Display
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "当前速度:", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(text = String.format("%.1f km/h", currentSpeed), fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
        }

        Text(
            text = "测试结果:",
            fontSize = 24.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = testResult,
            fontSize = 24.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Button(
            onClick = {
                Log.d(TAG, "Start test button clicked, isTestRunning: $isTestRunning, selectedCarModel: $selectedCarModel, isTestReady: $isTestReady")
                isTestRunning = true
            },
            enabled = !isTestRunning && selectedCarModel != null && isTestReady,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text(text = if (isTestReady) "开始测试" else "等待GPS时间...")
        }

        Button(
            onClick = {
                Log.d(TAG, "Stop test button clicked")
                isTestRunning = false
                testResult = "Stopped"
            },
            enabled = isTestRunning,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text(text = "停止测试")
        }
        
        // Change car button
        Button(
            onClick = {
                showCarSelectionDialog = true
            },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        ) {
            Text(text = "更换车型")
        }
        
        // View test records button
        Button(
            onClick = {
                // Navigate to test record list
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "查看测试记录")
        }
    }
}