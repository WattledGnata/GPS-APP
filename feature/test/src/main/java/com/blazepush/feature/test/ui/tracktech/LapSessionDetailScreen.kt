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
import androidx.compose.ui.text.style.TextOverflow
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
            item { LapRecordsHeader() }
            if (derived.lapRecords.isEmpty()) {
                item { EmptyLapRecordsHint() }
            } else {
                items(derived.lapRecords) { record ->
                    // 仅 VALID/BEST 圈可点（Decision 圈行可点范围）：它们的 lapNumber=idx+1 严格对应
                    // getLapTelemetry(sessionId, lapNumber-1) 的 lapIndex；INVALID/INCOMPLETE 圈
                    // lapNumber 是合成值，点了 getLapTelemetry 越界返回 null → 白屏，故传 null 禁点。
                    val onLapClick: (() -> Unit)? = when (record.status) {
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
                    LapRecordRow(record = record, onClick = onLapClick)
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
