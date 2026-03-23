package com.blazepush.feature.test.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blazepush.core.domain.model.DataQuality
import com.blazepush.core.domain.model.QualityLevel
import com.blazepush.core.domain.model.SignalStrength
import com.blazepush.core.domain.usecase.DataQualityEvaluator

/**
 * 数据质量卡片
 * 显示GPS数据质量的综合评估和各项指标
 */
@Composable
fun DataQualityCard(
    quality: DataQuality,
    evaluator: DataQualityEvaluator = DataQualityEvaluator(),
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "数据质量",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                // 综合质量徽章
                QualityBadge(
                    level = quality.overall,
                    score = quality.overallScore,
                    evaluator = evaluator
                )
            }

            // 指标网格
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QualityIndicator(
                    label = "卫星",
                    value = "${quality.satelliteCount}/12",
                    modifier = Modifier.weight(1f)
                )
                QualityIndicator(
                    label = "HDOP",
                    value = String.format("%.1f", quality.hdop),
                    modifier = Modifier.weight(1f)
                )
                QualityIndicator(
                    label = "VDOP",
                    value = String.format("%.1f", quality.vdop),
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QualityIndicator(
                    label = "信号",
                    value = getSignalStrengthText(quality.signalStrength),
                    modifier = Modifier.weight(1f)
                )
                QualityIndicator(
                    label = "丢包",
                    value = "${quality.packetLoss.toInt()}%",
                    modifier = Modifier.weight(1f)
                )
                QualityIndicator(
                    label = "频率",
                    value = "${quality.frequency.toInt()}Hz",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun QualityBadge(
    level: QualityLevel,
    score: Int,
    evaluator: DataQualityEvaluator
) {
    val baseColor = when (level) {
        QualityLevel.EXCELLENT -> Color(0xFF00FF78)
        QualityLevel.GOOD -> Color(0xFF4CAF50)
        QualityLevel.FAIR -> Color(0xFFFF9800)
        QualityLevel.POOR -> Color(0xFFF44336)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = baseColor.copy(alpha = 0.2f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = evaluator.getQualityDescription(level),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = baseColor
            )
            Text(
                text = "($score)",
                fontSize = 12.sp,
                color = baseColor.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun QualityIndicator(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun getSignalStrengthText(strength: SignalStrength): String = when (strength) {
    SignalStrength.EXCELLENT -> "极好"
    SignalStrength.GOOD -> "良好"
    SignalStrength.FAIR -> "一般"
    SignalStrength.POOR -> "较差"
    SignalStrength.NONE -> "无"
}
