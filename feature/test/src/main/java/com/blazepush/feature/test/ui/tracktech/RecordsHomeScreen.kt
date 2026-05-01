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
import com.blazepush.core.domain.model.TelemetrySession
import com.blazepush.core.domain.model.TestResultSummary
import com.blazepush.feature.test.model.track.Track
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
            "PERFORMANCE" -> PerformanceView(context = context)
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
    context: Context,
    testSessionViewModel: TestSessionViewModel = koinViewModel(),
) {
    val bestAcc by testSessionViewModel.bestAcceleration0To100.collectAsState()
    val bestBrake by testSessionViewModel.bestBraking100To0.collectAsState()
    val totalRuns by testSessionViewModel.totalRunCount.collectAsState()
    val recent by testSessionViewModel.recentRuns.collectAsState()

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

        SpeedCurveStub()
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
                        Toast.makeText(context, "Run detail placeholder", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
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

@Composable
private fun SpeedCurveStub() {
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
            Box(modifier = Modifier.fillMaxSize()) {
                SpeedCurveCanvas(modifier = Modifier.fillMaxSize())
                SpeedCurveBubble(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SpeedCurveCanvas(modifier: Modifier) {
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

        // 横轴
        drawLine(
            color = axisColor,
            start = Offset(originX, originY),
            end = Offset(originX + plotW, originY),
            strokeWidth = 1f,
        )
        // 纵轴
        drawLine(
            color = axisColor,
            start = Offset(originX, originY),
            end = Offset(originX, originY - plotH),
            strokeWidth = 1f,
        )

        // x tick: 0..5 s（6 个 tick）
        for (i in 0..5) {
            val x = originX + plotW * (i / 5f)
            drawLine(
                color = axisColor,
                start = Offset(x, originY),
                end = Offset(x, originY + 4f),
                strokeWidth = 1f,
            )
        }
        // y tick: 0/50/100/150 km/h（4 个 tick）
        for (i in 0..3) {
            val y = originY - plotH * (i / 3f)
            drawLine(
                color = axisColor,
                start = Offset(originX, y),
                end = Offset(originX - 4f, y),
                strokeWidth = 1f,
            )
        }

        // cyan 渐近曲线：0 -> ~100 km/h @ 4.21s -> 渐近 ~150
        // 参数化：t in [0, 1] 映射 x 0 -> plotW；speed = 150 * (1 - exp(-2.4*t)) 类似
        val path = Path()
        val steps = 60
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            // 5 秒映射到 plotW，曲线在 5 秒达到约 130 km/h（继续走可达 150）
            val speed = 150f * (1f - kotlin.math.exp(-1.4 * t * 5).toFloat())
            val x = originX + plotW * t
            val y = originY - plotH * (speed / 150f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = curveColor,
            style = Stroke(width = 2.5.dp.toPx()),
        )

        // 100 km/h 处水平虚线 + 4.21 s 处垂直虚线
        val tHit = 4.21f / 5f
        val crossX = originX + plotW * tHit
        val crossY = originY - plotH * (100f / 150f)
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
        drawLine(
            color = dashColor,
            start = Offset(originX, crossY),
            end = Offset(crossX, crossY),
            strokeWidth = 1.2.dp.toPx(),
            pathEffect = dash,
        )
        drawLine(
            color = dashColor,
            start = Offset(crossX, originY),
            end = Offset(crossX, crossY),
            strokeWidth = 1.2.dp.toPx(),
            pathEffect = dash,
        )
        // 交点圆点
        drawCircle(
            color = dotColor,
            radius = 5.dp.toPx(),
            center = Offset(crossX, crossY),
        )
    }
}

@Composable
private fun SpeedCurveBubble(modifier: Modifier) {
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
                text = "4.21 s",
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
    val bestLap by testSessionViewModel.bestLapForCurrentTrack.collectAsState()
    val sessionCount by testSessionViewModel.sessionCountForCurrentTrack.collectAsState()
    val totalLapCount by testSessionViewModel.totalLapCountForCurrentTrack.collectAsState()
    val recentSessions by testSessionViewModel.recentSessionsForCurrentTrack.collectAsState()

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
        CurrentTrackRecordCard(track = currentTrack, record = record)

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
                )
            }
        }
    }
}

private fun formatLapSessionRowTitle(session: TelemetrySession): String {
    val date = formatDate(session.startTs)
    val best = session.bestLapMs?.let { formatLapMs(it) } ?: "--"
    return "$date · ${session.lapCount} Laps · Best $best"
}

@Composable
private fun CurrentTrackRecordCard(track: Track?, record: CurrentTrackRecord) {
    CutCornerPanel(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp),
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
