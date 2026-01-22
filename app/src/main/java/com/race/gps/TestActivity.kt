package com.race.gps

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import com.race.gps.TestActivity.Companion.TAG
import com.race.gps.data.model.CarModel
import com.race.gps.service.BluetoothManager
import com.race.gps.viewmodel.MainViewModel


class TestActivity : ComponentActivity() {
    companion object {
        const val TAG = "RaceChronoGPS"
    }
    
    private lateinit var mainViewModel: MainViewModel
    private val bluetoothManager = BluetoothManager.getInstance()
    
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
        
        // 绑定到蓝牙服务
        bluetoothManager.bindService(this)
        
        // 连接到蓝牙设备
        bluetoothManager.connectToDevice(deviceAddress)

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
                    mainViewModel = mainViewModel,
                    bluetoothManager = bluetoothManager
                )
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 断开蓝牙连接
        bluetoothManager.disconnect()
        // 取消绑定服务
        bluetoothManager.unbindService(this)
        Log.d(TAG, "Bluetooth service closed")
    }
}

@Composable
@Preview
fun TestScreen(
    testType: String = "Sample Test",
    deviceName: String = "Unknown Device",
    deviceAddress: String = "",
    mainViewModel: MainViewModel = MainViewModel(androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application),
    bluetoothManager: BluetoothManager = BluetoothManager.getInstance()
) {
    val carModels by mainViewModel.carModels.collectAsState(emptyList())
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
    val currentSpeed by mainViewModel.currentSpeed.collectAsState(0.0)
    val isTestRunning by mainViewModel.isTestRunning.collectAsState(false)
    val testResult by mainViewModel.testResult.collectAsState("Not Started")
    
    // Observe bluetooth data from BluetoothManager
    val bluetoothData by bluetoothManager.bluetoothDataFlow.collectAsState()
    
    // Update ViewModel with bluetooth data
    LaunchedEffect(bluetoothData) {
        mainViewModel.updateCurrentSpeed(bluetoothData.speed)
        mainViewModel.updateTestReady(bluetoothData.isTestReady)
    }
    
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

        // Device connection status and satellite count
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "设备状态:", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(text = if (bluetoothData.isConnected) "已连接" else "未连接", color = if (bluetoothData.isConnected) Color.Green else Color.Red)
                Text(text = "卫星数量: ${bluetoothData.satelliteCount}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(text = "GPS就绪: ${if (bluetoothData.isTestReady) "是" else "否"}")
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
                Log.d(TAG, "Start test button clicked, isTestRunning: $isTestRunning, selectedCarModel: $selectedCarModel, isTestReady: ${bluetoothData.isTestReady}")
                selectedCarModel?.let {
                    mainViewModel?.startTest(testType, it)
                }
            },
            enabled = !isTestRunning && selectedCarModel != null && bluetoothData.isTestReady,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text(text = if (bluetoothData.isTestReady) "开始测试" else "等待GPS时间...")
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