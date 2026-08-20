// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import com.blazepush.core.data.repository.TelemetryRepository
import com.blazepush.core.domain.model.LapTelemetry
import com.blazepush.core.domain.model.LapConfidencePolicy
import com.blazepush.core.domain.model.LapConfidence
import com.blazepush.core.domain.model.LapEvidence
import com.blazepush.core.domain.model.LapReviewProvenance
import com.blazepush.core.domain.model.LapTelemetrySample
import com.blazepush.core.domain.usecase.AccelerationSmoother
import com.blazepush.core.domain.usecase.GRAVITY_MS2
import com.blazepush.core.domain.usecase.TimedSpeedSample
import com.blazepush.feature.test.FileLogger
import com.blazepush.feature.test.R
import com.blazepush.feature.test.ui.components.AccelTimeChart
import com.blazepush.feature.test.ui.components.SectorBar
import com.blazepush.feature.test.ui.components.SpeedTimeChart
import com.blazepush.feature.test.ui.components.TrackPolylineMap
import com.blazepush.feature.test.datastore.UserProfileRepository
import com.blazepush.feature.test.export.LapPlaybackLoader
import com.blazepush.feature.test.recording.VideoTelemetrySync
import com.blazepush.feature.test.repository.TrackCatalog
import com.blazepush.feature.test.viewmodel.TestSessionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * 单圈详情屏（M2 lap-detail-screen-with-cursor）。
 *
 * 4 个共享游标的回放组件：SpeedTimeChart / AccelTimeChart / SectorBar / TrackPolylineMap。
 * - R1：accelerationG 在 UI 层用 [AccelerationSmoother] 从 speedKmh 反算（reader 恒返回 null），
 *   只喂给 AccelTimeChart；其余 3 组件用原始 samples（不读 accelerationG）。
 * - R2：sectorBoundaries 直接消费 getLapTelemetry 返回的多段（future-sector-derivation 已合回）。
 * - Cursor：hoist 单一 cursorAbsoluteTs state；同圈内 4 组件共享同一 samples，absoluteTsMs
 *   精确相等匹配可命中同一时间点。
 *
 * 视觉约束（Track Tech V2）：圈时是时间字符串 → Score 字体（**非 DSEG7/Mechanical**）；
 * 屏内每个直接 Text MUST maxLines = 1 + Ellipsis；label-value Row 配 weight。
 *
 * @author CC
 * @description single-lap detail screen with shared cursor playback
 * @date 2026-05-30
 */
@Composable
fun LapDetailScreen(
    navController: NavController,
    sessionId: String,
    lapIndex: Int,
    telemetryRepository: TelemetryRepository = koinInject(),
    trackCatalog: TrackCatalog = koinInject(),
    userProfileRepository: UserProfileRepository = koinInject(),
    sessionViewModel: TestSessionViewModel = koinViewModel(),
) {
    var lapTelemetry by remember { mutableStateOf<LapTelemetry?>(null) }
    var lapEvidence by remember { mutableStateOf<LapEvidence?>(null) }

    // lap-detail-triview-panel:视频面板数据(2026-06-05 二轮:改用 LapPlaybackLoader 共享
    // 加载管线——overlay 帧/圈窗口/赛道点与全屏页同源;load 失败(无视频/无覆盖)即不渲染面板)。
    var videoPlaybackContext by remember { mutableStateOf<LapPlaybackLoader.LapPlaybackContext?>(null) }
    // round fix-lap-detail-ux-three-touch-issues Bug 2 二轮：sessionHasVideo 三态
    // (null=待判定乐观假设 / true=有视频 / false=无视频)。null/true 阶段 VIDEO panel
    // 立即占位到 list[0]，加载完替换为真 LapVideoPanel；false 阶段 VIDEO 从 visiblePanels filter 掉。
    // 不再依赖加载完后 scrollToItem 锚定（消除"先到非 VIDEO 再滑过去"的视觉跳跃）。
    var sessionHasVideo by remember { mutableStateOf<Boolean?>(null) }
    var videoCtxLoadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId, lapIndex) {
        // 先快查 session.videoFilePath（Room 单表 select，<50ms）判定 sessionHasVideo —
        // 让 VIDEO panel 占位状态尽快稳定（视频 session：保持占位；无视频 session：filter 掉）
        val sessionVideo = withContext(Dispatchers.IO) {
            val session = telemetryRepository.getSession(sessionId)
            val segments = telemetryRepository.getVideoSegments(sessionId)
            segments.isNotEmpty() || session?.videoFilePath != null
        }
        sessionHasVideo = sessionVideo
        FileLogger.d("LapDetail", "sessionHasVideo=$sessionHasVideo sid=$sessionId")

        val result = telemetryRepository.getLapTelemetry(sessionId, lapIndex)
        if (result != null) {
            FileLogger.d(
                "LapDetail",
                "loaded sid=$sessionId idx=$lapIndex samples=${result.samples.size} sectors=${result.sectorBoundaries.size}",
            )
        } else {
            FileLogger.e("LapDetail", "getLapTelemetry null sid=$sessionId idx=$lapIndex")
        }
        lapTelemetry = result
        // Persisted lap keys are 1-based; getLapTelemetry indices are 0-based.
        lapEvidence = telemetryRepository.getLapEvidence(sessionId, lapIndex + 1)

        // 视频面板数据(spec R1):LapPlaybackLoader 共享管线(全屏页/导出同源)——
        // 无 session/无视频/无圈/无样本 → null → 占位变 "视频不可用"
        if (sessionHasVideo == true) {
            val loaded = withContext(Dispatchers.IO) {
                LapPlaybackLoader.load(sessionId, lapIndex, telemetryRepository, trackCatalog)
            }
            if (loaded != null) {
                videoPlaybackContext = loaded.second
                FileLogger.d(
                    "LapDetail",
                    "triview ctx lap=[${loaded.second.lapStartWallClock}..${loaded.second.lapEndWallClock}] " +
                        "videoStart=${loaded.second.videoStartedAtWallClock} frames=${loaded.second.frames.size}",
                )
            } else {
                videoPlaybackContext = null
                videoCtxLoadFailed = true
                FileLogger.e("LapDetail", "video ctx load failed sid=$sessionId idx=$lapIndex")
            }
        }
    }

    // 共享游标 single source of truth（Cursor 决策）：SpeedTimeChart / AccelTimeChart 发起变更，
    // 4 组件入参全传它。25Hz 拖动用 v 级别埋点（可被 level 过滤）。
    var cursorAbsoluteTs by remember { mutableStateOf<Long?>(null) }

    // round fix-lap-detail-ux-three-touch-issues：LazyColumn 状态 hoist，
    // 视频面板异步加载完成后由 LaunchedEffect 锚到可见区顶部。
    val listState = rememberLazyListState()

    // 三联动回环抑制(triview design Decision 1):标记最近一次 cursor 变更来源;
    // 仅 CHART 来源触发视频 seek,VIDEO 来源(播放回写/拖进度)不再回环 seek。
    var cursorSource by remember { mutableStateOf(TriviewCursorSource.CHART) }

    // 面板顺序(design Decision 3):DataStore 偏好,默认顺序兜底;拖拽落定持久化
    val orderScope = rememberCoroutineScope()
    val orderSerialized by userProfileRepository.lapDetailPanelOrder.collectAsState(initial = "")
    val panelOrder = remember(orderSerialized) { LapDetailPanelOrder.parse(orderSerialized) }

    // R1 accelerationG 派生：remember(lapTelemetry) 缓存一次（lapTelemetry 只在 LaunchedEffect 加载一次），
    // 不在重组热路径。只喂给 AccelTimeChart。
    val accelSamples = remember(lapTelemetry) {
        lapTelemetry?.let { telemetry ->
            val derived = deriveAccelerationG(telemetry.samples)
            FileLogger.d("LapDetail", "accel derived sid=$sessionId idx=$lapIndex count=${derived.size}")
            derived
        } ?: emptyList()
    }

    // 游标关键状态转移埋点（road-test-first 强制）：用 LaunchedEffect 在 cursorAbsoluteTs 每次
    // 变化时记一条 v 级别日志（25Hz 拖动频率用 v 级别可被 level 过滤）。不内联进 onCursorChange
    // lambda（保持 `onCursorChange = { cursorAbsoluteTs = it }` 字面量供 contract test 锁定）。
    LaunchedEffect(cursorAbsoluteTs) {
        FileLogger.v("LapDetail", "cursor ts=$cursorAbsoluteTs")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrackTechColors.Background),
    ) {
        LapDetailHeader(onBack = { navController.popBackStack() })
        val quality = LapConfidencePolicy.evaluate(lapEvidence)
        val confidenceLabel = when (quality.confidence) {
            LapConfidence.Clean -> stringResource(R.string.detail_quality_clean)
            LapConfidence.Reviewed -> stringResource(R.string.detail_quality_reviewed)
            LapConfidence.Estimated -> stringResource(R.string.detail_quality_estimated)
            LapConfidence.Incomplete -> stringResource(R.string.detail_quality_incomplete)
        }
        val provenanceLabel = when (quality.provenance) {
            LapReviewProvenance.AutomaticEvidence -> stringResource(R.string.detail_provenance_automatic)
            LapReviewProvenance.ManualApproved -> stringResource(R.string.detail_provenance_approved)
            LapReviewProvenance.ManualRejected -> stringResource(R.string.detail_provenance_rejected)
            LapReviewProvenance.LegacyUnknown -> stringResource(R.string.detail_provenance_legacy)
        }
        Text(
            text = stringResource(R.string.detail_quality, confidenceLabel, provenanceLabel),
            style = TrackTechTypography.UiTextSmall,
            color = TrackTechColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        val telemetry = lapTelemetry
        if (telemetry == null) {
            // 降级态（Risk 3）：null lapTelemetry 显式占位，不崩溃不白屏。
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.detail_no_lap_data),
                    style = TrackTechTypography.ScoreSmall,
                    color = TrackTechColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            // round fix-lap-detail-ux-three-touch-issues Bug 2 二轮：占位准入用 sessionHasVideo
            // 三态（null=待判定乐观假设/true=有视频/false=无视频）。null/true 阶段 VIDEO 立即占位到 list[0]；
            // false 阶段 VIDEO 从 visiblePanels filter 掉。videoCtxReady 控制 VIDEO panel 内部三态（占位 vs 真内容）。
            val videoSlotEligible = sessionHasVideo != false
            val videoCtxReady = videoPlaybackContext != null
            // 反馈 3(2026-06-05):视频回写的任意毫秒值吸附到最近样本 absoluteTsMs——
            // 图表游标/地图亮点是精确相等匹配,不吸附永远 miss
            val sampleWallClocks = remember(telemetry) { telemetry.samples.map { it.absoluteTsMs } }
            val visiblePanels = panelOrder.filter { it != LapDetailPanelId.VIDEO || videoSlotEligible }

            // 长按拖拽 reorder(design Decision 3):draggingId + 累计位移;跨过相邻面板
            // 实测高度的一半即交换;松手序列化持久化。
            var draggingId by remember { mutableStateOf<LapDetailPanelId?>(null) }
            var dragOffsetY by remember { mutableFloatStateOf(0f) }
            val panelHeights = remember { mutableMapOf<LapDetailPanelId, Int>() }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(visiblePanels, key = { it.name }) { panelId ->
                    val isDragging = draggingId == panelId
                    Box(
                        modifier = Modifier
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer { translationY = if (isDragging) dragOffsetY else 0f }
                            .onGloballyPositioned { panelHeights[panelId] = it.size.height }
                            .pointerInput(panelId, visiblePanels) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingId = panelId
                                        dragOffsetY = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetY += dragAmount.y
                                        val idx = visiblePanels.indexOf(panelId)
                                        val neighborIdx = if (dragOffsetY > 0) idx + 1 else idx - 1
                                        val neighbor = visiblePanels.getOrNull(neighborIdx)
                                        if (neighbor != null) {
                                            val threshold = (panelHeights[neighbor] ?: 0) / 2f
                                            if (threshold > 0 && kotlin.math.abs(dragOffsetY) > threshold) {
                                                val newOrder = LapDetailPanelOrder.move(
                                                    panelOrder, panelId,
                                                    panelOrder.indexOf(neighbor),
                                                )
                                                orderScope.launch {
                                                    userProfileRepository.setLapDetailPanelOrder(
                                                        LapDetailPanelOrder.serialize(newOrder),
                                                    )
                                                }
                                                dragOffsetY = 0f
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        draggingId = null
                                        dragOffsetY = 0f
                                    },
                                    onDragCancel = {
                                        draggingId = null
                                        dragOffsetY = 0f
                                    },
                                )
                            },
                    ) {
                        when (panelId) {
                            LapDetailPanelId.VIDEO -> ChartCard(title = stringResource(R.string.detail_panel_video)) {
                                if (videoCtxReady) {
                                    LapVideoPanel(
                                        playbackContext = videoPlaybackContext!!,
                                        cursorAbsoluteTs = cursorAbsoluteTs,
                                        cursorSource = cursorSource,
                                        onCursorChangeFromVideo = { wc ->
                                            cursorSource = TriviewCursorSource.VIDEO
                                            // 吸附最近样本(反馈 3:驱动图表游标/地图亮点)
                                            if (sampleWallClocks.isNotEmpty()) {
                                                val idx = VideoTelemetrySync.findNearestSampleIndex(wc, sampleWallClocks)
                                                cursorAbsoluteTs = sampleWallClocks[idx]
                                            }
                                        },
                                        onFullscreen = { wc ->
                                            // 进度接力:全屏从面板当前时刻继续(lap-detail-triview-panel)
                                            navController.navigate("lap_video/$sessionId/$lapIndex?startWc=$wc")
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    // round fix-lap-detail-ux-three-touch-issues Bug 2 二轮：占位
                                    // 锁定 LapVideoPanel 默认 16:9 高度避免 ctx ready 后 layout 跳动
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(16f / 9f),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = if (videoCtxLoadFailed) "视频不可用" else "加载视频中…",
                                            style = TrackTechTypography.ScoreSmall,
                                            color = TrackTechColors.TextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            LapDetailPanelId.OVERVIEW ->
                                LapOverviewSection(lapIndex = lapIndex, telemetry = telemetry)
                            LapDetailPanelId.SPEED -> ChartCard(title = stringResource(R.string.detail_panel_speed)) {
                                SpeedTimeChart(
                                    samples = telemetry.samples,
                                    cursorAbsoluteTs = cursorAbsoluteTs,
                                    onCursorChange = {
                                        cursorSource = TriviewCursorSource.CHART
                                        cursorAbsoluteTs = it
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp),
                                )
                            }
                            LapDetailPanelId.ACCEL -> ChartCard(title = stringResource(R.string.detail_panel_accel)) {
                                AccelTimeChart(
                                    samples = accelSamples,
                                    cursorAbsoluteTs = cursorAbsoluteTs,
                                    onCursorChange = {
                                        cursorSource = TriviewCursorSource.CHART
                                        cursorAbsoluteTs = it
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp),
                                )
                            }
                            LapDetailPanelId.SECTORS -> ChartCard(title = stringResource(R.string.detail_panel_sectors)) {
                                SectorBar(
                                    sectorBoundaries = telemetry.sectorBoundaries,
                                    lapStartWallClock = telemetry.lapStartWallClock,
                                    lapEndWallClock = telemetry.lapEndWallClock,
                                    cursorAbsoluteTs = cursorAbsoluteTs,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                // 本圈各 sector 耗时（lap-detail-sector-split-times round）：sector 是看圈速的关注点。
                                val sectorSplits = computeSectorSplits(
                                    telemetry.sectorBoundaries,
                                    telemetry.lapEndWallClock,
                                )
                                if (sectorSplits.size >= 2) {
                                    sectorSplits.forEachIndexed { index, splitMs ->
                                        OverviewRow(
                                            label = stringResource(R.string.detail_sector, index + 1),
                                            value = formatLapDetailTime(splitMs),
                                        )
                                    }
                                } else {
                                    Text(
                                        text = stringResource(R.string.detail_no_sectors),
                                        style = TrackTechTypography.UiTextLabel,
                                        color = TrackTechColors.TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            LapDetailPanelId.TRACK -> ChartCard(title = stringResource(R.string.detail_panel_track)) {
                                TrackPolylineMap(
                                    samples = telemetry.samples,
                                    cursorAbsoluteTs = cursorAbsoluteTs,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * R1 纯函数：从 samples 的 speedKmh + absoluteTsMs 序列反算每个 sample 的 accelerationG（G 单位）。
 *
 * 用 [AccelerationSmoother] 得 m/s² 加速度（与输入索引一一对应），`/ GRAVITY_MS2` 转 G，
 * 只 `copy(accelerationG = gValue)`（absoluteTsMs / elapsedMsInLap / lat / lon / speedKmh 不变，
 * 保证游标精确相等匹配仍命中同一时间点）。
 *
 * - 空列表 → 空列表
 * - N >= 1 → 每个 accelerationG 非 null（N = 1 时 AccelerationSmoother 返回 [0.0] → accelerationG = 0.0 非 null）
 *
 * 抽 internal 纯函数（不引 androidx），便于 JVM 单测断言 spec scenario「accelerationG 非空喂 AccelTimeChart」。
 */
internal fun deriveAccelerationG(samples: List<LapTelemetrySample>): List<LapTelemetrySample> {
    if (samples.isEmpty()) return emptyList()
    val msPerS2 = AccelerationSmoother.compute(
        samples.map { TimedSpeedSample(it.absoluteTsMs, it.speedKmh) },
    )
    return samples.mapIndexed { index, sample ->
        sample.copy(accelerationG = msPerS2[index] / GRAVITY_MS2)
    }
}

@Composable
private fun LapDetailHeader(onBack: () -> Unit) {
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
            text = stringResource(R.string.detail_lap_title),
            style = TrackTechTypography.RacingTitleMedium,
            color = TrackTechColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LapOverviewSection(
    lapIndex: Int,
    telemetry: LapTelemetry,
) {
    // top speed in lap（可选）：空 samples 退化 null → "--"
    val topSpeedKmh = remember(telemetry) {
        telemetry.samples.maxOfOrNull { it.speedKmh }
    }
    CutCornerPanel(
        modifier = Modifier.fillMaxWidth(),
        cutSize = 8.dp,
        cutCorners = cutCornersAll,
        contentPadding = 16.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.detail_lap_number, lapIndex + 1),
                style = TrackTechTypography.RacingTitleMedium,
                color = TrackTechColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 圈时是时间字符串 → Score 字体（V2 约束：MUST NOT Mechanical/DSEG7）
            OverviewRow(label = stringResource(R.string.detail_lap_time), value = formatLapDetailTime(telemetry.lapDurationMs))
            OverviewRow(label = stringResource(R.string.detail_track), value = telemetry.trackNameSnapshot ?: "—")
            OverviewRow(
                label = stringResource(R.string.detail_top_speed),
                value = topSpeedKmh?.let { "%.1f km/h".format(it) } ?: "--",
            )
        }
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

/**
 * 本圈各 sector 耗时（split）：sectorBoundaries（[lapStart, s1, s2, ...]）末尾接 lapEndWallClock，
 * 相邻差即各段耗时。返回 sectorBoundaries.size 个 split（最后一段到 lapEnd）。空 boundaries → 空。
 * 抽 internal 纯函数便于 JVM 单测。
 */
internal fun computeSectorSplits(sectorBoundaries: List<Long>, lapEndWallClock: Long): List<Long> {
    if (sectorBoundaries.isEmpty()) return emptyList()
    val bounds = sectorBoundaries + lapEndWallClock
    return bounds.zipWithNext { a, b -> b - a }
}

/** 圈时格式化（m:ss.mmm）。与 LapSessionDetailScreen.formatLapTime 同语义（时间字符串 → Score 字体）。 */
private fun formatLapDetailTime(ms: Long?): String {
    if (ms == null || ms <= 0L) return "--:--.---"
    val totalSec = ms / 1000
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    val millis = ms % 1000
    return "%d:%02d.%03d".format(minutes, seconds, millis)
}
