package com.blazepush.feature.test.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blazepush.core.domain.usecase.SmartTestLauncher
import com.blazepush.core.domain.usecase.SmartTestLauncher.ConditionIcon

/**
 * 智能启动测试页面
 *
 * 交互流程：
 * 1. 进入页面 → 显示5秒倒计时（纯心理暗示，橙色"等待条件就绪"）
 * 2. 倒计时结束 → 显示绿色"条件就绪，可以开始测试了"，隐藏倒计时块
 * 3. 检测到起步条件 → 切换到加速中布局
 */
@Composable
fun SmartLaunchScreen(
    launchStatus: SmartTestLauncher.LaunchStatus,
    countdownSeconds: Int,
    onStartClicked: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 标题
        Text(
            text = "启动准备",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        // 条件卡片
        ConditionCard(launchStatus)

        Spacer(modifier = Modifier.height(16.dp))

        // 状态区域
        StatusSection(
            countdownSeconds = countdownSeconds,
            isReady = launchStatus.canLaunch
        )

        Spacer(modifier = Modifier.weight(1f))

        // 取消按钮
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("取消")
        }
    }
}

@Composable
private fun ConditionCard(launchStatus: SmartTestLauncher.LaunchStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "启动条件",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            launchStatus.conditions.forEach { condition ->
                ConditionRow(condition)
            }
        }
    }
}

@Composable
private fun ConditionRow(condition: SmartTestLauncher.LaunchCondition) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (icon, color) = when (condition.icon) {
            ConditionIcon.CHECKED -> "\u2713" to Color(0xFF4CAF50)
            ConditionIcon.WAITING -> "\u23F3" to Color(0xFFFF9800)
            ConditionIcon.ERROR -> "\u2717" to Color(0xFFF44336)
        }

        Text(text = icon, fontSize = 16.sp, color = color)
        Text(text = condition.name, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(text = condition.description, fontSize = 12.sp, color = Color.Gray)
    }
}

/**
 * 状态区域
 *
 * 倒计时中：橙色卡片，显示"等待条件就绪" + 倒计时数字
 * 就绪后：绿色卡片，显示"条件就绪，可以开始测试了"，无倒计时
 */
@Composable
private fun StatusSection(
    countdownSeconds: Int,
    isReady: Boolean
) {
    if (countdownSeconds > 0) {
        // 倒计时阶段：橙色卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFFF9800).copy(alpha = 0.15f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "\u23F3 等待条件就绪",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFFF9800)
                )

                Text(
                    text = countdownSeconds.toString(),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF9800)
                )
            }
        }
    } else {
        // 就绪阶段：绿色卡片，无倒计时
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF4CAF50).copy(alpha = 0.15f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "\u2705",
                    fontSize = 48.sp
                )

                Text(
                    text = "条件就绪，可以开始测试了",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50),
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "开始加速即可自动触发测试",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
