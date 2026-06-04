// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
    videoFilePath: String,
    playbackContext: LapPlaybackLoader.LapPlaybackContext,
    cursorAbsoluteTs: Long?,
    cursorSource: TriviewCursorSource,
    onCursorChangeFromVideo: (Long) -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val videoStart = playbackContext.videoStartedAtWallClock
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().also {
            FileLogger.d("LapVideoPanel", "ExoPlayer created path=$videoFilePath")
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
            FileLogger.d("LapVideoPanel", "ExoPlayer released")
        }
    }

    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }

    // 圈窗口 ∩ 视频覆盖(视频位置域;duration 未知时先用圈窗口,READY 后收紧)
    val clipStartPos = (playbackContext.lapStartWallClock - videoStart).coerceAtLeast(0L)
    val clipEndPos = if (durationMs > 0L) {
        (playbackContext.lapEndWallClock - videoStart).coerceIn(clipStartPos, durationMs)
    } else {
        (playbackContext.lapEndWallClock - videoStart).coerceAtLeast(clipStartPos + 1L)
    }

    LaunchedEffect(videoFilePath) {
        exoPlayer.setMediaItem(MediaItem.fromUri(videoFilePath))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = false
    }
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && durationMs <= 0L) {
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                    // 反馈 1:进面板定位到圈起点,不从视频文件 0 点(磨叽段)开始
                    val start = (playbackContext.lapStartWallClock - videoStart).coerceIn(0L, durationMs)
                    exoPlayer.seekTo(start)
                    positionMs = start
                    FileLogger.d(
                        "LapVideoPanel",
                        "READY duration=${durationMs}ms clip=[${start}ms..${(playbackContext.lapEndWallClock - videoStart).coerceAtMost(durationMs)}ms]",
                    )
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // 图表 → 视频(回环抑制:仅 CHART 来源 seek;clamp 圈窗口)
    LaunchedEffect(cursorAbsoluteTs, cursorSource, durationMs) {
        if (cursorSource == TriviewCursorSource.CHART && cursorAbsoluteTs != null && durationMs > 0L) {
            val pos = (cursorAbsoluteTs - videoStart).coerceIn(clipStartPos, clipEndPos)
            exoPlayer.seekTo(pos)
            positionMs = pos
            FileLogger.vSampled("LapVideoPanel", "triview-seek") { "chart→video seekTo=${pos}ms" }
        }
    }

    // 视频 → 图表(播放中 10Hz 回写)+ 圈终点自动暂停(反馈 1)
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val pos = exoPlayer.currentPosition
            positionMs = pos
            if (pos >= clipEndPos) {
                exoPlayer.pause()
                exoPlayer.seekTo(clipEndPos)
                positionMs = clipEndPos
                FileLogger.d("LapVideoPanel", "圈终点自动暂停 pos=${pos}ms clipEnd=${clipEndPos}ms")
                break
            }
            onCursorChangeFromVideo(videoStart + pos)
            delay(100)
        }
    }

    // overlay 帧解析(反馈 2):按当前 playheadWallClock 走共享管线(与全屏页/导出同源)
    var overlayFrame by remember { mutableStateOf<VideoOverlayTelemetry.OverlayFrame?>(null) }
    var overlayLap by remember { mutableStateOf<VideoOverlayTelemetry.LapResolution?>(null) }
    var overlayDeltaMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(positionMs) {
        updateOverlay(
            ctx = playbackContext,
            playheadWallClock = videoStart + positionMs,
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
                if (exoPlayer.isPlaying) {
                    exoPlayer.pause()
                } else {
                    // 停在圈终点再点播放 → 回到圈起点重播
                    if (positionMs >= clipEndPos) {
                        exoPlayer.seekTo(clipStartPos)
                        positionMs = clipStartPos
                    }
                    exoPlayer.play()
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
                value = positionMs.toFloat().coerceIn(clipStartPos.toFloat(), clipEndPos.toFloat()),
                onValueChange = { v ->
                    val pos = v.toLong().coerceIn(clipStartPos, clipEndPos)
                    positionMs = pos
                    exoPlayer.seekTo(pos)
                    onCursorChangeFromVideo(videoStart + pos)
                },
                valueRange = clipStartPos.toFloat()..clipEndPos.toFloat(),
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = TrackTechColors.Cyan,
                    activeTrackColor = TrackTechColors.Cyan,
                    inactiveTrackColor = TrackTechColors.Border,
                ),
            )
            IconButton(onClick = onFullscreen) {
                Icon(
                    imageVector = Icons.Filled.Fullscreen,
                    contentDescription = "Fullscreen",
                    tint = TrackTechColors.TextPrimary,
                )
            }
        }
    }
}
