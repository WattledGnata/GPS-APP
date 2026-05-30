// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.blazepush.core.data.repository.TelemetryRepository
import com.blazepush.core.domain.model.TelemetrySession
import com.blazepush.feature.test.FileLogger
import com.blazepush.feature.test.export.LapPlaybackLoader
import com.blazepush.feature.test.model.track.GeoPoint
import com.blazepush.feature.test.recording.VideoTelemetrySync
import com.blazepush.feature.test.repository.TrackCatalog
import com.blazepush.feature.test.usecase.GaugeMath
import com.blazepush.feature.test.usecase.VideoOverlayTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.abs

// round redo-video-overlay-visual-gauges：UI 数据刷新降频 30fps→10Hz（采样仍 25Hz 不动，
// 只降 overlay playhead 驱动的数据刷新/重组频率，减渲染负担）。视频本身由 ExoPlayer 自渲染不受影响。
private const val PLAYHEAD_TICK_MS = OVERLAY_UI_REFRESH_PERIOD_MS // 100ms = 10Hz
private const val TAG = "VideoOverlay"

/**
 * 进屏一次性读好的"单圈"按圈回放上下文（轮询时只查表不算 IO）。
 *
 * round video-export-burned-overlay Round B：原 data class 下沉到 [LapPlaybackLoader.LapPlaybackContext]
 * 供回放屏 + 导出管线共享同源加载（避免导出端另起一套加载逻辑导致 overlay 数据与回放漂移）。
 * 回放屏内部沿用 `LapPlaybackContext` 名字（typealias），代码无需改动。
 */
private typealias LapPlaybackContext = LapPlaybackLoader.LapPlaybackContext

/**
 * 按圈回放的视频实时叠加遥测 HUD 播放屏
 * （round redo-video-playback-per-lap-with-blackout，重构自 video-overlay-realtime-playback）。
 *
 * ## 播放模型：圈时间轴主导，视频跟随
 *
 * 不再由视频 currentPosition 驱动 overlay；改由 **playheadWallClock（圈 wallClock 时间轴）主导**：
 * - 圈时间轴范围 = [lapStartWallClock - 3000ms, lapEndWallClock]（进圈定位圈起点前 3 秒，圈播完即停）。
 * - 视频覆盖段 = [videoStartedAtWallClock, videoStartedAtWallClock + videoDurationMs]。
 * - **playhead 落覆盖段内**：ExoPlayer 自然时钟 play，playheadWallClock = videoStart + player.currentPosition；
 *   overlay 按 playheadWallClock 查样本。
 * - **playhead 落覆盖段外**（圈头早于视频起点 / 圈尾晚于视频终点）：ExoPlayer pause + 黑色遮罩盖
 *   PlayerView，30fps ticker 以 1x 实时推进 playheadWallClock；overlay 继续按 playheadWallClock 查样本。
 *
 * overlay 四角标（速度/圈速/G/小地图）始终浮最上层；黑屏段数据照常叠。
 *
 * @author CC
 * @description per-lap video playback with playhead-driven overlay + blackout segments
 * @date 2026-05-31
 */
@UnstableApi
@Composable
fun LapVideoPlaybackScreen(
    navController: NavController,
    sessionId: String,
    lapIndex: Int,
    telemetryRepository: TelemetryRepository = koinInject(),
    trackCatalog: TrackCatalog = koinInject(),
) {
    val context = LocalContext.current
    val view = LocalView.current

    // 横屏锁 + 常亮（复用 LapLiveScreen 范式）
    DisposableEffect(Unit) {
        val activity = context.findPlaybackActivity()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        view.keepScreenOn = true
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            view.keepScreenOn = false
        }
    }

    // ExoPlayer 生命周期：remember 创建 + DisposableEffect release（释放解码器）
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().also {
            FileLogger.d(TAG, "ExoPlayer created sid=$sessionId lapIndex=$lapIndex")
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
            FileLogger.d(TAG, "ExoPlayer released sid=$sessionId lapIndex=$lapIndex")
        }
    }

    // 进屏一次性读 session + 整 session 样本 + 当前圈起止 wallClock
    var session by remember { mutableStateOf<TelemetrySession?>(null) }
    var playbackContext by remember { mutableStateOf<LapPlaybackContext?>(null) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId, lapIndex) {
        val loaded = withContext(Dispatchers.IO) {
            LapPlaybackLoader.load(sessionId, lapIndex, telemetryRepository, trackCatalog)
        }
        if (loaded == null) {
            loadFailed = true
            FileLogger.e(TAG, "load failed sid=$sessionId lapIndex=$lapIndex (no session / no video / no lap / no samples)")
        } else {
            session = loaded.first
            val ctx = loaded.second
            playbackContext = ctx
            val range = VideoTelemetrySync.lapPlayheadRange(ctx.lapStartWallClock, ctx.lapEndWallClock)
            FileLogger.d(
                TAG,
                "enter lap lapIndex=$lapIndex lapNumber=${ctx.lapNumber} " +
                    "lapStart=${ctx.lapStartWallClock} lapEnd=${ctx.lapEndWallClock} " +
                    "playheadStart=${range.first} playheadEnd=${range.last} " +
                    "videoStart=${ctx.videoStartedAtWallClock} samples=${ctx.frames.size} " +
                    "hasBest=${ctx.bestReference != null} trackPts=${ctx.trackPoints.size}",
            )
        }
    }

    // 视频 setMediaItem（session 就绪后）；playWhenReady=false，由播放循环根据覆盖段控制 play/pause
    LaunchedEffect(session) {
        val s = session
        if (s != null && s.videoFilePath != null) {
            exoPlayer.setMediaItem(MediaItem.fromUri(s.videoFilePath!!))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = false
            FileLogger.d(TAG, "setMediaItem ${s.videoFilePath} startedAt=${s.videoStartedAtWallClock}")
        }
    }

    // 当前帧 overlay 数据（轮询更新）
    var overlayFrame by remember { mutableStateOf<VideoOverlayTelemetry.OverlayFrame?>(null) }
    var overlayLap by remember { mutableStateOf<VideoOverlayTelemetry.LapResolution?>(null) }
    var overlayDeltaMs by remember { mutableStateOf<Long?>(null) }
    // 当前是否落在视频覆盖段外（true → 黑屏遮罩盖 PlayerView）
    var blackout by remember { mutableStateOf(true) }

    // 圈时间轴主导播放循环（状态机）：
    // playheadWallClock 状态 + isWithinCoverage 布尔。
    // - 覆盖段内：ExoPlayer play，playhead = videoStart + player.currentPosition（视频驱动）。
    // - 覆盖段外：ExoPlayer pause，ticker 以 1x 实时推进 playhead（黑屏 + overlay 继续叠）。
    // - 段切换：进覆盖段 → seek 视频到对应 position + play；离覆盖段 → pause。
    // - playhead >= playheadEnd（lapEnd）→ pause 停止（圈播完不续下一圈）。
    LaunchedEffect(playbackContext) {
        val ctx = playbackContext ?: return@LaunchedEffect
        if (ctx.sampleWallClocks.isEmpty()) return@LaunchedEffect

        val range = VideoTelemetrySync.lapPlayheadRange(ctx.lapStartWallClock, ctx.lapEndWallClock)
        val playheadStart = range.first
        val playheadEnd = range.last

        // 等 ExoPlayer READY 拿到 duration（黑屏段也要 duration 判定覆盖段右边界）
        var videoDurationMs = exoPlayer.duration
        while (isActive && (videoDurationMs <= 0L || exoPlayer.playbackState == Player.STATE_IDLE ||
                exoPlayer.playbackState == Player.STATE_BUFFERING)
        ) {
            delay(PLAYHEAD_TICK_MS)
            videoDurationMs = exoPlayer.duration
        }
        if (!isActive) return@LaunchedEffect
        FileLogger.d(
            TAG,
            "video READY duration=$videoDurationMs lapIndex=$lapIndex " +
                "uiRefreshThrottle=${PLAYHEAD_TICK_MS}ms(${1000 / PLAYHEAD_TICK_MS}Hz, 采样仍25Hz)",
        )

        // playhead 从圈起点前导秒开始
        var playheadWallClock = playheadStart
        // 进圈初始定位：若起点已在视频覆盖段内 → seek + play；否则黑屏 ticker 起步
        var wasWithinCoverage = VideoTelemetrySync.isWithinVideoCoverage(
            playheadWallClock, ctx.videoStartedAtWallClock, videoDurationMs,
        )
        if (wasWithinCoverage) {
            val seekPos = VideoTelemetrySync.playheadToVideoPosition(
                playheadWallClock, ctx.videoStartedAtWallClock, videoDurationMs,
            )
            exoPlayer.seekTo(seekPos)
            exoPlayer.play()
            blackout = false
            FileLogger.d(TAG, "init within coverage: seek=$seekPos play; playhead=$playheadWallClock")
        } else {
            exoPlayer.pause()
            blackout = true
            FileLogger.d(TAG, "init blackout (lap head before video): playhead=$playheadWallClock videoStart=${ctx.videoStartedAtWallClock}")
        }

        var lastIdx = -1
        var tickCounter = 0
        var lastTickRealtimeMs = System.currentTimeMillis()

        while (isActive) {
            val nowRealtime = System.currentTimeMillis()
            val withinCoverage = VideoTelemetrySync.isWithinVideoCoverage(
                playheadWallClock, ctx.videoStartedAtWallClock, videoDurationMs,
            )

            if (withinCoverage) {
                // 覆盖段：视频驱动 playhead
                if (!wasWithinCoverage) {
                    // 黑屏段 → 覆盖段：seek 视频到 position 0（圈头早于视频，刚追到 videoStart）+ play
                    val seekPos = VideoTelemetrySync.playheadToVideoPosition(
                        playheadWallClock, ctx.videoStartedAtWallClock, videoDurationMs,
                    )
                    exoPlayer.seekTo(seekPos)
                    exoPlayer.play()
                    blackout = false
                    FileLogger.d(TAG, "blackout->coverage seek=$seekPos play; playhead=$playheadWallClock")
                } else if (!exoPlayer.isPlaying && exoPlayer.playbackState == Player.STATE_READY) {
                    exoPlayer.play()
                }
                playheadWallClock = VideoTelemetrySync.frameWallClock(
                    ctx.videoStartedAtWallClock, exoPlayer.currentPosition,
                )
            } else {
                // 覆盖段外：黑屏 ticker 以 1x 实时推进 playhead
                if (wasWithinCoverage) {
                    exoPlayer.pause()
                    blackout = true
                    FileLogger.d(TAG, "coverage->blackout pause; playhead=$playheadWallClock")
                }
                val advance = nowRealtime - lastTickRealtimeMs
                playheadWallClock += advance.coerceIn(0L, 200L) // clamp 防卡顿后大跳
                if (tickCounter % 30 == 0) {
                    FileLogger.d(TAG, "blackout tick advance=$advance playhead=$playheadWallClock end=$playheadEnd")
                }
            }
            wasWithinCoverage = withinCoverage
            lastTickRealtimeMs = nowRealtime

            // 圈播完停在圈末（不自动续下一圈）
            if (playheadWallClock >= playheadEnd) {
                playheadWallClock = playheadEnd
                exoPlayer.pause()
                FileLogger.d(TAG, "lap end reached: playhead=$playheadWallClock lapNumber=${ctx.lapNumber}; stop")
                // 更新最后一帧 overlay 后退出循环
                updateOverlay(
                    ctx, playheadWallClock,
                    onFrame = { overlayFrame = it },
                    onLap = { overlayLap = it },
                    onDelta = { overlayDeltaMs = it },
                )
                break
            }

            // 按 playheadWallClock 查最近邻样本更新 overlay（idx 去抖）
            val idx = VideoTelemetrySync.findNearestSampleIndex(playheadWallClock, ctx.sampleWallClocks)
            if (idx != lastIdx) {
                lastIdx = idx
                updateOverlay(
                    ctx, playheadWallClock,
                    onFrame = { overlayFrame = it },
                    onLap = { overlayLap = it },
                    onDelta = { overlayDeltaMs = it },
                )
                if (tickCounter % 30 == 0) {
                    val f = ctx.frames[idx]
                    FileLogger.d(
                        TAG,
                        "sync playhead=$playheadWallClock within=$withinCoverage idx=$idx " +
                            "spd=${"%.1f".format(f.speedKmh)} lonG=${"%.2f".format(f.lonG)} " +
                            "latG=${"%.2f".format(f.latG)} delta=$overlayDeltaMs",
                    )
                }
            }
            tickCounter++
            delay(PLAYHEAD_TICK_MS)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 加载/失败/内容三态：if/else 分支（M2：禁 early-return）
        if (loadFailed) {
            PlaybackMessage("无法播放该圈视频")
        } else if (playbackContext == null) {
            PlaybackMessage("加载中…")
        } else {
            val ctx = playbackContext!!
            // 视频垫底
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctxView ->
                    PlayerView(ctxView).apply {
                        player = exoPlayer
                        useController = false // 按圈回放由圈时间轴主导，不暴露 ExoPlayer 控制条
                    }
                },
            )
            // 黑屏段：黑色遮罩盖 PlayerView（覆盖段外视频无意义，遮成纯黑；overlay 仍浮上层）
            if (blackout) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                )
            }
            // overlay 四角浮最上层（黑屏段也照常叠）
            val gaugeMaxKmh = GaugeMath.speedGaugeMax(ctx.topSpeedKmh).toDouble()
            OverlayHud(
                frame = overlayFrame,
                lap = overlayLap,
                deltaMs = overlayDeltaMs,
                trackPoints = ctx.trackPoints,
                gaugeMaxKmh = gaugeMaxKmh,
            )
        }
    }
}

/**
 * 按 playheadWallClock 更新 overlay 三态（frame / lap resolution / delta）。
 * 抽出复用：循环中与圈末停止各调一次，避免最后一帧 overlay 不刷新。
 */
private fun updateOverlay(
    ctx: LapPlaybackContext,
    playheadWallClock: Long,
    onFrame: (VideoOverlayTelemetry.OverlayFrame) -> Unit,
    onLap: (VideoOverlayTelemetry.LapResolution?) -> Unit,
    onDelta: (Long?) -> Unit,
) {
    val idx = VideoTelemetrySync.findNearestSampleIndex(playheadWallClock, ctx.sampleWallClocks)
    val frame = ctx.frames[idx]
    onFrame(frame)
    val lap = VideoOverlayTelemetry.resolveCurrentLap(playheadWallClock, ctx.lapWindows)
    onLap(lap)
    val delta = if (lap != null && ctx.bestReference != null) {
        VideoOverlayTelemetry.computeDeltaMs(
            reference = ctx.bestReference,
            currentLapElapsedMs = lap.currentLapElapsedMs,
            currentLat = frame.lat,
            currentLon = frame.lon,
        )
    } else {
        null
    }
    onDelta(delta)
}

/**
 * overlay 四角标布局：左上 SPEED / 左下 LAP+delta / 右上 G / 右下 小地图。
 */
@Composable
private fun OverlayHud(
    frame: VideoOverlayTelemetry.OverlayFrame?,
    lap: VideoOverlayTelemetry.LapResolution?,
    deltaMs: Long?,
    trackPoints: List<GeoPoint>,
    gaugeMaxKmh: Double = GaugeMath.SPEEDO_MAX_KMH,
) {
    // round video-export-burned-overlay Round A：回放端四角已经共享绘制层 OverlayCanvasPainter
    // （speedo/gball/minimap 经 nativeCanvas，laptime 暂保留 Compose Text）。首次组装打一条锚点。
    LaunchedEffect(Unit) {
        FileLogger.d(TAG, "shared painter wired: speedo/gball/minimap via OverlayCanvasPainter; laptime Compose")
    }
    Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        SpeedCorner(
            speedKmh = frame?.speedKmh,
            maxSpeedKmh = gaugeMaxKmh,
            modifier = Modifier.align(Alignment.TopStart),
        )
        GForceCorner(
            latG = frame?.latG,
            lonG = frame?.lonG,
            modifier = Modifier.align(Alignment.TopEnd),
        )
        LapTimeCorner(
            lapNumber = lap?.lapNumber,
            elapsedMs = lap?.currentLapElapsedMs,
            deltaMs = deltaMs,
            modifier = Modifier.align(Alignment.BottomStart),
        )
        if (trackPoints.size >= 2) {
            MiniMapCorner(
                trackPoints = trackPoints,
                currentLat = frame?.lat,
                currentLon = frame?.lon,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

@Composable
private fun OverlayPanel(
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                color = TrackTechColors.Surface.copy(alpha = 0.55f),
                shape = CutCornerPanelShape(cutSize = 6.dp, cutCorners = cutCornersAll),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        content()
    }
}

/**
 * 左上速度角标：round redo-video-overlay-visual-gauges 起改为老式圆形指针速度表（[SpeedometerGauge]），
 * 替换原 DSEG7 速度数字。仪表自带半透明表盘底，不再套 OverlayPanel（避免双层背景）。
 * maxSpeedKmh 由 [GaugeMath.speedGaugeMax] 按 session.topSpeedKmh 动态计算（向上取整到 20 粒度）。
 */
@Composable
private fun SpeedCorner(
    speedKmh: Double?,
    maxSpeedKmh: Double = GaugeMath.SPEEDO_MAX_KMH,
    modifier: Modifier = Modifier,
) {
    SpeedometerGauge(
        speedKmh = speedKmh,
        maxSpeedKmh = maxSpeedKmh,
        modifier = modifier,
        diameter = 120.dp,
    )
}

/**
 * 右上 G 值角标：round redo-video-overlay-visual-gauges 起改为摩擦圆 / G 球（[GForceBall]），
 * 替换原 G 数字。横轴=横向 G（过弯）、纵轴=纵向 G（加速向上 / 制动向下），±1.5G 映射半径边界。
 */
@Composable
private fun GForceCorner(
    latG: Double?,
    lonG: Double?,
    modifier: Modifier = Modifier,
) {
    GForceBall(
        latG = latG,
        lonG = lonG,
        modifier = modifier,
        diameter = 120.dp,
    )
}

@Composable
private fun LapTimeCorner(
    lapNumber: Int?,
    elapsedMs: Long?,
    deltaMs: Long?,
    modifier: Modifier = Modifier,
) {
    OverlayPanel(modifier = modifier, content = {
        Column {
            Text(
                text = if (lapNumber != null) "LAP $lapNumber" else "LAP --",
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.Cyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            // 圈速时间字符串走 Score 斜体（MUST NOT DSEG7）
            Text(
                text = formatElapsed(elapsedMs),
                style = TrackTechTypography.ScoreMedium,
                color = TrackTechColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val deltaText = formatDelta(deltaMs)
            val deltaColor = when {
                deltaMs == null -> TrackTechColors.TextMuted
                deltaMs < 0 -> TrackTechColors.Green // 快
                deltaMs > 0 -> TrackTechColors.Red // 慢
                else -> TrackTechColors.TextPrimary
            }
            Text(
                text = deltaText,
                style = TrackTechTypography.ScoreSmall,
                color = deltaColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    })
}

@Composable
private fun MiniMapCorner(
    trackPoints: List<GeoPoint>,
    currentLat: Double?,
    currentLon: Double?,
    modifier: Modifier = Modifier,
) {
    OverlayPanel(modifier = modifier, content = {
        TrackMiniMap(
            points = trackPoints,
            currentLat = currentLat,
            currentLon = currentLon,
            modifier = Modifier.size(96.dp),
        )
    })
}

@Composable
private fun PlaybackMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = TrackTechTypography.RacingTitleMedium,
            color = TrackTechColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 圈速 elapsed 格式化 m:ss.mmm；null/负 → "--:--.---"。 */
private fun formatElapsed(ms: Long?): String {
    if (ms == null || ms < 0) return "--:--.---"
    val totalSec = ms / 1000
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    val millis = ms % 1000
    return "%d:%02d.%03d".format(minutes, seconds, millis)
}

/** delta 格式化 ±x.xx s；null → "--"。 */
private fun formatDelta(ms: Long?): String {
    if (ms == null) return "--"
    val sign = if (ms >= 0) "+" else "-"
    return "%s%.2f".format(sign, abs(ms) / 1000.0)
}

private tailrec fun Context.findPlaybackActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findPlaybackActivity()
    else -> null
}
