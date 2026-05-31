package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.blazepush.core.data.local.binary.PerformanceTestTelemetryReader
import com.blazepush.core.domain.model.TelemetrySession
import com.blazepush.core.domain.model.TestResultSummary
import com.blazepush.feature.test.model.track.Track
import com.blazepush.feature.test.ui.tracktech.components.DeleteCandidate
import com.blazepush.feature.test.ui.tracktech.components.DeleteHistoryDialog
import com.blazepush.feature.test.ui.tracktech.format.formatDate
import com.blazepush.feature.test.ui.tracktech.format.formatLapMs
import com.blazepush.feature.test.ui.tracktech.format.formatRunTimestamp
import com.blazepush.feature.test.viewmodel.TestSessionViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun RecordsHomeScreen(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") onTabSelected: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // rememberSaveable：进入 detail 屏后返回，sub-tab 选中态保持。
    var selectedSegment by rememberSaveable { mutableStateOf("PERFORMANCE") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TrackTechColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RecordsTitleRow(context = context)

        SegmentedControl(
            options = listOf("PERFORMANCE", "LAPS"),
            selected = selectedSegment,
            onSelect = { selectedSegment = it },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        when (selectedSegment) {
            "PERFORMANCE" -> PerformanceView(navController = navController)
            "LAPS" -> LapsView(context = context, navController = navController)
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun RecordsTitleRow(context: Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Records",
            style = TrackTechTypography.RacingTitleLarge,
            color = TrackTechColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = Icons.Filled.FilterAlt,
            contentDescription = "Filter",
            tint = TrackTechColors.TextSecondary,
            modifier = Modifier
                .size(24.dp)
                .clickable {
                    Toast.makeText(context, "Filter coming next round", Toast.LENGTH_SHORT).show()
                },
        )
    }
}

// =====================================================================
// PERFORMANCE 视图
// =====================================================================

@Composable
private fun PerformanceView(
    navController: NavController,
    testSessionViewModel: TestSessionViewModel = koinViewModel(),
) {
    val bestAcc by testSessionViewModel.bestAcceleration0To100.collectAsState()
    val bestBrake by testSessionViewModel.bestBraking100To0.collectAsState()
    val totalRuns by testSessionViewModel.totalRunCount.collectAsState()
    val recent by testSessionViewModel.recentRuns.collectAsState()
    // round add-history-deletion §8.1：长按删除候选；本 round 用普通 remember
    // （配置变化丢 state 是已知 trade-off，rememberSaveable 留 follow-up §12.4）
    var deleteCandidate by remember { mutableStateOf<DeleteCandidate?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetricTile(
                label = "BEST 0-100",
                value = bestAcc?.let { "%.2f".format(it.totalTime) } ?: "--",
                unit = "s",
                accentColor = TrackTechColors.Purple,
                valueSize = MetricSize.Medium,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "BEST BRAKE",
                value = bestBrake?.let { "%.1f".format(it.totalDistance) } ?: "--",
                unit = "m",
                accentColor = TrackTechColors.Red,
                valueSize = MetricSize.Medium,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "TOTAL RUNS",
                value = totalRuns.toString(),
                accentColor = TrackTechColors.Cyan,
                valueSize = MetricSize.Medium,
                modifier = Modifier.weight(1f),
            )
        }

        SpeedCurveSection(bestAcc = bestAcc)
    }

    Spacer(Modifier.height(4.dp))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "RECENT RUNS",
            style = TrackTechTypography.UiTextLabel,
            color = TrackTechColors.Cyan,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (recent.isEmpty()) {
            Text(
                text = "No runs yet.",
                style = TrackTechTypography.UiTextSmall,
                color = TrackTechColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            recent.forEach { result ->
                val isPB = result.id == bestAcc?.id || result.id == bestBrake?.id
                val (leadingIcon, title, subtitle) = recentRunRowContent(result, isPB)
                TrackTechRow(
                    leadingIcon = leadingIcon,
                    title = title,
                    subtitle = subtitle,
                    onClick = {
                        navController.navigate("performance_result/${result.id}")
                    },
                    onLongClick = {
                        deleteCandidate = DeleteCandidate.TestRecord(
                            id = result.id,
                            titleHint = formatPerfDeleteHint(result),
                        )
                    },
                )
            }
        }
    }

    deleteCandidate?.let { candidate ->
        DeleteHistoryDialog(
            candidate = candidate,
            onConfirm = {
                if (candidate is DeleteCandidate.TestRecord) {
                    testSessionViewModel.deleteTestRecord(candidate.id)
                }
                deleteCandidate = null
            },
            onDismiss = { deleteCandidate = null },
        )
    }
}

private fun recentRunRowContent(
    result: TestResultSummary,
    isPB: Boolean,
): Triple<ImageVector, String, String> {
    val type = when (result.testTemplateId) {
        "acc_0_100" -> "0-100 km/h"
        "brake_100_0" -> "100-0 km/h"
        else -> result.testTemplateId
    }
    val value = when (result.testTemplateId) {
        "acc_0_100" -> "%.2f s".format(result.totalTime)
        "brake_100_0" -> "%.1f m".format(result.totalDistance)
        else -> "—"
    }
    val time = formatRunTimestamp(result.timestamp)
    val icon = when {
        isPB -> Icons.Filled.EmojiEvents
        result.testTemplateId == "acc_0_100" -> Icons.Filled.Speed
        else -> Icons.Outlined.DoNotDisturbOn
    }
    val subtitle = if (isPB) "$value · $time · Personal Best" else "$value · $time"
    return Triple(icon, type, subtitle)
}

/**
 * SPEED CURVE 卡片：消费 BEST 0-100 record 的 dataFilePath → PerformanceTestTelemetryReader.read()
 * 出真实速度曲线 + 找首个 speed >= 100 km/h 的点作 100 km/h 标注；
 * 无 best record / binary 读不出时 fallback 到 muted 占位。
 *
 * round redesign-performance-result-screen 替代了原 SpeedCurveStub 硬编码假曲线（150 * exp(-1.4t*5)）+
 * "4.21 s / 100 km/h" 字面量。dataPoints 已由 A56 round（unify-gps-telemetry-persistence）持久化到
 * dataFilePath 指向的 binary chunk，无需新增 schema 迁移。
 */
@Composable
private fun SpeedCurveSection(bestAcc: TestResultSummary?) {
    val curve = produceCurveSamples(bestAcc)
    CutCornerPanel(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        cutSize = 12.dp,
        cutCorners = cutCornersDiagonal,
        contentPadding = 16.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "SPEED CURVE",
                    style = TrackTechTypography.UiTextLabel,
                    color = TrackTechColors.Cyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "(0-100 km/h)",
                    style = TrackTechTypography.UiTextSmall,
                    color = TrackTechColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (curve == null || curve.points.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (bestAcc == null) "暂无 0-100 成绩" else "曲线数据不可用",
                        style = TrackTechTypography.UiTextSmall,
                        color = TrackTechColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    SpeedCurveCanvas(curve = curve, modifier = Modifier.fillMaxSize())
                    if (curve.hundredKmhAtSec != null) {
                        SpeedCurveBubble(
                            timeLabel = "%.2f s".format(curve.hundredKmhAtSec),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 8.dp, end = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Speed curve 的 UI 派生数据：xy 列表 + 100 km/h 命中时间（若达到）+ 轴上限。 */
private data class SpeedCurveData(
    val points: List<Pair<Float, Float>>, // (elapsedSec, speedKmh)
    val maxSec: Float,
    val maxSpeedKmh: Float,
    val hundredKmhAtSec: Float?,
)

@Composable
private fun produceCurveSamples(best: TestResultSummary?): SpeedCurveData? {
    if (best == null || best.dataFilePath.isBlank()) return null
    // 读 binary 是 IO；对 ~250 sample（10 秒 25 Hz）级别成本约 < 5ms，远低于一次 recomposition。
    // remember 按 dataFilePath 缓存，切换 best record 才重读。
    return remember(best.id, best.dataFilePath) {
        val samples = PerformanceTestTelemetryReader.read(best.dataFilePath)
        if (samples.isEmpty()) return@remember null
        val points = samples.map { it.tsDeltaMs / 1000f to it.speedKmh.toFloat() }
        val maxSec = points.maxOf { it.first }.coerceAtLeast(1f)
        val maxSpeed = points.maxOf { it.second }.coerceAtLeast(100f)
        val hundredAt = points.firstOrNull { it.second >= 100f }?.first
        SpeedCurveData(
            points = points,
            maxSec = maxSec,
            maxSpeedKmh = maxSpeed,
            hundredKmhAtSec = hundredAt,
        )
    }
}

@Composable
private fun SpeedCurveCanvas(curve: SpeedCurveData, modifier: Modifier) {
    val axisColor = TrackTechColors.BorderAlpha60
    val curveColor = TrackTechColors.Cyan
    val dashColor = TrackTechColors.Purple.copy(alpha = 0.6f)
    val dotColor = TrackTechColors.Cyan
    Canvas(modifier = modifier) {
        val leftPad = 32f
        val bottomPad = 28f
        val topPad = 16f
        val rightPad = 16f
        val plotW = size.width - leftPad - rightPad
        val plotH = size.height - topPad - bottomPad
        val originX = leftPad
        val originY = size.height - bottomPad

        // 横轴 / 纵轴
        drawLine(axisColor, Offset(originX, originY), Offset(originX + plotW, originY), strokeWidth = 1f)
        drawLine(axisColor, Offset(originX, originY), Offset(originX, originY - plotH), strokeWidth = 1f)

        // x tick: 0..5（按 maxSec 5 等分）
        for (i in 0..5) {
            val x = originX + plotW * (i / 5f)
            drawLine(axisColor, Offset(x, originY), Offset(x, originY + 4f), strokeWidth = 1f)
        }
        // y tick: 0/50/100/150 km/h 等分
        for (i in 0..3) {
            val y = originY - plotH * (i / 3f)
            drawLine(axisColor, Offset(originX, y), Offset(originX - 4f, y), strokeWidth = 1f)
        }

        // 真实速度曲线：speed (km/h) vs time (s)
        val path = Path()
        curve.points.forEachIndexed { i, (sec, kmh) ->
            val x = originX + plotW * (sec / curve.maxSec)
            val y = originY - plotH * (kmh / curve.maxSpeedKmh)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = curveColor, style = Stroke(width = 2.5.dp.toPx()))

        // 100 km/h 命中点：水平虚线 + 垂直虚线 + 圆点
        val hitSec = curve.hundredKmhAtSec
        if (hitSec != null) {
            val crossX = originX + plotW * (hitSec / curve.maxSec)
            val crossY = originY - plotH * (100f / curve.maxSpeedKmh)
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
            drawLine(dashColor, Offset(originX, crossY), Offset(crossX, crossY), 1.2.dp.toPx(), pathEffect = dash)
            drawLine(dashColor, Offset(crossX, originY), Offset(crossX, crossY), 1.2.dp.toPx(), pathEffect = dash)
            drawCircle(color = dotColor, radius = 5.dp.toPx(), center = Offset(crossX, crossY))
        }
    }
}

@Composable
private fun SpeedCurveBubble(timeLabel: String, modifier: Modifier) {
    Box(
        modifier = modifier
            .wrapContentSize()
            .clip(CutCornerPanelShape(4.dp, cutCornersAll))
            .background(TrackTechColors.Surface)
            .border(1.dp, TrackTechColors.Purple.copy(alpha = 0.6f), CutCornerPanelShape(4.dp, cutCornersAll))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column {
            Text(
                text = "100 km/h",
                style = TrackTechTypography.UiTextSmall,
                color = TrackTechColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = timeLabel,
                style = TrackTechTypography.UiTextSmall,
                color = TrackTechColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// =====================================================================
// LAPS 视图
// =====================================================================

@Composable
private fun LapsView(
    @Suppress("UNUSED_PARAMETER") context: Context,
    navController: NavController,
    testSessionViewModel: TestSessionViewModel = koinViewModel(),
) {
    val currentTrack by testSessionViewModel.currentSelectedTrack.collectAsState()
    val availableTracks by testSessionViewModel.availableTracks.collectAsState()
    val bestLap by testSessionViewModel.bestLapForCurrentTrack.collectAsState()
    val sessionCount by testSessionViewModel.sessionCountForCurrentTrack.collectAsState()
    val totalLapCount by testSessionViewModel.totalLapCountForCurrentTrack.collectAsState()
    val recentSessions by testSessionViewModel.recentSessionsForCurrentTrack.collectAsState()
    var showSelectTrackSheet by remember { mutableStateOf(false) }
    // round add-history-deletion §8.2：LAPS 长按删除候选；本 round 用普通 remember
    // （旋屏/配置变化丢 state 的 trade-off 跟 PERFORMANCE 一致，§12.4 follow-up）
    var deleteCandidate by remember { mutableStateOf<DeleteCandidate?>(null) }

    val record = remember(currentTrack, bestLap, sessionCount, totalLapCount) {
        CurrentTrackRecord(
            trackName = currentTrack?.name?.zh ?: "—",
            bestLapTime = bestLap?.bestLapMs?.let { formatLapMs(it) } ?: "--",
            bestLapDate = bestLap?.startTs?.let { formatDate(it) } ?: "暂无",
            length = currentTrack?.let { "%.3f km".format(it.lengthKm) } ?: "—",
            direction = "Clockwise",
            sessions = sessionCount,
            totalLaps = totalLapCount,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CurrentTrackRecordCard(
            track = currentTrack,
            record = record,
            onClick = { showSelectTrackSheet = true },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetricTile(
                label = "BEST LAP",
                value = record.bestLapTime,
                accentColor = TrackTechColors.Purple,
                valueSize = MetricSize.Small,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            MetricTile(
                label = "SESSIONS",
                value = record.sessions.toString(),
                accentColor = TrackTechColors.Cyan,
                valueSize = MetricSize.Medium,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            MetricTile(
                label = "TOTAL LAPS",
                value = record.totalLaps.toString(),
                accentColor = TrackTechColors.Cyan,
                valueSize = MetricSize.Medium,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }

    Spacer(Modifier.height(4.dp))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "SESSION HISTORY",
            style = TrackTechTypography.UiTextLabel,
            color = TrackTechColors.Cyan,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (recentSessions.isEmpty()) {
            Text(
                text = "No lap sessions yet.",
                style = TrackTechTypography.UiTextSmall,
                color = TrackTechColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            recentSessions.forEach { session ->
                TrackTechRow(
                    leadingIcon = Icons.Filled.CalendarMonth,
                    title = formatLapSessionRowTitle(session),
                    onClick = {
                        navController.navigate("lap_session_detail/${session.sessionId}")
                    },
                    onLongClick = {
                        deleteCandidate = DeleteCandidate.LapSession(
                            id = session.sessionId,
                            titleHint = formatLapDeleteHint(session),
                        )
                    },
                )
            }
        }
    }

    if (showSelectTrackSheet) {
        SelectTrackBottomSheet(
            onDismiss = { showSelectTrackSheet = false },
            tracks = availableTracks,
            currentTrackId = currentTrack?.id,
            onTrackSelected = { testSessionViewModel.selectTrack(it) },
        )
    }

    deleteCandidate?.let { candidate ->
        DeleteHistoryDialog(
            candidate = candidate,
            onConfirm = {
                if (candidate is DeleteCandidate.LapSession) {
                    testSessionViewModel.deleteLapSession(candidate.id)
                }
                deleteCandidate = null
            },
            onDismiss = { deleteCandidate = null },
        )
    }
}

private fun formatLapSessionRowTitle(session: TelemetrySession): String {
    val date = formatDate(session.startTs)
    val best = session.bestLapMs?.let { formatLapMs(it) } ?: "--"
    return "$date · ${session.lapCount} Laps · Best $best"
}

/**
 * round add-history-deletion §8.4：PERFORMANCE row 长按删除 dialog 副标 hint
 * （格式："0-100 km/h · 4.21 s · Today 10:35"），复用 [recentRunRowContent] 的派生口径。
 */
private fun formatPerfDeleteHint(result: TestResultSummary): String {
    val type = when (result.testTemplateId) {
        "acc_0_100" -> "0-100 km/h"
        "brake_100_0" -> "100-0 km/h"
        else -> result.testTemplateId
    }
    val value = when (result.testTemplateId) {
        "acc_0_100" -> "%.2f s".format(result.totalTime)
        "brake_100_0" -> "%.1f m".format(result.totalDistance)
        else -> "—"
    }
    val time = formatRunTimestamp(result.timestamp)
    return "$type · $value · $time"
}

/**
 * round add-history-deletion §8.4：LAPS row 长按删除 dialog 副标 hint
 * （格式："5/2 23:48 · 4 Laps · Best 1:32.457"），复用 [formatLapSessionRowTitle] 同款字段。
 */
private fun formatLapDeleteHint(session: TelemetrySession): String {
    val date = formatDate(session.startTs)
    val best = session.bestLapMs?.let { formatLapMs(it) } ?: "--"
    return "$date · ${session.lapCount} Laps · Best $best"
}

@Composable
private fun CurrentTrackRecordCard(
    track: Track?,
    record: CurrentTrackRecord,
    onClick: () -> Unit,
) {
    CutCornerPanel(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clickable { onClick() },
        cutSize = 14.dp,
        cutCorners = cutCornersDiagonal,
        contentPadding = 16.dp,
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "CURRENT TRACK RECORD",
                        style = TrackTechTypography.UiTextLabel,
                        color = TrackTechColors.Purple,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = record.trackName,
                        style = TrackTechTypography.RacingTitleMedium,
                        color = TrackTechColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column {
                    Text(
                        text = "BEST LAP",
                        style = TrackTechTypography.UiTextLabel,
                        color = TrackTechColors.Cyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = record.bestLapTime,
                        style = TrackTechTypography.ScoreMedium,
                        color = TrackTechColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = record.bestLapDate,
                        style = TrackTechTypography.UiTextSmall,
                        color = TrackTechColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Favorite",
                    tint = TrackTechColors.TextSecondary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp),
                )
                TrackThumbnail(
                    assetPath = track?.thumbnailAssetPath,
                    points = track?.referencePath?.points,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 24.dp, bottom = 4.dp),
                )
            }
        }
    }
}

// TrackPreviewStub removed by change `enhance-track-presentation` §11.7.
// 资产接入入口现在统一走 ui/tracktech/TrackThumbnail.kt（消费 Track.thumbnailAssetPath）。

// =====================================================================
// SegmentedControl（baseline 视觉零回归，本 round 不改）
// =====================================================================

@Composable
private fun SegmentedControl(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = CutCornerPanelShape(cutSize = 8.dp, cutCorners = cutCornersAll)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(TrackTechColors.Surface, shape)
            .border(1.dp, TrackTechColors.BorderAlpha60, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { opt ->
            val isSelected = opt == selected
            val itemShape = CutCornerPanelShape(cutSize = 6.dp, cutCorners = cutCornersDiagonal)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(itemShape)
                    .background(
                        if (isSelected) TrackTechColors.PurpleAlpha20 else Color.Transparent,
                        itemShape,
                    )
                    .let {
                        if (isSelected) it.border(1.dp, TrackTechColors.Purple, itemShape) else it
                    }
                    .clickable { onSelect(opt) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = opt,
                    style = TrackTechTypography.UiTextLabel,
                    color = if (isSelected) TrackTechColors.TextPrimary else TrackTechColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// =====================================================================
// LapsView 派生用临时容器
// =====================================================================

private data class CurrentTrackRecord(
    val trackName: String,
    val bestLapTime: String,
    val bestLapDate: String,
    val length: String,
    val direction: String,
    val sessions: Int,
    val totalLaps: Int,
)

// placeholderTrackRecord top-level val removed by change
// `enhance-track-presentation` §11.1：trackName / length 现在从 LapsView 内部
// 派生自 currentSelectedTrack，其他 mock 字段已并入 LapsView 内部
// remember(currentTrack) { CurrentTrackRecord(...) } 块。
