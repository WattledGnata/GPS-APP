package com.race.gps.ui.screen

import androidx.compose.foundation.layout.*
import kotlinx.coroutines.delay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.race.gps.domain.model.TestState
import com.race.gps.domain.model.TestTemplate
import com.race.gps.viewmodel.GpsDataViewModel
import com.race.gps.viewmodel.TestSessionViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * 测试执行页面
 * 显示实时速度、测试状态和进度
 */
@Composable
fun TestExecutionScreen(
    onTestCompleted: (String) -> Unit,
    onCancel: () -> Unit,
    gpsDataViewModel: GpsDataViewModel = koinViewModel(),
    testSessionViewModel: TestSessionViewModel = koinViewModel()
) {
    val gpsData by gpsDataViewModel.gpsData.collectAsState()
    val testState by testSessionViewModel.testState.collectAsState()
    val launchStatus by testSessionViewModel.launchStatus.collectAsState()
    val countdownSeconds by testSessionViewModel.countdownSeconds.collectAsState()

    // 测试完成时跳转
    LaunchedEffect(testState) {
        if (testState is TestState.Completed) {
            val result = (testState as TestState.Completed).result
            onTestCompleted(result.id)
        }
    }

    // Preparing 状态显示智能启动检查界面
    when (val state = testState) {
        is TestState.Preparing -> {
            SmartLaunchScreen(
                launchStatus = launchStatus,
                countdownSeconds = countdownSeconds,
                onStartClicked = { testSessionViewModel.skipCountdown() },
                onCancel = {
                    testSessionViewModel.cancelTest()
                    onCancel()
                }
            )
            return
        }
        else -> { /* 继续显示正常测试界面 */ }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 标题
        val titleText = when (val state = testState) {
            is TestState.Preparing -> state.template.name
            is TestState.Running -> state.session.template.name
            else -> "测试"
        }
        Text(
            titleText,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        // 状态提示
        StatusBanner(testState)

        // 实时速度
        SpeedDisplay(gpsData.speed, testState)

        // 进度条
        ProgressBar(gpsData.speed, testState)

        // GPS信号
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("卫星", fontSize = 12.sp, color = Color.Gray)
                    Text("${gpsData.satelliteCount}", fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("频率", fontSize = 12.sp, color = Color.Gray)
                    Text("${gpsData.frequency}Hz", fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("HDOP", fontSize = 12.sp, color = Color.Gray)
                    Text(String.format("%.1f", gpsData.hdop), fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // 取消按钮
        OutlinedButton(
            onClick = {
                testSessionViewModel.cancelTest()
                onCancel()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("取消测试")
        }
    }
}

@Composable
private fun StatusBanner(testState: TestState) {
    // Running状态时使用定时器持续更新elapsed time
    var elapsed by remember { mutableStateOf(0.0) }

    LaunchedEffect(testState) {
        if (testState is TestState.Running) {
            val startTime = testState.session.startTime
            while (true) {
                elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                delay(100) // 每100ms更新一次
            }
        }
    }

    val (text, color) = when (testState) {
        is TestState.Preparing -> {
            val hint = when (testState.template) {
                is TestTemplate.Acceleration0To100 -> "等待GPS数据就绪..."
                is TestTemplate.Braking100To0 -> "等待GPS数据就绪..."
            }
            hint to Color(0xFF2196F3)
        }
        is TestState.Running -> {
            val elapsedStr = if (elapsed < 10) {
                String.format("%.2f", elapsed)
            } else {
                String.format("%.1f", elapsed)
            }
            "测试进行中... ${elapsedStr}s" to Color(0xFF4CAF50)
        }
        else -> "准备中..." to Color.Gray
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SpeedDisplay(speed: Double, testState: TestState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("当前速度", fontSize = 14.sp, color = Color.Gray)
            Text(
                text = String.format("%.1f", speed),
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                color = if (testState is TestState.Running) MaterialTheme.colorScheme.primary else Color.Black
            )
            Text("km/h", fontSize = 18.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun ProgressBar(speed: Double, testState: TestState) {
    val template = when (testState) {
        is TestState.Preparing -> testState.template
        is TestState.Running -> testState.session.template
        else -> return
    }

    val progress = when (template) {
        is TestTemplate.Acceleration0To100 -> (speed / 100.0).coerceIn(0.0, 1.0).toFloat()
        is TestTemplate.Braking100To0 -> (1.0 - speed / 100.0).coerceIn(0.0, 1.0).toFloat()
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${template.startSpeed} km/h", fontSize = 12.sp, color = Color.Gray)
            Text("${template.endSpeed} km/h", fontSize = 12.sp, color = Color.Gray)
        }
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxWidth().height(8.dp)
        )
    }
}
