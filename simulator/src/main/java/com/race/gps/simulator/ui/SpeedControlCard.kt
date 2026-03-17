package com.race.gps.simulator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.race.gps.simulator.data.SpeedMode
import com.race.gps.simulator.data.getDisplayName
import com.race.gps.simulator.data.getDescription

/**
 * 速度控制面板
 */
@Composable
fun SpeedControlCard(
    mode: SpeedMode,
    targetSpeed: Float,
    acceleration: Float,
    currentSpeed: Float,
    status: String,
    onModeChange: (SpeedMode) -> Unit,
    onTargetSpeedChange: (Float) -> Unit,
    onAccelerationChange: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "速度控制",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // 当前速度显示
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "当前速度",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${"%.1f".format(currentSpeed)} km/h",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 状态描述
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Divider()

            // 速度模式选择
            Text(
                text = "速度模式",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            SpeedMode.values().forEach { speedMode ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = speedMode == mode,
                        onClick = { onModeChange(speedMode) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = speedMode.getDisplayName(),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = speedMode.getDescription(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Divider()

            // 目标速度设置
            Text(
                text = "目标速度: ${"%.0f".format(targetSpeed)} km/h",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Slider(
                value = targetSpeed,
                onValueChange = { onTargetSpeedChange(it) },
                valueRange = 0f..300f,
                steps = 300
            )

            // 加速度设置（仅在加速/减速模式显示）
            if (mode == SpeedMode.ACCELERATION || mode == SpeedMode.DECELERATION) {
                Text(
                    text = "加速度: ${"%.1f".format(acceleration)} m/s²",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = acceleration,
                    onValueChange = { onAccelerationChange(it) },
                    valueRange = 0.5f..10f,
                    steps = 19
                )
            }
        }
    }
}
