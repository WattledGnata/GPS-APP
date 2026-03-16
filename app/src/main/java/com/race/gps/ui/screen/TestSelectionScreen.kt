package com.race.gps.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.race.gps.domain.model.TestTemplate
import com.race.gps.viewmodel.GpsDataViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * 测试类型选择页面
 */
@Composable
fun TestSelectionScreen(
    onTestSelected: (TestTemplate, String) -> Unit,
    gpsDataViewModel: GpsDataViewModel = koinViewModel()
) {
    val gpsData by gpsDataViewModel.gpsData.collectAsState()
    var selectedTemplate by remember { mutableStateOf<TestTemplate?>(null) }
    var carModelInput by remember { mutableStateOf("") }
    var showCarInput by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("选择测试类型", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        // 加速测试卡片
        TestTypeCard(
            template = TestTemplate.Acceleration0To100,
            isSelected = selectedTemplate is TestTemplate.Acceleration0To100,
            bestResult = null, // TODO: 从历史记录获取
            onClick = { selectedTemplate = TestTemplate.Acceleration0To100 }
        )

        // 刹车测试卡片
        TestTypeCard(
            template = TestTemplate.Braking100To0,
            isSelected = selectedTemplate is TestTemplate.Braking100To0,
            bestResult = null,
            onClick = { selectedTemplate = TestTemplate.Braking100To0 }
        )

        Divider()

        // 车型输入
        Text("车型", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        OutlinedTextField(
            value = carModelInput,
            onValueChange = { carModelInput = it },
            label = { Text("输入车型（如：特斯拉 Model 3）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.weight(1f))

        // 开始按钮
        Button(
            onClick = {
                val template = selectedTemplate ?: return@Button
                val carModel = carModelInput.ifBlank { "未知车型" }
                onTestSelected(template, carModel)
            },
            enabled = selectedTemplate != null,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("进入测试 →", fontSize = 16.sp)
        }
    }
}

@Composable
private fun TestTypeCard(
    template: TestTemplate,
    isSelected: Boolean,
    bestResult: String?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) CardDefaults.outlinedCardBorder() else null
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(template.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (isSelected) {
                    Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            Text(template.description, fontSize = 14.sp, color = Color.Gray)
            bestResult?.let {
                Text("最佳: $it", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }
        }
    }
}
