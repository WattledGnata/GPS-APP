package com.blazepush.feature.test.ui.screen
// @IgnoreFormatCheck

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blazepush.core.data.local.binary.PerformanceTestTelemetryReader
import com.blazepush.core.data.local.entity.TestRecordEntity
import com.blazepush.core.domain.model.GpsDataPoint
import com.blazepush.core.domain.model.SpeedSegment
import com.blazepush.core.domain.model.TestTemplate
import com.blazepush.feature.test.ui.components.SpeedChart
import com.blazepush.feature.test.ui.components.GForceChart
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 测试结果详情页面
 */
@Composable
fun TestResultScreen(
    testId: String,
    onBack: () -> Unit,
    testHistoryViewModel: com.blazepush.feature.test.viewmodel.TestHistoryViewModel = koinViewModel(),
) {
    val testRecords by testHistoryViewModel.testRecords.collectAsState()
    val record = testRecords.find { it.id == testId }

    if (record == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val dataPoints = remember(record.dataFilePath) {
        PerformanceTestTelemetryReader.read(record.dataFilePath).map { sample ->
            GpsDataPoint(
                elapsedTime = sample.tsDeltaMs / 1000.0,
                speed = sample.speedKmh,
                latitude = sample.lat,
                longitude = sample.lon,
                altitude = 0.0,
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onBack) { Text("← 返回", color = MaterialTheme.colorScheme.primary) }
            }
        }

        // 成绩卡片
        item { ResultSummaryCard(record) }

        // 关键指标
        item { KeyMetricsCard(record) }

        // 速度曲线图
        if (dataPoints.isNotEmpty()) {
            item {
                SpeedChart(
                    dataPoints = dataPoints,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // G值曲线图
        if (dataPoints.isNotEmpty()) {
            item {
                GForceChart(
                    dataPoints = dataPoints,
                    maxAcceleration = record.maxAcceleration,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 分段数据（从���据库加载）
        item {
            Text("速度分段", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
        }

        // TODO: 从数据库加载分段数据
        // 暂时从数据点计算
        if (dataPoints.isNotEmpty()) {
            val template = TestTemplate.fromId(record.testTemplateId)
            if (template != null) {
                val segments = calculateSegmentsFromPoints(dataPoints, template)
                items(segments) { segment ->
                    SegmentRow(segment)
                }
            }
        }
    }
}

@Composable
private fun ResultSummaryCard(record: TestRecordEntity) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(record.testType, fontSize = 16.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                text = if (record.totalTime > 0) String.format("%.2f 秒", record.totalTime)
                else record.result,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(record.carModel, fontSize = 16.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                dateFormat.format(Date(record.timestamp)),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun KeyMetricsCard(record: TestRecordEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("关键指标", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricItem("总时间", String.format("%.2f s", record.totalTime))
                MetricItem("总距离", String.format("%.1f m", record.totalDistance))
                MetricItem("平均G值", String.format("%.2f G", record.avgAcceleration))
                MetricItem("最大G值", String.format("%.2f G", record.maxAcceleration))
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun SegmentRow(segment: SpeedSegment) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                "${segment.startSpeed}-${segment.endSpeed} km/h",
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text(String.format("%.3f s", segment.time), fontWeight = FontWeight.Bold)
                Text(String.format("%.1f m", segment.distance), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// 从数据点计算分段
private fun calculateSegmentsFromPoints(
    dataPoints: List<com.blazepush.core.domain.model.GpsDataPoint>,
    template: TestTemplate
): List<SpeedSegment> {
    if (dataPoints.isEmpty()) return emptyList()

    return when (template) {
        is TestTemplate.Acceleration0To100 -> {
            // 0-10 到 80-90（9段）
            (0..80 step 10).map { startSpeed ->
                calculateSegment(dataPoints, startSpeed, startSpeed + 10, ascending = true, isLastSegment = false)
            } + listOf(
                // 90-100 段（最后一段）
                calculateSegment(dataPoints, 90, 100, ascending = true, isLastSegment = true)
            )
        }
        is TestTemplate.Braking100To0 -> {
            (100 downTo 10 step 10).mapIndexed { index, startSpeed ->
                val isLast = index == 9
                calculateSegment(dataPoints, startSpeed, startSpeed - 10, ascending = false, isLastSegment = isLast)
            }
        }
    }
}

private fun calculateSegment(
    dataPoints: List<com.blazepush.core.domain.model.GpsDataPoint>,
    fromSpeed: Int,
    toSpeed: Int,
    ascending: Boolean,
    isLastSegment: Boolean = false
): SpeedSegment {
    if (dataPoints.isEmpty()) {
        return SpeedSegment(fromSpeed, toSpeed, 0.0, 0.0)
    }

    val from = fromSpeed.toDouble()
    val to = toSpeed.toDouble()

    // 找到第一个速度达到 fromSpeed 的点作为起点
    val startIdx = dataPoints.indexOfFirst { point ->
        if (ascending) point.speed >= from else point.speed <= from
    }
    // 找到终点
    val endIdx = if (isLastSegment) {
        dataPoints.lastIndex
    } else {
        dataPoints.indexOfFirst { point ->
            if (ascending) point.speed >= to else point.speed <= to
        }
    }

    if (startIdx < 0 || endIdx < 0 || startIdx >= endIdx) {
        return SpeedSegment(fromSpeed, toSpeed, 0.0, 0.0)
    }

    val startTime = dataPoints[startIdx].elapsedTime
    val endTime = dataPoints[endIdx].elapsedTime
    val time = endTime - startTime

    // 计算该区间的距离
    val segmentPoints = dataPoints.subList(startIdx, endIdx + 1)
    val distance = calculateSegmentDistance(segmentPoints)

    return SpeedSegment(
        startSpeed = fromSpeed,
        endSpeed = toSpeed,
        time = time,
        distance = distance
    )
}

private fun calculateSegmentDistance(
    dataPoints: List<com.blazepush.core.domain.model.GpsDataPoint>
): Double {
    if (dataPoints.size < 2) return 0.0

    var total = 0.0
    for (i in 1 until dataPoints.size) {
        val prev = dataPoints[i - 1]
        val curr = dataPoints[i]
        if (prev.latitude != 0.0 && curr.latitude != 0.0) {
            val results = FloatArray(1)
            android.location.Location.distanceBetween(
                prev.latitude, prev.longitude,
                curr.latitude, curr.longitude,
                results
            )
            total += results[0]
        }
    }
    return total
}
