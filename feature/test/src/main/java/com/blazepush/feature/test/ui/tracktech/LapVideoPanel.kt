// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
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
import kotlinx.coroutines.delay

/** 三视图游标来源(design Decision 1 回环抑制):仅 CHART 来源触发视频 seekTo。 */
enum class TriviewCursorSource { CHART, VIDEO }

/**
 * 单圈详情内嵌视频面板(lap-detail-triview-panel round)。
 *
 * - 自绘控制条(播放/暂停 + Slider 进度 + 全屏),PlayerView useController=false。
 * - 三联动(spec R2):CHART 来源的 cursorAbsoluteTs → seekTo(cursor - videoStart,clamp);
 *   播放 ticker/拖进度 → onCursorChangeFromVideo(videoStart + position)回写(VIDEO 来源,
 *   ≤10Hz)——回写不再触发 seek(回环抑制)。
 * - 生命周期:remember + DisposableEffect release;进全屏(navigate)旧 composition 销毁
 *   即释放,返回重建(design Decision 4)。
 *
 * @author CC
 * @description embedded lap video panel with seek + tri-view cursor sync
 * @date 2026-06-05
 */
@Composable
fun LapVideoPanel(
    videoFilePath: String,
    videoStartedAtWallClock: Long,
    cursorAbsoluteTs: Long?,
    cursorSource: TriviewCursorSource,
    onCursorChangeFromVideo: (Long) -> Unit,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
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
                    FileLogger.d("LapVideoPanel", "READY duration=${durationMs}ms videoStart=$videoStartedAtWallClock")
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // 图表 → 视频(回环抑制:仅 CHART 来源 seek;spec R2 反例锁)
    LaunchedEffect(cursorAbsoluteTs, cursorSource, durationMs) {
        if (cursorSource == TriviewCursorSource.CHART && cursorAbsoluteTs != null && durationMs > 0L) {
            val pos = (cursorAbsoluteTs - videoStartedAtWallClock).coerceIn(0L, durationMs)
            exoPlayer.seekTo(pos)
            positionMs = pos
            FileLogger.vSampled("LapVideoPanel", "triview-seek") { "chart→video seekTo=${pos}ms" }
        }
    }

    // 视频 → 图表(播放中 10Hz 回写,VIDEO 来源)
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val pos = exoPlayer.currentPosition
            positionMs = pos
            onCursorChangeFromVideo(videoStartedAtWallClock + pos)
            delay(100)
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = exoPlayer
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
            }) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = TrackTechColors.Cyan,
                )
            }
            Slider(
                value = positionMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
                onValueChange = { v ->
                    val pos = v.toLong().coerceIn(0L, durationMs)
                    positionMs = pos
                    exoPlayer.seekTo(pos)
                    onCursorChangeFromVideo(videoStartedAtWallClock + pos) // 拖进度 → 图表跟随(VIDEO 来源)
                },
                valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
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
