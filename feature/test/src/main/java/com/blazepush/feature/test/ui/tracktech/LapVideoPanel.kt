// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.blazepush.feature.test.FileLogger
import com.blazepush.feature.test.export.LapPlaybackLoader
import com.blazepush.feature.test.usecase.VideoOverlayTelemetry
import kotlinx.coroutines.delay
import kotlin.math.abs

/** 三视图游标来源(design Decision 1 回环抑制):仅 CHART 来源触发视频 seekTo。 */
enum class TriviewCursorSource { CHART, VIDEO }

/**
 * 单圈详情内嵌视频面板(lap-detail-triview-panel round;2026-06-05 真机反馈二轮打磨)。
 *
 * - **进度条以本圈为坐标系**(反馈 1):值域 = 圈窗口∩视频覆盖([clipStartPos, clipEndPos],
 *   视频位置域);进面板自动 seek 到圈起点;播放越过圈终点自动暂停——"开头磨叽段"不进条。
 * - **overlay 图层**(反馈 2):PlayerView 上叠全屏页同款 [OverlayHud](速度表/G球/圈时+delta/
 *   小地图),帧数据由 [updateOverlay] 按当前 playheadWallClock 解析(共享管线,回放/导出/面板同源)。
 * - 三联动(spec R2):CHART 来源 cursor → seekTo(clamp 圈窗口);播放 ticker 10Hz/拖进度 →
 *   onCursorChangeFromVideo 回写(调用方吸附最近样本,驱动图表游标/地图亮点——反馈 3)。
 *
 * @author CC
 * @description embedded lap video panel: lap-window seek + overlay HUD + tri-view cursor sync
 * @date 2026-06-05
 */
@Composable
internal fun LapVideoPanel(
    playbackContext: LapPlaybackLoader.LapPlaybackContext,
    cursorAbsoluteTs: Long?,
    cursorSource: TriviewCursorSource,
    onCursorChangeFromVideo: (Long) -> Unit,
    onFullscreen: (currentWallClock: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val timeline = playbackContext.timelinePlan
    val slices = timeline.slices
    val lapStart = playbackContext.lapStartWallClock
    val lapEnd = playbackContext.lapEndWallClock.coerceAtLeast(lapStart + 1L)
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().also {
            FileLogger.d("LapVideoPanel", "ExoPlayer created slices=${slices.size}")
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
            FileLogger.d("LapVideoPanel", "ExoPlayer released")
        }
    }

    var positionWallClock by remember { mutableLongStateOf(lapStart) }
    var isPlaying by remember { mutableStateOf(false) }

    fun seekToWallClock(wallClock: Long) {
        val clamped = wallClock.coerceIn(lapStart, lapEnd)
        positionWallClock = clamped
        val slice = timeline.sliceAtWallClock(clamped)
        val index = slices.indexOf(slice)
        if (slice != null && index >= 0) {
            val sourcePosition = (clamped - slice.segment.startWallClock)
                .coerceIn(slice.sourceStartMs, slice.sourceEndMs)
            exoPlayer.seekTo(index, sourcePosition)
        } else {
            exoPlayer.pause()
        }
    }

    LaunchedEffect(slices) {
        exoPlayer.setMediaItems(slices.map { MediaItem.fromUri(it.segment.filePath) })
        exoPlayer.pauseAtEndOfMediaItems = true
        exoPlayer.prepare()
        exoPlayer.playWhenReady = false
        seekToWallClock(lapStart)
        FileLogger.d(
            "LapVideoPanel",
            "playlist ready segments=${slices.map { it.segment.segmentIndex }} lap=[$lapStart,$lapEnd]",
        )
    }

    // 图表 → 视频(回环抑制:仅 CHART 来源 seek;clamp 圈窗口)
    LaunchedEffect(cursorAbsoluteTs, cursorSource) {
        if (cursorSource == TriviewCursorSource.CHART && cursorAbsoluteTs != null) {
            seekToWallClock(cursorAbsoluteTs)
            FileLogger.vSampled("LapVideoPanel", "triview-seek") {
                "chart→video seek wallClock=$positionWallClock"
            }
        }
    }

    // 多段视频 → 图表（10Hz）。短切段 gap 立即跨越；真实缺失 gap 按时间轴推进并明确遮罩。
    LaunchedEffect(isPlaying, slices) {
        while (isPlaying) {
            val slice = timeline.sliceAtWallClock(positionWallClock)
            if (slice != null) {
                val index = slices.indexOf(slice)
                val expectedPosition = positionWallClock - slice.segment.startWallClock
                if (exoPlayer.currentMediaItemIndex != index ||
                    exoPlayer.playbackState == Player.STATE_ENDED ||
                    abs(exoPlayer.currentPosition - expectedPosition) > 750L
                ) {
                    exoPlayer.seekTo(index, expectedPosition.coerceIn(slice.sourceStartMs, slice.sourceEndMs))
                }
                if (exoPlayer.playbackState == Player.STATE_READY && !exoPlayer.isPlaying) {
                    exoPlayer.play()
                }
                val playerWallClock = slice.segment.startWallClock + exoPlayer.currentPosition
                positionWallClock = playerWallClock.coerceIn(slice.wallClockStart, slice.wallClockEnd)
                if (positionWallClock >= slice.wallClockEnd) {
                    exoPlayer.pause()
                    positionWallClock = slice.wallClockEnd
                }
            } else {
                exoPlayer.pause()
                val gap = timeline.gapAtWallClock(positionWallClock)
                positionWallClock = when {
                    gap == null -> positionWallClock + 100L
                    gap.isShortTechnicalGap -> gap.wallClockEnd
                    else -> minOf(gap.wallClockEnd, positionWallClock + 100L)
                }
            }
            if (positionWallClock >= lapEnd) {
                positionWallClock = lapEnd
                exoPlayer.pause()
                isPlaying = false
                FileLogger.d("LapVideoPanel", "圈终点自动暂停 wallClock=$positionWallClock")
            }
            onCursorChangeFromVideo(positionWallClock)
            delay(100)
        }
    }

    // overlay 帧解析(反馈 2):按当前 playheadWallClock 走共享管线(与全屏页/导出同源)
    var overlayFrame by remember { mutableStateOf<VideoOverlayTelemetry.OverlayFrame?>(null) }
    var overlayLap by remember { mutableStateOf<VideoOverlayTelemetry.LapResolution?>(null) }
    var overlayDeltaMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(positionWallClock) {
        updateOverlay(
            ctx = playbackContext,
            playheadWallClock = positionWallClock,
            onFrame = { overlayFrame = it },
            onLap = { overlayLap = it },
            onDelta = { overlayDeltaMs = it },
        )
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        player = exoPlayer
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            val gap = timeline.gapAtWallClock(positionWallClock)
            if (gap != null && !gap.isShortTechnicalGap) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.82f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "录像中断 ${"%.1f".format(gap.durationMs / 1000f)} 秒",
                        style = TrackTechTypography.UiTextBody,
                        color = TrackTechColors.TextMuted,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            OverlayHud(
                frame = overlayFrame,
                lap = overlayLap,
                deltaMs = overlayDeltaMs,
                trackPoints = playbackContext.trackPoints,
                scale = 0.5f, // 小面板紧凑缩放(全屏页保持 1f 原尺寸)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (isPlaying) {
                    isPlaying = false
                    exoPlayer.pause()
                } else {
                    // 停在圈终点再点播放 → 回到圈起点重播
                    if (positionWallClock >= lapEnd) {
                        seekToWallClock(lapStart)
                    }
                    isPlaying = true
                }
            }) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = TrackTechColors.Cyan,
                )
            }
            // 反馈 1:Slider 以圈窗口为值域——0% = 开圈,100% = 收圈
            Slider(
                value = positionWallClock.toFloat().coerceIn(lapStart.toFloat(), lapEnd.toFloat()),
                onValueChange = { v ->
                    seekToWallClock(v.toLong())
                    onCursorChangeFromVideo(positionWallClock)
                },
                valueRange = lapStart.toFloat()..lapEnd.toFloat(),
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = TrackTechColors.Cyan,
                    activeTrackColor = TrackTechColors.Cyan,
                    inactiveTrackColor = TrackTechColors.Border,
                ),
            )
            IconButton(onClick = { onFullscreen(positionWallClock) }) {
                Icon(
                    imageVector = Icons.Filled.Fullscreen,
                    contentDescription = "Fullscreen",
                    tint = TrackTechColors.TextPrimary,
                )
            }
        }
    }
}
