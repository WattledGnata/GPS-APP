package com.race.gps

import android.Manifest
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
import com.race.gps.data.service.BluetoothService
import com.race.gps.data.service.impl.BleBluetoothServiceImpl
import com.race.gps.data.service.impl.MockBluetoothServiceImpl
import com.race.gps.viewmodel.MainViewModel
import kotlinx.coroutines.delay


class TestActivity : ComponentActivity() {
    companion object {
        const val TAG = "RaceChronoGPS"
        private const val USE_MOCK_BLUETOOTH = false // 设置为true使用mock蓝牙服务，false使用真实BLE服务
    }
    
    private lateinit var mainViewModel: MainViewModel
    private lateinit var bluetoothService: BluetoothService
    
    // Test state
    private lateinit var testType: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        testType = intent.getStringExtra("test_type") ?: "Unknown Test"
        val deviceName = intent.getStringExtra("device_name") ?: "Unknown Device"
        val deviceAddress = intent.getStringExtra("device_address") ?: ""
        
        Log.d(TAG, "Starting TestActivity with testType: $testType, deviceName: $deviceName, deviceAddress: $deviceAddress")

        mainViewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[MainViewModel::class.java]
        
        // 初始化BluetoothService
        bluetoothService = if (USE_MOCK_BLUETOOTH) {
            Log.d(TAG, "Using Mock Bluetooth Service for testing")
            MockBluetoothServiceImpl()
        } else {
            Log.d(TAG, "Using Real BLE Bluetooth Service")
            BleBluetoothServiceImpl(this)
        }
        
        // 设置蓝牙回调
        bluetoothService.setCallback(object : BluetoothService.BluetoothCallback {
            override fun onConnectionStateChanged(isConnected: Boolean) {
                Log.d(TAG, "Bluetooth connection state changed: $isConnected")
            }
            
            override fun onTestReady(isReady: Boolean) {
                Log.d(TAG, "Test ready state changed: $isReady")
                // Update test ready state in ViewModel
                mainViewModel.updateTestReady(isReady)
            }
            
            override fun onSpeedUpdated(speedKmh: Double) {
                // Update speed in ViewModel
                mainViewModel.updateCurrentSpeed(speedKmh)
            }
            
            override fun onError(errorMessage: String) {
                Log.e(TAG, "Bluetooth error: $errorMessage")
            }
        })
        
        // Connect to BLE device to get GPS time
        bluetoothService.connectToDevice(deviceAddress)

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
                    testType = testType,
                    deviceName = deviceName,
                    deviceAddress = deviceAddress,
                    mainViewModel = mainViewModel
                )
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Close Bluetooth service, release resources
        bluetoothService.close()
        Log.d(TAG, "Bluetooth service closed")
    }
}

@Composable
@Preview
fun TestScreen(
    testType: String = "Sample Test",
    deviceName: String = "Unknown Device",
    deviceAddress: String = "",
    mainViewModel: MainViewModel = MainViewModel(androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application)
) {
    val carModels by mainViewModel.carModels.observeAsState(emptyList())
    var selectedCarModel by remember { mutableStateOf<CarModel?>(null) }
    var showCarSelectionDialog by remember { mutableStateOf(true) }
    var showAddCarDialog by remember { mutableStateOf(false) }
    var newCarBrand by remember { mutableStateOf("") }
    var newCarName by remember { mutableStateOf("") }
    var newCarYear by remember { mutableStateOf("") }
    var newCarDescription by remember { mutableStateOf("") }
    
    //成绩弹窗状态
    var showResultDialog by remember { mutableStateOf(false) }
    var lastTestResult by remember { mutableStateOf("0.0") }
    
    // Observe ViewModel state
    val currentSpeed by mainViewModel.currentSpeed.observeAsState(0.0)
    val isTestRunning by mainViewModel.isTestRunning.observeAsState(false)
    val testResult by mainViewModel.testResult.observeAsState("Not Started")
    
    // Observe ViewModel state for isTestReady
    val isTestReady by mainViewModel.isTestReady.observeAsState(false)
    
    // Get current context at composable level
    val context = androidx.compose.ui.platform.LocalContext.current
    
    // Show result dialog when test completes
    LaunchedEffect(testResult) {
        if (testResult != "Not Started" && testResult != "Running..." && testResult != "Stopped" && testResult != "等待起始速度...") {
            lastTestResult = testResult
            showResultDialog = true
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
        Log.d(TAG, "Showing test result dialog with result: $lastTestResult")
        AlertDialog(
            onDismissRequest = { showResultDialog = false },
            title = { Text(text = "测试完成！") },
            text = {
                Column {
                    Text(text = "测试类型: $testType", fontSize = 18.sp, modifier = Modifier.padding(bottom = 16.dp))
                    Text(text = "成绩: $lastTestResult", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
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
                selectedCarModel?.let {
                    mainViewModel?.startTest(testType, it)
                }
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
                mainViewModel?.stopTest()
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
                val intent = android.content.Intent(context, TestRecordListActivity::class.java)
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "查看测试记录")
        }
    }
}