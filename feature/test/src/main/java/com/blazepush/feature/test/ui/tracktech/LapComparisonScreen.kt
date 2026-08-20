// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.blazepush.core.data.repository.TelemetryRepository
import com.blazepush.core.domain.model.TelemetryCrossingEvent
import com.blazepush.core.domain.model.LapEvidence
import com.blazepush.feature.test.FileLogger
import com.blazepush.feature.test.R
import com.blazepush.feature.test.ui.components.LapSeries
import com.blazepush.feature.test.ui.components.MultiLapSpeedChart
import com.blazepush.feature.test.ui.components.nearestSampleByElapsed
import com.blazepush.feature.test.viewmodel.TestSessionViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * 多圈比较屏（M3 lap-comparison-screen-with-cursor，time-axis 第一刀）。
 *
 * 用户从 session 详情屏点 COMPARE 进入：选 2-4 圈 → 多圈 speed 曲线按 `elapsedMsInLap`
 * 叠加（各圈一色）+ 共享 elapsed-time 游标拖动各圈同步取最近邻读数 + 图例（圈号色块 + 圈时 +
 * 游标处瞬时 speed）。
 *
 * Decision X-Axis：time-axis（elapsedMsInLap），**不**用距离轴重采样（距离轴留 follow-up round）。
 * Decision Cursor：hoist `cursorElapsedMs: Long?` single source of truth；MultiLapSpeedChart
 * 回写 + 图例 / 读数读同一 state；每圈各自 `nearestSampleByElapsed` 最近邻。
 * Decision Lap-Selection：chips 多选 2-4 圈；默认 BEST + 升序最多 3 个其他 VALID（≤4）。
 *
 * Risk 3（M2 路测 crash 教训 commit 65d6ada）：null / 不足 / loaded 分支 **MUST 用 if/else**，
 * **MUST NOT** 在 Column / LazyColumn scope lambda 用 early-return（重组 group stack 失衡 crash）。
 *
 * 视觉约束（Track Tech V2）：圈时是时间字符串 → Score 字体（**非 DSEG7/Mechanical**）；
 * 游标处瞬时 speed 是纯数字仪表读数 → Mechanical 允许；屏内每个直接 Text MUST maxLines=1 + Ellipsis；
 * chip / 图例 Row 配 weight 防窄屏挤压。
 *
 * @author CC
 * @description multi-lap comparison screen with shared elapsed-time cursor
 * @date 2026-05-30
 */
@Composable
fun LapComparisonScreen(
    navController: NavController,
    sessionId: String,
    telemetryRepository: TelemetryRepository = koinInject(),
    sessionViewModel: TestSessionViewModel = koinViewModel(),
) {
    var crossings by remember { mutableStateOf<List<TelemetryCrossingEvent>>(emptyList()) }
    var evidenceByLap by remember { mutableStateOf<Map<Int, LapEvidence>>(emptyMap()) }

    LaunchedEffect(sessionId) {
        crossings = telemetryRepository.getCrossings(sessionId)
        evidenceByLap = telemetryRepository.getLapEvidenceForSession(sessionId)
    }

    // 复用 LapSessionDetailScreen.deriveDetailMetrics（internal 同 module）得可选圈。
    val derived = remember(crossings, evidenceByLap) { deriveDetailMetrics(crossings, evidenceByLap) }

    // 可比较圈（VALID/BEST + 非 null timeMs）：圈选择 chips 的数据源。
    val selectableLaps = remember(derived) {
        derived.lapRecords.filter {
            (it.status == UiLapStatus.VALID || it.status == UiLapStatus.BEST) && it.timeMs != null
        }
    }

    // 默认选择（Decision Lap-Selection）：BEST + 升序最多 3 个其他 VALID（≤4）。可选圈 < 2 → emptyList。
    var selectedLapNumbers by remember { mutableStateOf<List<Int>>(emptyList()) }
    LaunchedEffect(selectableLaps) {
        val default = computeDefaultSelection(selectableLaps)
        selectedLapNumbers = default
        FileLogger.d(
            "LapCompare",
            "open compare sid=$sessionId selectable=${selectableLaps.size} default=$default",
        )
    }

    // 多圈加载（Risk 2 降级）：每选中 lapNumber 调 getLapTelemetry(sessionId, lapNumber-1)，
    // null skip（mapNotNull）+ FileLogger.e；构造 List<LapSeries>（color = assignLapColors）。
    var series by remember { mutableStateOf<List<LapSeries>>(emptyList()) }
    LaunchedEffect(sessionId, selectedLapNumbers) {
        val colors = assignLapColors(selectedLapNumbers)
        val loaded = selectedLapNumbers.mapIndexedNotNull { index, lapNumber ->
            val telemetry = telemetryRepository.getLapTelemetry(sessionId, lapNumber - 1)
            if (telemetry == null) {
                FileLogger.e("LapCompare", "getLapTelemetry null sid=$sessionId lapNumber=$lapNumber")
                null
            } else {
                LapSeries(
                    lapNumber = lapNumber,
                    color = colors.getOrElse(index) { TrackTechColors.TextSecondary },
                    samples = telemetry.samples,
                )
            }
        }
        series = loaded
        FileLogger.d(
            "LapCompare",
            "loaded sid=$sessionId series=${loaded.size} samples=${loaded.map { it.samples.size }}",
        )
    }

    // 共享游标 single source of truth（Decision Cursor）：MultiLapSpeedChart 回写 elapsedMs，
    // 图例 / 读数读同一 state。25Hz 拖动用 v 级别埋点（可被 level 过滤）。
    var cursorElapsedMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(cursorElapsedMs) {
        FileLogger.v("LapCompare", "cursor elapsed=$cursorElapsedMs")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrackTechColors.Background),
    ) {
        LapCompareHeader(onBack = { navController.popBackStack() })

        // Risk 3：if/else 分支（不足 2 可选圈降级 / 可比较 loaded），MUST NOT early-return。
        if (selectableLaps.size < 2) {
            // 可选圈本就 < 2：session 圈不足，显式提示（不崩溃不白屏）。
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.comparison_not_enough_laps),
                    style = TrackTechTypography.ScoreSmall,
                    color = TrackTechColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    LapSelectionChips(
                        selectableLaps = selectableLaps,
                        selectedLapNumbers = selectedLapNumbers,
                        onToggle = { lapNumber ->
                            selectedLapNumbers = toggleLapSelection(selectedLapNumbers, lapNumber)
                            FileLogger.d("LapCompare", "select=$selectedLapNumbers")
                        },
                    )
                }
                item {
                    ChartCard(title = stringResource(R.string.comparison_speed_overlay)) {
                        // 降级态（Risk 2）：series.size < 2 时占位，不画图（if/else，不 early-return）。
                        if (series.size < 2) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.comparison_select_two_hint),
                                    style = TrackTechTypography.ScoreSmall,
                                    color = TrackTechColors.TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else {
                            MultiLapSpeedChart(
                                series = series,
                                cursorElapsedMs = cursorElapsedMs,
                                onCursorChange = { cursorElapsedMs = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                            )
                        }
                    }
                }
                item {
                    ChartCard(title = stringResource(R.string.comparison_legend)) {
                        LapCompareLegend(
                            series = series,
                            selectableLaps = selectableLaps,
                            cursorElapsedMs = cursorElapsedMs,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 纯函数：从可比较圈算默认选择（Decision Lap-Selection）。
 *
 * 选 BEST 圈 + 圈时升序最多 3 个其他 VALID 圈（合计 ≤4）。可比较圈 < 2 → emptyList（触发降级）。
 * 抽 internal 纯函数便于 JVM 单测。
 */
internal fun computeDefaultSelection(records: List<UiLapRecord>): List<Int> {
    val valid = records.filter {
        (it.status == UiLapStatus.VALID || it.status == UiLapStatus.BEST) && it.timeMs != null
    }
    if (valid.size < 2) return emptyList()
    val best = valid.firstOrNull { it.status == UiLapStatus.BEST }
    val others = valid
        .filter { best == null || it.lapNumber != best.lapNumber }
        .sortedBy { it.timeMs ?: Long.MAX_VALUE }
    val selected = mutableListOf<Int>()
    if (best != null) selected += best.lapNumber
    for (record in others) {
        if (selected.size >= 4) break
        selected += record.lapNumber
    }
    return selected
}

/**
 * 纯函数：按选中顺序分配调色板 [Purple, Cyan, Green, Red]（≤4 圈）。
 * BEST 圈在 computeDefaultSelection 排首 → 优先 Purple，与圈列表 BEST 紫色语义一致。
 */
internal fun assignLapColors(selectedLapNumbers: List<Int>): List<Color> {
    val palette = listOf(
        TrackTechColors.Purple,
        TrackTechColors.Cyan,
        TrackTechColors.Green,
        TrackTechColors.Red,
    )
    return selectedLapNumbers.mapIndexed { index, _ ->
        palette.getOrElse(index) { TrackTechColors.TextSecondary }
    }
}

/**
 * 纯函数：toggle 圈选择，受 [2,4] 约束。
 * - 已选中 → 取消（但剩 2 不再减，保证下限 2）
 * - 未选中 → 加入（但已满 4 不再加，保证上限 4）
 * 抽 internal 纯函数便于 JVM 单测。
 */
internal fun toggleLapSelection(current: List<Int>, lapNumber: Int): List<Int> {
    return if (current.contains(lapNumber)) {
        if (current.size <= 2) current else current.filter { it != lapNumber }
    } else {
        if (current.size >= 4) current else current + lapNumber
    }
}

@Composable
private fun LapCompareHeader(onBack: () -> Unit) {
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
            text = stringResource(R.string.comparison_title),
            style = TrackTechTypography.RacingTitleMedium,
            color = TrackTechColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChartCard(
    title: String,
    content: @Composable () -> Unit,
) {
    CutCornerPanel(
        modifier = Modifier.fillMaxWidth(),
        cutSize = 8.dp,
        cutCorners = cutCornersAll,
        contentPadding = 12.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = title,
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Cyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            content()
        }
    }
}

/**
 * 圈选择 chips：每个可比较圈一个 chip（Lap N + 圈时 Score + 选中态色块）。
 * toggle 受 [2,4] 约束（toggleLapSelection）。flow 用普通换行 Column 多行排（避免水平挤压）。
 */
@Composable
private fun LapSelectionChips(
    selectableLaps: List<UiLapRecord>,
    selectedLapNumbers: List<Int>,
    onToggle: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.comparison_select_range),
            style = TrackTechTypography.UiTextLabel,
            color = TrackTechColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 每行最多放 3 chip，避免窄屏水平挤压（固定每 chip weight 均分）。
        selectableLaps.chunked(3).forEach { rowLaps ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowLaps.forEach { record ->
                    val isSelected = selectedLapNumbers.contains(record.lapNumber)
                    LapChip(
                        record = record,
                        isSelected = isSelected,
                        onClick = { onToggle(record.lapNumber) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 末行不足 3 个时补空 weight，保持列宽一致（不挤压）。
                repeat(3 - rowLaps.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LapChip(
    record: UiLapRecord,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (isSelected) TrackTechColors.Purple else TrackTechColors.BorderAlpha60
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (isSelected) TrackTechColors.PurpleAlpha20 else TrackTechColors.Surface,
            )
            .border(width = 1.dp, color = accent, shape = RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.comparison_lap_number, record.lapNumber),
            style = TrackTechTypography.UiTextLabel,
            color = if (isSelected) TrackTechColors.Purple else TrackTechColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 圈时是时间字符串 → Score 字体（V2：MUST NOT Mechanical/DSEG7）
        Text(
            text = formatComparisonLapTime(record.timeMs),
            style = TrackTechTypography.UiTextBody,
            color = TrackTechColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 图例 + 游标读数：每选中圈一行（色块 + Lap N + 圈时 Score）；cursorElapsedMs != null 时
 * 追加该圈 nearestSampleByElapsed 处瞬时 speed（纯数字仪表 → Mechanical 允许）。
 * Row 配 weight 约束（不裸 SpaceBetween），防窄屏挤压。
 */
@Composable
private fun LapCompareLegend(
    series: List<LapSeries>,
    selectableLaps: List<UiLapRecord>,
    cursorElapsedMs: Long?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        series.forEach { lap ->
            val record = selectableLaps.firstOrNull { it.lapNumber == lap.lapNumber }
            val nearest = if (cursorElapsedMs != null) {
                nearestSampleByElapsed(lap.samples, cursorElapsedMs)
            } else null
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(lap.color),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.comparison_lap_number, lap.lapNumber),
                    style = TrackTechTypography.UiTextLabel,
                    color = TrackTechColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(64.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatComparisonLapTime(record?.timeMs),
                    style = TrackTechTypography.UiTextBody,
                    color = TrackTechColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (nearest != null) {
                    Spacer(Modifier.width(8.dp))
                    // 游标处瞬时 speed 是纯数字仪表读数 → Mechanical 允许（V2 仪表瞬时数字例外）。
                    MetricNumber(
                        value = "${nearest.speedKmh.toInt()}",
                        kind = MetricKind.Mechanical,
                        size = MetricSize.Small,
                        unit = "km/h",
                    )
                }
            }
        }
    }
}

/** 圈时格式化（m:ss.mmm）。与 LapDetailScreen.formatLapDetailTime 同语义（时间字符串 → Score 字体）。 */
private fun formatComparisonLapTime(ms: Long?): String {
    if (ms == null || ms <= 0L) return "--:--.---"
    val totalSec = ms / 1000
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    val millis = ms % 1000
    return "%d:%02d.%03d".format(minutes, seconds, millis)
}
