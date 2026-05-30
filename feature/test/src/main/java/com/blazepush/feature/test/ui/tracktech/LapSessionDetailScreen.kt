package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.blazepush.core.data.repository.TelemetryRepository
import com.blazepush.core.domain.model.TelemetryCrossingEvent
import com.blazepush.core.domain.model.TelemetrySession
import com.blazepush.feature.test.FileLogger
import com.blazepush.feature.test.repository.TrackCatalog
import com.blazepush.feature.test.viewmodel.TestSessionViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Records/Laps Session 详情屏（Overview + Lap Records List）。
 *
 * 数据接入仅通过 TelemetryRepository public API（D13）：getSession + getCrossings；
 * UI 层不直接 import CrossingEventDao / TelemetrySessionDao。
 *
 * @author CC
 * @description lap session detail screen
 * @date 2026-05-01
 */
@Composable
fun LapSessionDetailScreen(
    navController: NavController,
    sessionId: String,
    telemetryRepository: TelemetryRepository = koinInject(),
    sessionViewModel: TestSessionViewModel = koinViewModel(),
    trackCatalog: TrackCatalog = koinInject(),
) {
    var session by remember { mutableStateOf<TelemetrySession?>(null) }
    var crossings by remember { mutableStateOf<List<TelemetryCrossingEvent>>(emptyList()) }

    val currentTrack by sessionViewModel.currentSelectedTrack.collectAsState()

    LaunchedEffect(sessionId) {
        session = telemetryRepository.getSession(sessionId)
        crossings = telemetryRepository.getCrossings(sessionId)
        // persist-session-summary-fields round 起：topSpeedKmh 直接读 entity.topSpeedKmh，
        // 不再每次进入 detail 屏全扫 binary（endSession 时已派生持久化）
    }

    val derived = remember(crossings) { deriveDetailMetrics(crossings) }
    // unify-lap-count-pairing-semantics round（road-test-first 强制埋点）：记录站点 B 圈列表
    // 有效圈数 + null wallClock 计数 + 配对 key，真机点击前 adb pull 核对列表与 getLapTelemetry
    // 可读圈一致（R2）。
    LaunchedEffect(derived) {
        val wallClockNull = crossings.count {
            it.accepted &&
                it.gateType.equals("StartFinish", ignoreCase = true) &&
                it.crossingWallClockTimestampMs == null
        }
        FileLogger.d(
            "LapPairing",
            "detail sid=$sessionId validLaps=${derived.validLaps} wallClockNull=$wallClockNull key=wallClock",
        )
    }
    val durationMs = session?.let { it.endTs - it.startTs }?.takeIf { it > 0L }
    val topSpeed = session?.topSpeedKmh

    // track name 用 when 分支严格分流（spec D5：MUST NOT 用 elvis 链 fallback currentTrack）：
    // - 优先级 1：trackNameSnapshot 非空（本 round 后所有新 session 都会有）
    // - 优先级 2：snapshot 空 + trackId 非空 → catalog 解析；解析失败 → "—"（**不** fallback currentTrack）
    // - 优先级 3：snapshot 与 trackId 都为 null（历史 session）→ currentTrack
    val trackName = remember(session, currentTrack) {
        val snapshot = session?.trackNameSnapshot
        val sessionTrackId = session?.trackId
        when {
            !snapshot.isNullOrBlank() -> snapshot
            sessionTrackId != null -> trackCatalog.getTrack(sessionTrackId)?.name?.zh ?: "—"
            else -> currentTrack?.name?.zh ?: "—"
        }
    }
    // distance 仅 catalog 解析成功时显示（不依赖 currentSelectedTrack 的 lengthKm 避免误读）
    val distanceKm = remember(session, derived) {
        session?.trackId
            ?.let { trackCatalog.getTrack(it) }
            ?.lengthKm
            ?.let { it * derived.validLaps }
            ?.takeIf { it > 0.0 }
    }

    val sessionDateLabel = session?.startTs?.let(::formatDateTime) ?: "—"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrackTechColors.Background),
    ) {
        DetailHeader(
            title = "Session",
            onBack = { navController.popBackStack() },
        )
        // ui-redo-lap-sector-table round：撤掉过大的 TheoreticalBestPanel，改用
        // RaceChrono/AiM 风格的 "带 sector 列的表"。table 非 null（有 sector 门）时圈列表区
        // 渲染 sector 表（表头 + THEORETICAL 行 + valid/best 圈行，横向滚动同步固定列宽防窄屏挤压）；
        // table 为 null（无 sector 门或 <2 SF）时 fallback 回原 LapRecordsHeader + 全部 LapRecordRow。
        val table = remember(crossings) { computeLapSectorTable(crossings) }
        // sector 表横向滚动 state：表头 / THEORETICAL / 各圈行共享同一 hScroll 保证横向同步。
        val hScroll = rememberScrollState()

        // 导航回调工厂：VALID/BEST 圈 lapNumber=idx+1 严格对应 getLapTelemetry(sessionId, lapNumber-1)
        // 的 lapIndex；INVALID/INCOMPLETE 圈 lapNumber 是合成值，点了 getLapTelemetry 越界返回
        // null → 白屏，故传 null 禁点（Decision 圈行可点范围）。
        val onLapClickFactory: (record: UiLapRecord) -> (() -> Unit)? = { record ->
            when (record.status) {
                UiLapStatus.VALID, UiLapStatus.BEST -> {
                    {
                        val lapIndex = record.lapNumber - 1
                        FileLogger.d(
                            "LapDetail",
                            "navigate sid=$sessionId lapNumber=${record.lapNumber} -> lapIndex=$lapIndex",
                        )
                        navController.navigate("lap_detail/$sessionId/$lapIndex")
                    }
                }
                UiLapStatus.INVALID, UiLapStatus.INCOMPLETE -> null
            }
        }

        // redo-video-overlay-visual-gauges round（真机反馈）：去掉独立 "VIDEO REPLAY" 圈列表区，
        // 把视频回放入口整合进已有圈成绩单（sector 表 / fallback 圈列表）的每一行：
        // - session 有视频 → VALID/BEST 圈行末尾显示小播放图标（▶），点图标导航 lap_video/{sid}/{lapIndex}；
        //   行其余部分仍点进 lap_detail（单圈数据图表）。两入口共存不互斥。
        // - 无视频 / INVALID/INCOMPLETE 圈 → 不显示播放图标（getLapTelemetry 越界 → 白屏，禁点）。
        val hasVideo = session?.videoFilePath != null
        // 视频回放点击工厂：仅 VALID/BEST + 有视频时返回非 null（否则圈行不渲染播放图标）。
        val onVideoClickFactory: (lapNumber: Int, status: UiLapStatus) -> (() -> Unit)? =
            { lapNumber, status ->
                if (hasVideo && (status == UiLapStatus.VALID || status == UiLapStatus.BEST)) {
                    {
                        val lapIndex = lapNumber - 1
                        FileLogger.d(
                            "VideoOverlay",
                            "open video replay (from lap row) sid=$sessionId lapNumber=$lapNumber -> lapIndex=$lapIndex",
                        )
                        navController.navigate("lap_video/$sessionId/$lapIndex")
                    }
                } else {
                    null
                }
            }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OverviewSection(
                    trackName = trackName,
                    sessionDate = sessionDateLabel,
                    bestLapMs = derived.bestLapMs,
                    totalLaps = derived.totalLaps,
                    validLaps = derived.validLaps,
                    invalidLaps = derived.invalidLaps,
                    durationMs = durationMs,
                    topSpeedKmh = topSpeed,
                    distanceKm = distanceKm,
                )
            }
            // M3 COMPARE 入口（lap-comparison-screen-with-cursor）：仅在 ≥2 个 VALID/BEST 圈时
            // 可点（< 2 圈无法比较 → disabled）。点击导航到 lap_comparison/{sessionId}。
            item {
                CompareEntry(
                    enabled = derived.validLaps >= 2,
                    onClick = {
                        FileLogger.d(
                            "LapCompare",
                            "open compare sid=$sessionId validLaps=${derived.validLaps}",
                        )
                        navController.navigate("lap_comparison/$sessionId")
                    },
                )
            }
            if (table != null) {
                // sector 表路径：表头 + THEORETICAL + valid/best 圈行（横向滚动同步），
                // INVALID/INCOMPLETE 圈仍用原 LapRecordRow 在表下方列出（别丢）。
                item { LapRecordsHeader() }
                item {
                    LapSectorTableBlock(
                        table = table,
                        bestActualLapMs = derived.bestLapMs,
                        hScroll = hScroll,
                        onLapClick = { lapNumber ->
                            val lapIndex = lapNumber - 1
                            FileLogger.d(
                                "LapDetail",
                                "navigate sid=$sessionId lapNumber=$lapNumber -> lapIndex=$lapIndex",
                            )
                            navController.navigate("lap_detail/$sessionId/$lapIndex")
                        },
                        // sector 表圈行均为 VALID/BEST → 有视频时整行右侧加播放图标导航 lap_video。
                        onVideoClick = if (hasVideo) {
                            { lapNumber -> onVideoClickFactory(lapNumber, UiLapStatus.VALID)?.invoke() }
                        } else {
                            null
                        },
                    )
                }
                // sector 表只渲染 valid/best 圈（table.laps）；INVALID/INCOMPLETE 圈从
                // derived.lapRecords 取出在表下方用原 LapRecordRow 列出。
                val extraRecords = derived.lapRecords.filter {
                    it.status == UiLapStatus.INVALID || it.status == UiLapStatus.INCOMPLETE
                }
                items(extraRecords) { record ->
                    LapRecordRow(
                        record = record,
                        onClick = onLapClickFactory(record),
                        onVideoClick = onVideoClickFactory(record.lapNumber, record.status),
                    )
                }
            } else {
                // fallback：无 sector 门或 <2 SF → 保持原圈列表（不回归）。
                item { LapRecordsHeader() }
                if (derived.lapRecords.isEmpty()) {
                    item { EmptyLapRecordsHint() }
                } else {
                    items(derived.lapRecords) { record ->
                        LapRecordRow(
                            record = record,
                            onClick = onLapClickFactory(record),
                            onVideoClick = onVideoClickFactory(record.lapNumber, record.status),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(
    title: String,
    onBack: () -> Unit,
) {
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
                contentDescription = "Back",
                tint = TrackTechColors.TextPrimary,
            )
        }
        Spacer(Modifier.size(12.dp))
        Text(
            text = title,
            style = TrackTechTypography.RacingTitleMedium,
            color = TrackTechColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OverviewSection(
    trackName: String,
    sessionDate: String,
    bestLapMs: Long?,
    totalLaps: Int,
    validLaps: Int,
    invalidLaps: Int,
    durationMs: Long?,
    topSpeedKmh: Double?,
    distanceKm: Double?,
) {
    CutCornerPanel(
        modifier = Modifier.fillMaxWidth(),
        cutSize = 8.dp,
        cutCorners = cutCornersAll,
        contentPadding = 16.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OverviewRow(label = "Track", value = trackName)
            OverviewRow(label = "Session", value = sessionDate)

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricTile(
                    label = "BEST LAP",
                    value = formatLapTime(bestLapMs),
                    modifier = Modifier.weight(1f),
                    accentColor = TrackTechColors.Purple,
                    valueSize = MetricSize.Medium,
                    valueKind = MetricKind.Score,
                )
                MetricTile(
                    label = "TOTAL LAPS",
                    value = totalLaps.toString(),
                    modifier = Modifier.weight(1f),
                    accentColor = TrackTechColors.Cyan,
                    valueSize = MetricSize.Medium,
                    valueKind = MetricKind.Score,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricTile(
                    label = "VALID LAPS",
                    value = validLaps.toString(),
                    modifier = Modifier.weight(1f),
                    accentColor = TrackTechColors.Green,
                    valueSize = MetricSize.Small,
                    valueKind = MetricKind.Score,
                )
                MetricTile(
                    label = "INVALID LAPS",
                    value = invalidLaps.toString(),
                    modifier = Modifier.weight(1f),
                    accentColor = TrackTechColors.Red,
                    valueSize = MetricSize.Small,
                    valueKind = MetricKind.Score,
                )
            }
            Spacer(Modifier.height(4.dp))
            // 辅助信息（Top speed / Duration / Distance）用 row 风格 label-value，避免三列均分时
            // value+unit 字符长（如 "171.3 km/h"）撑爆单元格被截断
            OverviewRow(
                label = "Top speed",
                value = topSpeedKmh?.let { "%.1f km/h".format(it) } ?: "--",
            )
            OverviewRow(
                label = "Duration",
                value = durationMs?.let(::formatDuration) ?: "--",
            )
            OverviewRow(
                label = "Distance",
                value = distanceKm?.let { "%.2f km".format(it) } ?: "--",
            )
        }
    }
}

@Composable
private fun OverviewRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
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

/**
 * M3 COMPARE 入口按钮（lap-comparison-screen-with-cursor）。
 * enabled = false（< 2 VALID/BEST 圈）时灰禁不可点；enabled = true 时紫色高亮可点导航。
 * 时间/文字字符串走 Score/UiText 字体（V2：MUST NOT DSEG7）；Text maxLines=1 + Ellipsis。
 */
@Composable
private fun CompareEntry(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (enabled) TrackTechColors.Purple else TrackTechColors.BorderAlpha60
    val labelColor = if (enabled) TrackTechColors.Purple else TrackTechColors.TextMuted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CutCornerPanelShape(cutSize = 6.dp, cutCorners = cutCornersAll))
            .background(if (enabled) TrackTechColors.PurpleAlpha20 else TrackTechColors.Surface)
            .border(
                width = 1.dp,
                color = accent,
                shape = CutCornerPanelShape(cutSize = 6.dp, cutCorners = cutCornersAll),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "COMPARE LAPS",
            style = TrackTechTypography.UiTextLabel,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (enabled) "›" else "—",
            style = TrackTechTypography.UiTextBody,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LapRecordsHeader() {
    Text(
        text = "Lap Records",
        style = TrackTechTypography.UiTextLabel,
        color = TrackTechColors.Cyan,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun EmptyLapRecordsHint() {
    Text(
        text = "No completed laps recorded.",
        style = TrackTechTypography.UiTextSmall,
        color = TrackTechColors.TextMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun LapRecordRow(
    record: UiLapRecord,
    onClick: (() -> Unit)? = null,
    // redo-video-overlay-visual-gauges round：非 null 时行末显示小播放图标（▶），点图标导航 lap_video。
    // 仅 VALID/BEST + session 有视频时由调用方传非 null（视频回放入口整合进圈成绩单）。
    onVideoClick: (() -> Unit)? = null,
) {
    val timeColor = when (record.status) {
        UiLapStatus.BEST -> TrackTechColors.Purple
        UiLapStatus.VALID -> TrackTechColors.TextPrimary
        UiLapStatus.INVALID -> TrackTechColors.Red
        UiLapStatus.INCOMPLETE -> TrackTechColors.TextMuted
    }
    val borderColor: Color = if (record.status == UiLapStatus.BEST) {
        TrackTechColors.PurpleAlpha40
    } else {
        TrackTechColors.BorderAlpha60
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CutCornerPanelShape(cutSize = 6.dp, cutCorners = cutCornersAll))
            .background(TrackTechColors.Surface)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = CutCornerPanelShape(cutSize = 6.dp, cutCorners = cutCornersAll),
            )
            // 仅 VALID/BEST 圈传非 null onClick → 可点导航；INVALID/INCOMPLETE 传 null → 禁点（Decision 圈行可点范围）
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Lap ${record.lapNumber}",
            style = TrackTechTypography.UiTextLabel,
            color = TrackTechColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(64.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = formatLapTime(record.timeMs),
            style = TrackTechTypography.UiTextBody,
            color = timeColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        StatusChip(record)
        when (record.status) {
            UiLapStatus.INVALID -> if (!record.reason.isNullOrBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = record.reason.uppercase(),
                    style = TrackTechTypography.UiTextSmall,
                    color = TrackTechColors.Red.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            UiLapStatus.VALID -> {
                val diffText = formatDiff(record)
                if (diffText.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = diffText,
                        style = TrackTechTypography.UiTextSmall,
                        color = TrackTechColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            UiLapStatus.BEST, UiLapStatus.INCOMPLETE -> Unit
        }
        // 视频回放入口：行末固定小播放图标（V2：末尾固定元素前加 Spacer 保间距）。
        // 自身 clickable 在图标区域优先消费点击（导航 lap_video），不影响行其余区域的 lap_detail 点击。
        if (onVideoClick != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(TrackTechColors.CyanAlpha60.copy(alpha = 0.18f))
                    .clickable(onClick = onVideoClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "▶",
                    style = TrackTechTypography.UiTextBody,
                    color = TrackTechColors.Cyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StatusChip(record: UiLapRecord) {
    val (label, color) = when (record.status) {
        UiLapStatus.BEST -> "BEST" to TrackTechColors.Purple
        UiLapStatus.VALID -> return  // VALID 圈不显示 chip，时间字色已表达
        UiLapStatus.INVALID -> "INVALID" to TrackTechColors.Red
        UiLapStatus.INCOMPLETE -> "INCOMPLETE" to TrackTechColors.TextMuted
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = TrackTechTypography.UiTextLabel.copy(fontSize = 10.sp),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatDiff(record: UiLapRecord): String {
    val ms = record.diffMs ?: return ""
    if (ms == 0L) return ""
    val sign = if (ms >= 0) "+" else "-"
    return "%s%.3f s".format(sign, abs(ms) / 1000.0)
}

private fun formatLapTime(ms: Long?): String {
    if (ms == null || ms <= 0L) return "--:--.---"
    val totalSec = ms / 1000
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    val millis = ms % 1000
    return "%d:%02d.%03d".format(minutes, seconds, millis)
}

// sector 列紧凑时间格式（sector 多在 10-40s，formatLapTime 出 "0:23.456" 前导 0: 较冗）：
// 出 "23.456" / 大于 60s 时出 "m:ss.mmm"。仍是时间字符串走 Score 字体（V2：MUST NOT DSEG7）。
private fun formatSectorSplit(ms: Long): String {
    if (ms <= 0L) return "—"
    val totalSec = ms / 1000
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    val millis = ms % 1000
    return if (minutes > 0) {
        "%d:%02d.%03d".format(minutes, seconds, millis)
    } else {
        "%d.%03d".format(seconds, millis)
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private fun formatDateTime(epochMs: Long): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return formatter.format(Date(epochMs))
}

// unify-lap-count-pairing-semantics round：private → internal，使 deriveDetailMetrics 纯函数
// 可被 module 内单测断言（不暴露为 public API）。
internal data class UiLapRecord(
    val lapNumber: Int,
    val timeMs: Long?,
    val diffMs: Long?,
    val status: UiLapStatus,
    val reason: String?,
)

internal enum class UiLapStatus { BEST, VALID, INVALID, INCOMPLETE }

internal data class DetailMetrics(
    val totalLaps: Int,
    val validLaps: Int,
    val invalidLaps: Int,
    val bestLapMs: Long?,
    val lapRecords: List<UiLapRecord>,
)

/**
 * ui-redo-lap-sector-table round：圈 × sector 拆分表（RaceChrono/AiM 风格）。
 *
 * 每个 valid/best 圈一行（splits 对齐 sectorCount，缺段补 null）；theoretical 行取各 sector 最快段拼接。
 */
internal data class LapSectorTable(
    val sectorCount: Int, // 每圈段数（= 最大段数）
    val laps: List<LapSectorRow>, // 每个 valid/best 圈一行
    val theoreticalTotalMs: Long, // 各 sector 最快段拼接总和
    val bestSplitPerSector: List<Long>, // size=sectorCount，每 sector 最快段耗时
    val bestLapPerSector: List<Int>, // size=sectorCount，每 sector 最快是第几圈(lapNumber)
)

internal data class LapSectorRow(
    val lapNumber: Int,
    val lapTimeMs: Long,
    val splits: List<Long?>, // size=sectorCount；该圈该 sector 缺失则 null
)

/**
 * 从 LapSessionDetailScreen 已加载的 crossings 直接算圈 × sector 拆分表（复用 deriveDetailMetrics /
 * getLapTelemetry 同款 SF 配对 + sector 窗口逻辑，零 binary 读、零 repository 改）。
 *
 * 无 sector 门（每圈 < 2 段）、<2 SF、或无完整圈 → null（圈列表区 fallback 回原简单列表）。
 */
internal fun computeLapSectorTable(crossings: List<TelemetryCrossingEvent>): LapSectorTable? {
    val acceptedSF = crossings
        .filter {
            it.gateType.equals("StartFinish", ignoreCase = true) &&
                it.accepted &&
                it.crossingWallClockTimestampMs != null
        }
        .sortedBy { it.crossingWallClockTimestampMs ?: Long.MAX_VALUE }
    if (acceptedSF.size < 2) return null
    val sectorWallClocks = crossings
        .filter {
            it.gateType.equals("Sector", ignoreCase = true) &&
                it.accepted &&
                it.crossingWallClockTimestampMs != null
        }
        .mapNotNull { it.crossingWallClockTimestampMs }
    // 每圈窗口 = acceptedSF.zipWithNext()（lapNumber = idx + 1，与 deriveDetailMetrics /
    // getLapTelemetry 同源配对）。acceptedSF 已过滤 wallClock 非空，!! 安全。
    // 窗口内 accepted Sector wallClock 落 [lapStart, lapEnd) 且 != lapStart → bounds 相邻差 = 各 split。
    data class RawLap(val lapNumber: Int, val lapTimeMs: Long, val splits: List<Long>)
    val perLap = acceptedSF.zipWithNext().mapIndexed { idx, pair ->
        val lapStart = pair.first.crossingWallClockTimestampMs!!
        val lapEnd = pair.second.crossingWallClockTimestampMs!!
        val inWindow = sectorWallClocks.filter { it in lapStart until lapEnd && it != lapStart }.sorted()
        val bounds = listOf(lapStart) + inWindow + lapEnd
        RawLap(
            lapNumber = idx + 1,
            lapTimeMs = lapEnd - lapStart,
            splits = bounds.zipWithNext { a, b -> b - a },
        )
    }
    val sectorCount = perLap.maxOfOrNull { it.splits.size } ?: 0
    if (sectorCount < 2) return null

    // 每行 splits 对齐 sectorCount（实际段数不足末尾补 null）。
    val laps = perLap.map { raw ->
        val aligned: List<Long?> = (0 until sectorCount).map { i -> raw.splits.getOrNull(i) }
        LapSectorRow(lapNumber = raw.lapNumber, lapTimeMs = raw.lapTimeMs, splits = aligned)
    }

    // bestSplitPerSector / bestLapPerSector 只看"完整圈"(splits.size == sectorCount)；无完整圈 → null。
    val completeLaps = perLap.filter { it.splits.size == sectorCount }
    if (completeLaps.isEmpty()) return null
    val bestSplitPerSector = mutableListOf<Long>()
    val bestLapPerSector = mutableListOf<Int>()
    for (i in 0 until sectorCount) {
        val best = completeLaps.minByOrNull { it.splits[i] }!!
        bestSplitPerSector += best.splits[i]
        bestLapPerSector += best.lapNumber
    }
    return LapSectorTable(
        sectorCount = sectorCount,
        laps = laps,
        theoreticalTotalMs = bestSplitPerSector.sum(),
        bestSplitPerSector = bestSplitPerSector,
        bestLapPerSector = bestLapPerSector,
    )
}

// sector 列固定宽度 token（窄屏关键：固定列宽 + 横向滚动同步避免 5+ 列在窄屏挤压换行）。
private val SectorTableLapColWidth = 48.dp
private val SectorTableTimeColWidth = 76.dp
private val SectorTableSectorColWidth = 60.dp

/**
 * 圈 × sector 拆分表块（表头 + THEORETICAL 行 + 各 valid/best 圈行，全部共享同一 hScroll 横向同步）。
 *
 * 时间字符串走 Score 字体（V2：MUST NOT DSEG7）；每 Text maxLines=1 + Ellipsis；
 * 固定列宽 + horizontalScroll(hScroll) 防窄屏换行。
 */
@Composable
private fun LapSectorTableBlock(
    table: LapSectorTable,
    bestActualLapMs: Long?,
    hScroll: ScrollState,
    onLapClick: (lapNumber: Int) -> Unit,
    // redo-video-overlay-visual-gauges round：非 null 时每圈行右侧加固定播放图标（不随横滚），
    // 点图标导航 lap_video（视频回放入口整合进 sector 成绩表）。
    onVideoClick: ((lapNumber: Int) -> Unit)? = null,
) {
    CutCornerPanel(
        modifier = Modifier.fillMaxWidth(),
        cutSize = 6.dp,
        cutCorners = cutCornersAll,
        contentPadding = 12.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 表头行：LAP | TIME | S1 | S2 | ... | SN
            Row(modifier = Modifier.horizontalScroll(hScroll)) {
                SectorCell(SectorTableLapColWidth) {
                    SectorCellText("LAP", TrackTechTypography.UiTextLabel, TrackTechColors.Cyan)
                }
                SectorCell(SectorTableTimeColWidth) {
                    SectorCellText("TIME", TrackTechTypography.UiTextLabel, TrackTechColors.Cyan)
                }
                for (i in 0 until table.sectorCount) {
                    SectorCell(SectorTableSectorColWidth) {
                        SectorCellText("S${i + 1}", TrackTechTypography.UiTextLabel, TrackTechColors.Cyan)
                    }
                }
            }

            // THEORETICAL 行：OPT | 拼接总时间 | 各 sector 最快段（绿色高亮）
            Row(modifier = Modifier.horizontalScroll(hScroll)) {
                SectorCell(SectorTableLapColWidth) {
                    SectorCellText("OPT", TrackTechTypography.UiTextLabel, TrackTechColors.Green)
                }
                SectorCell(SectorTableTimeColWidth) {
                    SectorCellText(
                        formatLapTime(table.theoreticalTotalMs),
                        TrackTechTypography.UiTextBody,
                        TrackTechColors.Green,
                    )
                }
                table.bestSplitPerSector.forEach { ms ->
                    SectorCell(SectorTableSectorColWidth) {
                        SectorCellText(formatSectorSplit(ms), TrackTechTypography.UiTextBody, TrackTechColors.Green)
                    }
                }
            }

            // gain 小字：理论最优 vs 最快实跑圈（gain > 0 时绿色显示能再快多少）。
            if (bestActualLapMs != null && bestActualLapMs > table.theoreticalTotalMs) {
                Text(
                    text = "比最快圈快 %.3fs".format((bestActualLapMs - table.theoreticalTotalMs) / 1000.0),
                    style = TrackTechTypography.UiTextSmall,
                    color = TrackTechColors.Green,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 各圈行（valid/best）：[Lap N | lapTime | 各 sector split（横滚区）] + 固定播放图标（不随横滚）
            // 横滚区 weight(1f) 点击导航 lap_detail；右侧固定播放图标点击导航 lap_video（有视频时）。
            table.laps.forEach { lap ->
                val isBest = bestActualLapMs != null && lap.lapTimeMs == bestActualLapMs
                val timeColor = if (isBest) TrackTechColors.Purple else TrackTechColors.TextPrimary
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onLapClick(lap.lapNumber) }
                            .horizontalScroll(hScroll),
                    ) {
                        SectorCell(SectorTableLapColWidth) {
                            SectorCellText("Lap ${lap.lapNumber}", TrackTechTypography.UiTextSmall, TrackTechColors.TextSecondary)
                        }
                        SectorCell(SectorTableTimeColWidth) {
                            SectorCellText(formatLapTime(lap.lapTimeMs), TrackTechTypography.UiTextBody, timeColor)
                        }
                        lap.splits.forEachIndexed { i, split ->
                            val isSectorBest = split != null && split == table.bestSplitPerSector.getOrNull(i)
                            val splitColor = if (isSectorBest) TrackTechColors.Green else TrackTechColors.TextPrimary
                            SectorCell(SectorTableSectorColWidth) {
                                SectorCellText(
                                    if (split != null) formatSectorSplit(split) else "—",
                                    TrackTechTypography.UiTextBody,
                                    splitColor,
                                )
                            }
                        }
                    }
                    if (onVideoClick != null) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(TrackTechColors.CyanAlpha60.copy(alpha = 0.18f))
                                .clickable { onVideoClick(lap.lapNumber) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "▶",
                                style = TrackTechTypography.UiTextBody,
                                color = TrackTechColors.Cyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 固定宽度 sector 表单元（窄屏关键：锁列宽 + 末尾 8dp 内边距分隔，inner Text 被 bounded width 测量
 * 后 maxLines=1+Ellipsis 才生效）。
 */
@Composable
private fun SectorCell(
    width: Dp,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.width(width).padding(end = 8.dp)) {
        content()
    }
}

/**
 * sector 表单元内文本（统一 maxLines=1 + Ellipsis；时间字符串走 Score / UiText 字体，MUST NOT DSEG7）。
 */
@Composable
private fun SectorCellText(
    text: String,
    style: TextStyle,
    color: Color,
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

internal fun deriveDetailMetrics(crossings: List<TelemetryCrossingEvent>): DetailMetrics {
    // unify-lap-count-pairing-semantics round：accepted SF 排序键 + duration 减法统一为
    // crossingWallClockTimestampMs（与 endSession 站点 A / getLapTelemetry 站点 C 同源），
    // 使 UiLapRecord.lapNumber=idx+1 严格对应 getLapTelemetry(sessionId, idx)（lapIndex=lapNumber-1）。
    // MUST NOT 用 crossingTimestampMs（GPS 协议时钟跨整点回绕会与 wallClock 排序分歧 → 点击错圈）。
    val sf = crossings
        .filter { it.gateType.equals("StartFinish", ignoreCase = true) }
        .sortedBy { it.crossingWallClockTimestampMs ?: Long.MAX_VALUE }
    val acceptedSF = sf.filter { it.accepted }
    val rejectedSF = sf.filter { !it.accepted }

    // duration 仅对"起止两 crossing wallClock 均非空"的相邻对计算（任一端 null 不计有效圈，
    // 与 getLapTelemetry 对 null wallClock 圈返回 null 收敛）。
    val durations = acceptedSF.zipWithNext { a, b -> a to b }
        .mapNotNull { (a, b) ->
            val sa = a.crossingWallClockTimestampMs
            val sb = b.crossingWallClockTimestampMs
            if (sa != null && sb != null) sb - sa else null
        }
    val bestLapMs = durations.minOrNull()

    val records = mutableListOf<UiLapRecord>()
    var bestAssigned = false
    durations.forEachIndexed { idx, dur ->
        val isBest = !bestAssigned && bestLapMs != null && dur == bestLapMs
        if (isBest) bestAssigned = true
        records += UiLapRecord(
            lapNumber = idx + 1,
            timeMs = dur,
            diffMs = bestLapMs?.let { dur - it },
            status = if (isBest) UiLapStatus.BEST else UiLapStatus.VALID,
            reason = null,
        )
    }
    rejectedSF.forEachIndexed { idx, c ->
        records += UiLapRecord(
            lapNumber = durations.size + idx + 1,
            timeMs = null,
            diffMs = null,
            status = UiLapStatus.INVALID,
            reason = c.reason,
        )
    }
    return DetailMetrics(
        // totalLaps 是 "完成圈数尝试"：valid 完成圈 + invalid 作废圈。
        // 不能用 acceptedSF.size，因为它把首次开圈 crossing 也算 1（第 1 圈完成时
        // acceptedSF 有 2 个，会让 UI 显示 TOTAL LAPS = 2 而实际只 1 圈完成）。
        totalLaps = durations.size + rejectedSF.size,
        validLaps = durations.size,
        invalidLaps = rejectedSF.size,
        bestLapMs = bestLapMs,
        lapRecords = records,
    )
}
