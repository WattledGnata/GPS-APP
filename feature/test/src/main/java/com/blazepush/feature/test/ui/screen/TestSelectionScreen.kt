package com.blazepush.feature.test.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blazepush.core.domain.model.TestTemplate

/**
 * 测试类型选择页面
 */
@Composable
fun TestSelectionScreen(
    onTestSelected: (TestTemplate, String) -> Unit,
    onLapDebugClick: () -> Unit,
    onHistoryClick: () -> Unit = {}
) {
    var selectedTemplate by remember { mutableStateOf<TestTemplate?>(null) }
    var carModelInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "选择测试类型",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        // 加速测试卡片
        TestTypeCard(
            title = TestTemplate.Acceleration0To100.name,
            description = TestTemplate.Acceleration0To100.description,
            isSelected = selectedTemplate is TestTemplate.Acceleration0To100,
            bestResult = null,
            onClick = { selectedTemplate = TestTemplate.Acceleration0To100 }
        )

        // 刹车测试卡片
        TestTypeCard(
            title = TestTemplate.Braking100To0.name,
            description = TestTemplate.Braking100To0.description,
            isSelected = selectedTemplate is TestTemplate.Braking100To0,
            bestResult = null,
            onClick = { selectedTemplate = TestTemplate.Braking100To0 }
        )

        Divider()

        TestTypeCard(
            title = "圈速调试",
            description = "选择赛道并设置轨迹/计时门显示选项",
            isSelected = false,
            bestResult = null,
            onClick = onLapDebugClick
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

        // 历史记录按钮
        OutlinedButton(
            onClick = onHistoryClick,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("查看历史记录", fontSize = 14.sp)
        }
    }
}

@Composable
private fun TestTypeCard(
    title: String,
    description: String,
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
                Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (isSelected) {
                    Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            Text(description, fontSize = 14.sp, color = Color.Gray)
            bestResult?.let {
                Text("最佳: $it", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }
        }
    }
}
