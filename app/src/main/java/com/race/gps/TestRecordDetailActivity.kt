package com.race.gps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import com.race.gps.viewmodel.MainViewModel

class TestRecordDetailActivity : ComponentActivity() {
    private lateinit var mainViewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mainViewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[MainViewModel::class.java]

        val recordId = intent.getStringExtra("record_id")

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
                TestRecordDetailScreen(recordId = recordId)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestRecordDetailScreen(recordId: String?) {
    // Get the test record by ID (this would typically come from a ViewModel)
    // For now, we'll use mock data
    val testRecord = remember { mutableStateOf(
        com.race.gps.data.model.TestRecord(
            id = recordId ?: "",
            testType = "0-100 km/h",
            carModel = "Tesla Model 3",
            deviceName = "RaceChrono GPS",
            deviceAddress = "00:11:22:33:44:55",
            result = "3.14 seconds"
        )
    )}

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = "测试成绩详情")
                },
                navigationIcon = {
                    IconButton(onClick = { /* Handle back button */ } ) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) {
        Column(modifier = Modifier.padding(it).padding(16.dp)) {
            Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(8.dp)) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "测试类型",
                            fontSize = 16.sp,
                            color = Color.Gray
                        )
                        Text(
                            text = testRecord.value.testType,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "车型",
                            fontSize = 16.sp,
                            color = Color.Gray
                        )
                        Text(
                            text = testRecord.value.carModel,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "成绩",
                            fontSize = 16.sp,
                            color = Color.Gray
                        )
                        Text(
                            text = testRecord.value.result,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "设备名称",
                            fontSize = 16.sp,
                            color = Color.Gray
                        )
                        Text(
                            text = testRecord.value.deviceName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "设备地址",
                            fontSize = 16.sp,
                            color = Color.Gray
                        )
                        Text(
                            text = testRecord.value.deviceAddress,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "测试时间",
                            fontSize = 16.sp,
                            color = Color.Gray
                        )
                        Text(
                            text = testRecord.value.timestamp.toString(),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { /* Handle share */ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "分享成绩")
            }
        }
    }
}