package com.race.gps.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.race.gps.domain.usecase.SmartTestLauncher
import com.race.gps.domain.usecase.SmartTestLauncher.ConditionIcon

/**
 * 智能启动测试页面
 * 显示启动条件状态和倒计时，条件就绪后用户手动开始
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
            fontWeight = FontWeight.Bold
        )

        // 条件卡片
        ConditionCard(launchStatus)

        Spacer(modifier = Modifier.height(16.dp))

        // 倒计时/状态区域
        CountdownSection(
            countdownSeconds = countdownSeconds,
            canStart = launchStatus.canLaunch,
            onStartClicked = onStartClicked
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

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 总体状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (launchStatus.canLaunch) "✅ 所有条件已满足" else "⏳ 等待条件满足...",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (launchStatus.canLaunch) Color(0xFF4CAF50) else Color(0xFFFF9800)
                )
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

@Composable
private fun CountdownSection(
    countdownSeconds: Int,
    canStart: Boolean,
    onStartClicked: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (countdownSeconds > 0 && canStart) {
                MaterialTheme.colorScheme.primaryContainer
            } else if (countdownSeconds == 0 && canStart) {
                Color(0xFF4CAF50).copy(alpha = 0.1f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when {
                countdownSeconds > 0 -> {
                    // 倒计时显示
                    Text(
                        text = countdownSeconds.toString(),
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "条件满足，请保持当前速度",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
                canStart -> {
                    // 就绪状态 - 速度已在起点范围
                    Text(
                        text = "\u2705",
                        fontSize = 48.sp
                    )
                    Text(
                        text = "准备就绪！当前速度已达到起点条件",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF4CAF50),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "测试将在速度变化时自动触发",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
                else -> {
                    // 等待条件
                    Text(
                        text = "\u23F3",
                        fontSize = 48.sp
                    )
                    Text(
                        text = "等待所有条件满足...",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}
