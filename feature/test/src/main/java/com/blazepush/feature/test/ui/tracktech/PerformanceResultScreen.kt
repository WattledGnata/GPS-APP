package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blazepush.core.data.local.binary.PerformanceTestTelemetryReader
import com.blazepush.core.data.local.entity.TestRecordEntity
import com.blazepush.core.domain.model.GpsDataPoint
import com.blazepush.core.domain.model.SpeedSegment
import com.blazepush.core.domain.model.TestTemplate
import com.blazepush.feature.test.R
import com.blazepush.feature.test.ui.components.GForceChart
import com.blazepush.feature.test.ui.components.SpeedChart
import com.blazepush.feature.test.viewmodel.TestHistoryViewModel
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Track Tech V2 性能测试结果详情页（加速 0-100 / 制动 100-0）。
 *
 * 视觉契约：
 * - DetailHeader：cut-corner ← back + "PERFORMANCE" 标题
 * - Hero CutCornerPanel：TEST TYPE label (cyan) + 类型大标题 (RacingTitleMedium) + Score Hero 主成绩 (Purple) + Date / Device 副信息
 *   - 主成绩按 TestTemplate 分支：Acceleration0To100 → totalTime + s；Braking100To0 → totalDistance + m
 * - Metric Row：3 个 MetricTile 等分（weight=1f），第 1 格按 template 切 DISTANCE/TIME，第 2/3 格固定 PEAK G / AVG G
 * - SPEED CURVE / G-FORCE 卡：CutCornerPanel 包 SpeedChart/GForceChart（wrapInCard = false 避免双层卡）
 * - SPEED SEGMENTS：cyan section header + 每段 cut-corner row（区间 / 时间 / 距离）
 *
 * @author CC
 * @description V2 visual redesign of performance test result screen
 * @date 2026-05-01
 */
@Composable
fun PerformanceResultScreen(
    testId: String,
    onBack: () -> Unit,
    testHistoryViewModel: TestHistoryViewModel = koinViewModel(),
) {
    val testRecords by testHistoryViewModel.testRecords.collectAsState()
    val record = testRecords.find { it.id == testId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrackTechColors.Background),
    ) {
        PerformanceDetailHeader(onBack = onBack)
        if (record == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = TrackTechColors.Purple)
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

        val template = TestTemplate.fromId(record.testTemplateId)
        val segments = remember(dataPoints, template) {
            if (dataPoints.isEmpty() || template == null) emptyList()
            else calculateSegmentsFromPoints(dataPoints, template)
        }
        // PEAK G 按 testTemplateId 二选一（acc → maxAcceleration、brake → maxDeceleration），
        // V1 brake 记录（maxDeceleration == 0）走 "—" 降级；MetricRow 与 GForceChart Y 轴共用此值。
        val peakG = derivePeakG(record, template)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { HeroSection(record = record, template = template) }
            item { MetricRow(record = record, template = template, peakG = peakG) }
            if (dataPoints.isNotEmpty()) {
                item { SpeedCurveCard(dataPoints = dataPoints) }
                item { GForceCurveCard(dataPoints = dataPoints, maxAcceleration = peakG.gForceChartMaxG) }
            }
            item { SpeedSegmentsHeader() }
            if (segments.isEmpty()) {
                item { EmptySegmentsHint() }
            } else {
                items(segments) { segment -> SegmentRow(segment = segment) }
            }
        }
    }
}

@Composable
private fun PerformanceDetailHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TrackTechColors.Surface)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CutCornerPanelShape(cutSize = 6.dp, cutCorners = cutCornersAll))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowBack,
                contentDescription = stringResource(R.string.detail_back),
                tint = TrackTechColors.TextPrimary,
            )
        }
        Spacer(Modifier.size(12.dp))
        Text(
            text = stringResource(R.string.detail_performance),
            style = TrackTechTypography.RacingTitleMedium,
            color = TrackTechColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HeroSection(
    record: TestRecordEntity,
    template: TestTemplate?,
) {
    val typeTitle = when (template) {
        is TestTemplate.Acceleration0To100 -> "0-100 km/h"
        is TestTemplate.Braking100To0 -> "100-0 km/h"
        else -> "—"
    }
    val (heroValue, heroUnit) = deriveHeroPrimary(record, template)
    val dateLabel = remember(record.timestamp) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(record.timestamp))
    }

    CutCornerPanel(
        modifier = Modifier.fillMaxWidth(),
        cutSize = 8.dp,
        cutCorners = cutCornersAll,
        contentPadding = 16.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.detail_test_type),
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Cyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = typeTitle,
                style = TrackTechTypography.RacingTitleMedium,
                color = TrackTechColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            MetricNumber(
                value = heroValue,
                unit = heroUnit,
                size = MetricSize.Hero,
                kind = MetricKind.Score,
                valueColor = TrackTechColors.Purple,
                unitColor = TrackTechColors.TextSecondary,
            )
            Spacer(Modifier.height(4.dp))
            OverviewRow(label = stringResource(R.string.detail_date), value = dateLabel)
            OverviewRow(label = stringResource(R.string.detail_device), value = record.deviceName.ifBlank { "—" })
        }
    }
}

@Composable
private fun MetricRow(
    record: TestRecordEntity,
    template: TestTemplate?,
    peakG: PeakGTile,
) {
    val (firstLabelRes, firstValue, firstUnit) = deriveFirstMetric(record, template)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetricTile(
            label = stringResource(firstLabelRes),
            value = firstValue,
            unit = firstUnit,
            modifier = Modifier.weight(1f),
            accentColor = TrackTechColors.Cyan,
            valueSize = MetricSize.Medium,
            valueKind = MetricKind.Score,
        )
        MetricTile(
            label = stringResource(peakG.labelRes),
            value = peakG.valueText,
            unit = peakG.unit,
            status = if (peakG.isLegacyRecord) stringResource(R.string.detail_legacy_record) else null,
            modifier = Modifier.weight(1f),
            accentColor = TrackTechColors.Red,
            valueSize = MetricSize.Medium,
            valueKind = MetricKind.Score,
        )
        MetricTile(
            label = stringResource(R.string.detail_avg_g),
            value = "%.2f".format(record.avgAcceleration),
            unit = "G",
            modifier = Modifier.weight(1f),
            accentColor = TrackTechColors.TextSecondary,
            // 跟同行 DISTANCE/TIME + PEAK G 保持 Medium，避免 tile 高度不齐；
            // "弱化"语义靠 accentColor = TextSecondary 的 muted 色已足够。
            valueSize = MetricSize.Medium,
            valueKind = MetricKind.Score,
        )
    }
}

@Composable
private fun SpeedCurveCard(dataPoints: List<GpsDataPoint>) {
    CutCornerPanel(
        modifier = Modifier.fillMaxWidth(),
        cutSize = 8.dp,
        cutCorners = cutCornersAll,
        contentPadding = 12.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.records_speed_curve),
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Cyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SpeedChart(
                dataPoints = dataPoints,
                modifier = Modifier.fillMaxWidth(),
                wrapInCard = false,
            )
        }
    }
}

@Composable
private fun GForceCurveCard(
    dataPoints: List<GpsDataPoint>,
    maxAcceleration: Double,
) {
    CutCornerPanel(
        modifier = Modifier.fillMaxWidth(),
        cutSize = 8.dp,
        cutCorners = cutCornersAll,
        contentPadding = 12.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.detail_g_force),
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Cyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            GForceChart(
                dataPoints = dataPoints,
                maxAcceleration = maxAcceleration,
                modifier = Modifier.fillMaxWidth(),
                wrapInCard = false,
            )
        }
    }
}

@Composable
private fun SpeedSegmentsHeader() {
    Text(
        text = stringResource(R.string.detail_speed_segments),
        style = TrackTechTypography.UiTextLabel,
        color = TrackTechColors.Cyan,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun EmptySegmentsHint() {
    Text(
        text = stringResource(R.string.detail_no_segments),
        style = TrackTechTypography.UiTextSmall,
        color = TrackTechColors.TextMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SegmentRow(segment: SpeedSegment) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CutCornerPanelShape(cutSize = 6.dp, cutCorners = cutCornersAll))
            .background(TrackTechColors.Surface)
            .border(
                width = 1.dp,
                color = TrackTechColors.BorderAlpha60,
                shape = CutCornerPanelShape(cutSize = 6.dp, cutCorners = cutCornersAll),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${segment.startSpeed}–${segment.endSpeed} km/h",
            style = TrackTechTypography.UiTextLabel,
            color = TrackTechColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(96.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "%.3f s".format(segment.time),
            style = TrackTechTypography.UiTextBody,
            color = TrackTechColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "%.1f m".format(segment.distance),
            style = TrackTechTypography.UiTextBody,
            color = TrackTechColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OverviewRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = TrackTechTypography.UiTextLabel,
            color = TrackTechColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = value,
            style = TrackTechTypography.UiTextBody,
            color = TrackTechColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun deriveHeroPrimary(
    record: TestRecordEntity,
    template: TestTemplate?,
): Pair<String, String?> = when (template) {
    is TestTemplate.Acceleration0To100 -> "%.2f".format(record.totalTime) to "s"
    is TestTemplate.Braking100To0 -> "%.1f".format(record.totalDistance) to "m"
    else -> record.result.ifBlank { "—" } to null
}

private fun deriveFirstMetric(
    record: TestRecordEntity,
    template: TestTemplate?,
): Triple<Int, String, String> = when (template) {
    is TestTemplate.Braking100To0 -> Triple(R.string.detail_time, "%.2f".format(record.totalTime), "s")
    // Acceleration0To100 + 未知模板都退到 DISTANCE 显示，避免空白
    else -> Triple(R.string.detail_distance_metric, "%.1f".format(record.totalDistance), "m")
}

/**
 * 按测试模板二选一渲染 PEAK G tile（round smooth-perftest-acceleration-curve §4）：
 * - acc_0_100 → PEAK ACCEL G，值 = record.maxAcceleration
 * - brake_100_0 + maxDeceleration > 0 → PEAK BRAKE G，值 = record.maxDeceleration
 * - brake_100_0 + maxDeceleration == 0（V1 / 异常数据）→ "—" + 副标 "V1 record"，
 *   **MUST NOT fallback 到 maxAcceleration**（V1 abs 污染语义错位会把刹车 G 显示为加速 G）
 *
 * `gForceChartMaxG` 同时作为 GForceChart Y 轴 maxG 限定值传入，保持 metric tile 数字与曲线 Y 轴一致。
 *
 * `internal` 暴露给 `DerivePeakGTest` 单测断言四个分支（spec.md Requirement 4 全部 scenarios）。
 */
internal data class PeakGTile(
    val labelRes: Int,
    val valueText: String,
    val unit: String?,
    val gForceChartMaxG: Double,
    /** 旧版制动记录缺峰值数据时，UI 显式显示本地化降级副标。 */
    val isLegacyRecord: Boolean = false,
)

internal fun derivePeakG(
    record: TestRecordEntity,
    template: TestTemplate?,
): PeakGTile = when (template) {
    is TestTemplate.Acceleration0To100 -> PeakGTile(
        labelRes = R.string.detail_peak_accel_g,
        valueText = "%.2f".format(record.maxAcceleration),
        unit = "G",
        gForceChartMaxG = record.maxAcceleration,
    )
    is TestTemplate.Braking100To0 -> if (record.maxDeceleration > 0.0) {
        PeakGTile(
            labelRes = R.string.detail_peak_brake_g,
            valueText = "%.2f".format(record.maxDeceleration),
            unit = "G",
            gForceChartMaxG = record.maxDeceleration,
        )
    } else {
        PeakGTile(
            labelRes = R.string.detail_peak_brake_g,
            valueText = "—",
            unit = null,
            gForceChartMaxG = 0.0,
            isLegacyRecord = true,
        )
    }
    else -> PeakGTile(
        labelRes = R.string.detail_peak_g,
        valueText = "%.2f".format(record.maxAcceleration),
        unit = "G",
        gForceChartMaxG = record.maxAcceleration,
    )
}

private fun calculateSegmentsFromPoints(
    dataPoints: List<GpsDataPoint>,
    template: TestTemplate,
): List<SpeedSegment> {
    if (dataPoints.isEmpty()) return emptyList()
    return when (template) {
        is TestTemplate.Acceleration0To100 -> {
            (0..80 step 10).map { startSpeed ->
                calculateSegment(dataPoints, startSpeed, startSpeed + 10, ascending = true, isLastSegment = false)
            } + listOf(
                calculateSegment(dataPoints, 90, 100, ascending = true, isLastSegment = true),
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
    dataPoints: List<GpsDataPoint>,
    fromSpeed: Int,
    toSpeed: Int,
    ascending: Boolean,
    isLastSegment: Boolean = false,
): SpeedSegment {
    if (dataPoints.isEmpty()) {
        return SpeedSegment(fromSpeed, toSpeed, 0.0, 0.0)
    }
    val from = fromSpeed.toDouble()
    val to = toSpeed.toDouble()
    val startIdx = dataPoints.indexOfFirst { point ->
        if (ascending) point.speed >= from else point.speed <= from
    }
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
    val time = dataPoints[endIdx].elapsedTime - dataPoints[startIdx].elapsedTime
    val distance = calculateSegmentDistance(dataPoints.subList(startIdx, endIdx + 1))
    return SpeedSegment(
        startSpeed = fromSpeed,
        endSpeed = toSpeed,
        time = time,
        distance = distance,
    )
}

private fun calculateSegmentDistance(dataPoints: List<GpsDataPoint>): Double {
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
                results,
            )
            total += results[0]
        }
    }
    return total
}
