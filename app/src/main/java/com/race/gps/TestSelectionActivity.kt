package com.race.gps

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.race.gps.data.model.TestRecord
import com.race.gps.viewmodel.MainViewModel

class TestSelectionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set status bar to black/immersive style
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.BLACK

        val deviceName = intent.getStringExtra("device_name")
        val deviceAddress = intent.getStringExtra("device_address")
        
        // Initialize ViewModel for test records
        val mainViewModel = ViewModelProvider(
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
                TestSelectionScreen(
                    deviceName = deviceName,
                    deviceAddress = deviceAddress,
                    mainViewModel = mainViewModel,
                    onTestSelected = { testType ->
                        val intent = Intent(this, TestActivity::class.java)
                        intent.putExtra("test_type", testType)
                        intent.putExtra("device_name", deviceName)
                        intent.putExtra("device_address", deviceAddress)
                        startActivity(intent)
                    }
                )
            }
        }
    }
}

@Composable
fun TestSelectionScreen(
    deviceName: String?,
    deviceAddress: String?,
    mainViewModel: MainViewModel,
    onTestSelected: (String) -> Unit
) {
    val context = LocalContext.current
    
    // Tab state management
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabItems = listOf("加速测试", "刹车测试")
    
    // Get test records from ViewModel
    val testRecords by mainViewModel.testRecords.observeAsState(emptyList())
    
    // Filter test records by current tab
    val filteredRecords = testRecords.filter {
        when (selectedTabIndex) {
            0 -> it.testType.contains("加速") || it.testType.contains("km/h") && !it.testType.contains("-")
            1 -> it.testType.contains("刹车") || it.testType.contains("100-0")
            else -> true
        }
    }
    
    // Custom test dialog state
    var showCustomTestDialog by remember { mutableStateOf(false) }
    var fromSpeed by remember { mutableStateOf("0") }
    var toSpeed by remember { mutableStateOf("100") }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "选择测试类型",
            fontSize = 24.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        )
        
        // Real-time data button with connection status
        Column(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val intent = Intent(context, RealTimeDataActivity::class.java)
                    intent.putExtra("device_name", deviceName)
                    intent.putExtra("device_address", deviceAddress)
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
            ) {
                Text(text = "实时数据")
            }
            Text(
                text = "点击进入实时数据页面，显示GPS卫星信息",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        // Tab Row for switching between acceleration and braking tests
        TabRow(
            selectedTabIndex = selectedTabIndex,
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            tabItems.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(title) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = Color.Gray
                )
            }
        }
        
        // Tab Content with unified background
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 8.dp),
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                when (selectedTabIndex) {
                    0 -> AccelerationTestsTab(
                        onTestSelected = onTestSelected,
                        onCustomTestClick = { showCustomTestDialog = true }
                    )
                    1 -> BrakingTestsTab(
                        onTestSelected = onTestSelected,
                        onCustomTestClick = { showCustomTestDialog = true }
                    )
                }
                
                // History records section
                if (filteredRecords.isNotEmpty()) {
                    Divider(modifier = Modifier.padding(vertical = 16.dp))
                    Text(
                        text = "历史成绩",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        items(filteredRecords) {
                            TestRecordItem(record = it)
                        }
                    }
                }
            }
        }
    }
    
    // Custom test dialog
    if (showCustomTestDialog) {
        AlertDialog(
            onDismissRequest = { showCustomTestDialog = false },
            title = { Text(text = "自定义测试") },
            text = {
                Column {
                    Text(text = "设置测试参数", modifier = Modifier.padding(bottom = 16.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "从", modifier = Modifier.padding(bottom = 8.dp))
                            OutlinedTextField(
                                value = fromSpeed,
                                onValueChange = { fromSpeed = it },
                                label = { Text(text = "起始速度 (km/h)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        
                        Text(text = "→", modifier = Modifier.padding(horizontal = 16.dp, vertical = 32.dp))
                        
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "到", modifier = Modifier.padding(bottom = 8.dp))
                            OutlinedTextField(
                                value = toSpeed,
                                onValueChange = { toSpeed = it },
                                label = { Text(text = "目标速度 (km/h)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val testType = "${fromSpeed}-${toSpeed} km/h"
                    onTestSelected(testType)
                    showCustomTestDialog = false
                    // Reset fields
                    fromSpeed = "0"
                    toSpeed = "100"
                }) {
                    Text(text = "开始测试")
                }
            },
            dismissButton = {
                Button(onClick = {
                    showCustomTestDialog = false
                    // Reset fields
                    fromSpeed = "0"
                    toSpeed = "100"
                }) {
                    Text(text = "取消")
                }
            }
        )
    }
}



@Composable
fun AccelerationTestsTab(
    onTestSelected: (String) -> Unit,
    onCustomTestClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(
            onClick = { onTestSelected("0-100 km/h") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Text(text = "0-100 km/h")
        }

        Button(
            onClick = { onTestSelected("60-160 km/h") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Text(text = "60-160 km/h")
        }

        Button(
            onClick = { onTestSelected("0-400 meters") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Text(text = "0-400米")
        }

        Button(
            onClick = onCustomTestClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Text(text = "自定义加速测试")
        }
    }
}

@Composable
fun BrakingTestsTab(
    onTestSelected: (String) -> Unit,
    onCustomTestClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(
            onClick = { onTestSelected("100-0 km/h") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Text(text = "100-0 km/h")
        }

        Button(
            onClick = onCustomTestClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        ) {
            Text(text = "自定义刹车测试")
        }
    }
}