package com.race.gps

import android.os.Bundle
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
import androidx.lifecycle.ViewModelProvider
import com.race.gps.data.model.CarModel
import com.race.gps.data.model.TestRecord
import com.race.gps.viewmodel.MainViewModel
import kotlinx.coroutines.delay

class TestActivity : ComponentActivity() {
    private lateinit var mainViewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val testType = intent.getStringExtra("test_type")
        val deviceName = intent.getStringExtra("device_name")
        val deviceAddress = intent.getStringExtra("device_address")

        mainViewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[MainViewModel::class.java]

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
                    mainViewModel = mainViewModel
                )
            }
        }
    }
}

@Composable
@Preview
fun TestScreen(
    testType: String = "Sample Test",
    deviceName: String = "Unknown Device",
    deviceAddress: String = "",
    mainViewModel: MainViewModel? = null
) {
    val carModels by mainViewModel?.carModels?.observeAsState(emptyList()) ?: remember { mutableStateOf(emptyList()) }
    var selectedCarModel by remember { mutableStateOf<CarModel?>(null) }
    var showCarSelectionDialog by remember { mutableStateOf(true) }
    var showAddCarDialog by remember { mutableStateOf(false) }
    var newCarBrand by remember { mutableStateOf("") }
    var newCarName by remember { mutableStateOf("") }
    var newCarYear by remember { mutableStateOf("") }
    var newCarDescription by remember { mutableStateOf("") }
    
    // Test state variables
    var testResult by remember { mutableStateOf("Not Started") }
    var isTestRunning by remember { mutableStateOf(false) }
    var currentSpeed by remember { mutableStateOf(0.0) }
    var startTime by remember { mutableStateOf(0L) }
    var endTime by remember { mutableStateOf(0L) }
    var testCompleted by remember { mutableStateOf(false) }
    
    //成绩弹窗状态
    var showResultDialog by remember { mutableStateOf(false) }
    var lastTestResult by remember { mutableStateOf("0.0") }
    var dialogCountdown by remember { mutableStateOf(5) }
    
    //实际应从BLE接收速度数据，此处暂时移除模拟数据
    LaunchedEffect(isTestRunning) {
        if (isTestRunning) {
            startTime = System.currentTimeMillis()
            testResult = "Running..."
            testCompleted = false
            
            //等待真实GPS数据（此处应替换为BLE数据接收逻辑）
            //暂时不使用模拟数据，需要等待真实BLE数据
            //实际应用中，应连接到BLE设备，接收真实GPS数据
            
            //此处可以添加GPS信号检测逻辑
            //如果没有GPS信号，显示错误信息
            
            //示例：等待用户手动停止测试
            //实际应用中，应根据真实GPS数据自动检测测试完成
        }
    }
    
    //弹窗倒计时
    LaunchedEffect(showResultDialog) {
        if (showResultDialog) {
            dialogCountdown = 5
            repeat(5) {
                delay(1000)
                dialogCountdown--
            }
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
                isTestRunning = true
            },
            enabled = !isTestRunning && selectedCarModel != null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text(text = "开始测试")
        }

        Button(
            onClick = {
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