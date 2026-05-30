// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.blazepush.core.data.repository.TelemetryRepository
import com.blazepush.core.domain.model.LapTelemetrySample
import com.blazepush.core.domain.model.TelemetrySession
import com.blazepush.feature.test.FileLogger
import com.blazepush.feature.test.model.track.GeoPoint
import com.blazepush.feature.test.recording.VideoTelemetrySync
import com.blazepush.feature.test.repository.TrackCatalog
import com.blazepush.feature.test.usecase.ReferenceLapIndex
import com.blazepush.feature.test.usecase.VideoOverlayTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.abs

private const val OVERLAY_POLL_INTERVAL_MS = 33L // ~30fps
private const val TAG = "VideoOverlay"

/**
 * 进屏一次性读好的整 session overlay 上下文（轮询时只查表不算 IO）。
 */
private data class PlaybackContext(
    val frames: List<VideoOverlayTelemetry.OverlayFrame>,
    val sampleWallClocks: List<Long>,
    val lapWindows: List<VideoOverlayTelemetry.LapWindow>,
    val bestReference: ReferenceLapIndex?,
    val trackPoints: List<GeoPoint>,
    val videoStartedAtWallClock: Long,
)

/**
 * 视频实时叠加遥测 HUD 播放屏（Phase 2 round video-overlay-realtime-playback）。
 *
 * media3 ExoPlayer 播放原始视频（PlayerView 经 AndroidView 垫底），Compose 角标 overlay 浮上层：
 * 左上 SPEED（DSEG7）/ 左下 LAP 计时+delta（Score 斜体）/ 右上 G 值（DSEG7）/ 右下 小地图。
 * overlay 随 player.currentPosition 30fps 轮询跳变（覆盖 seek/暂停）。纯播放渲染，不烧录不导出。
 *
 * @author CC
 * @description landscape video playback screen with realtime telemetry HUD overlay
 * @date 2026-05-31
 */
@UnstableApi
@Composable
fun LapVideoPlaybackScreen(
    navController: NavController,
    sessionId: String,
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

    // ExoPlayer 生命周期：remember 创建 + DisposableEffect release（释放解码器，spec 反例锁定）
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().also {
            FileLogger.d(TAG, "ExoPlayer created sid=$sessionId")
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
            FileLogger.d(TAG, "ExoPlayer released sid=$sessionId")
        }
    }

    // 进屏一次性读 session + 全样本（Dispatchers.IO），置 state；读取中 loading（if/else 禁 early-return）
    var session by remember { mutableStateOf<TelemetrySession?>(null) }
    var playbackContext by remember { mutableStateOf<PlaybackContext?>(null) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        val loaded = withContext(Dispatchers.IO) {
            loadPlaybackData(sessionId, telemetryRepository, trackCatalog)
        }
        if (loaded == null) {
            loadFailed = true
            FileLogger.e(TAG, "load failed sid=$sessionId (no session / no video / no samples)")
        } else {
            session = loaded.first
            playbackContext = loaded.second
            val ctx = loaded.second
            FileLogger.d(
                TAG,
                "loaded sid=$sessionId samples=${ctx.frames.size} estBytes=${ctx.frames.size * 70} " +
                    "laps=${ctx.lapWindows.size} hasBest=${ctx.bestReference != null} trackPts=${ctx.trackPoints.size}",
            )
        }
    }

    // 视频 setMediaItem（session 就绪后）
    LaunchedEffect(session) {
        val s = session
        if (s != null && s.videoFilePath != null) {
            exoPlayer.setMediaItem(MediaItem.fromUri(s.videoFilePath!!))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            FileLogger.d(TAG, "setMediaItem ${s.videoFilePath} startedAt=${s.videoStartedAtWallClock}")
        }
    }

    // 当前帧 overlay 数据（轮询更新）
    var overlayFrame by remember { mutableStateOf<VideoOverlayTelemetry.OverlayFrame?>(null) }
    var overlayLap by remember { mutableStateOf<VideoOverlayTelemetry.LapResolution?>(null) }
    var overlayDeltaMs by remember { mutableStateOf<Long?>(null) }

    // 轮询：每 33ms 读 currentPosition → frameWallClock → 最近邻样本 idx → 更新 overlay（idx 去抖）
    LaunchedEffect(playbackContext) {
        val ctx = playbackContext ?: return@LaunchedEffect
        if (ctx.sampleWallClocks.isEmpty()) return@LaunchedEffect
        var lastIdx = -1
        var tickCounter = 0
        while (isActive) {
            val position = exoPlayer.currentPosition
            val frameWallClock = VideoTelemetrySync.frameWallClock(ctx.videoStartedAtWallClock, position)
            val idx = VideoTelemetrySync.findNearestSampleIndex(frameWallClock, ctx.sampleWallClocks)
            if (idx != lastIdx) {
                lastIdx = idx
                val frame = ctx.frames[idx]
                overlayFrame = frame
                // 圈窗口判定 + delta 投影（落两圈间 / 无 best → null 显 "--"）
                val lap = VideoOverlayTelemetry.resolveCurrentLap(frameWallClock, ctx.lapWindows)
                overlayLap = lap
                overlayDeltaMs = if (lap != null && ctx.bestReference != null) {
                    VideoOverlayTelemetry.computeDeltaMs(
                        reference = ctx.bestReference,
                        currentLapElapsedMs = lap.currentLapElapsedMs,
                        currentLat = frame.lat,
                        currentLon = frame.lon,
                    )
                } else {
                    null
                }
                // 同步精度抽样埋点（每 ~1s 一条，路测 adb pull 核对）
                if (tickCounter % 30 == 0) {
                    FileLogger.d(
                        TAG,
                        "sync pos=$position fwc=$frameWallClock idx=$idx ts=${frame.absoluteTsMs} " +
                            "spd=${"%.1f".format(frame.speedKmh)} lonG=${"%.2f".format(frame.lonG)} " +
                            "latG=${"%.2f".format(frame.latG)} lap=${lap?.lapNumber} delta=$overlayDeltaMs",
                    )
                }
            }
            tickCounter++
            delay(OVERLAY_POLL_INTERVAL_MS)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 加载/失败/内容三态：if/else 分支（M2：禁 early-return）
        if (loadFailed) {
            PlaybackMessage("无法播放该视频")
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
                        useController = true
                    }
                },
            )
            // overlay 四角浮上层
            OverlayHud(
                frame = overlayFrame,
                lap = overlayLap,
                deltaMs = overlayDeltaMs,
                trackPoints = ctx.trackPoints,
            )
        }
    }
}

/**
 * overlay 四角标布局（design Decision 5）：左上 SPEED / 左下 LAP+delta / 右上 G / 右下 小地图。
 */
@Composable
private fun OverlayHud(
    frame: VideoOverlayTelemetry.OverlayFrame?,
    lap: VideoOverlayTelemetry.LapResolution?,
    deltaMs: Long?,
    trackPoints: List<GeoPoint>,
) {
    Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // 左上 SPEED（DSEG7）
        SpeedCorner(
            speedKmh = frame?.speedKmh,
            modifier = Modifier.align(Alignment.TopStart),
        )
        // 右上 G-FORCE（DSEG7）
        GForceCorner(
            latG = frame?.latG,
            lonG = frame?.lonG,
            modifier = Modifier.align(Alignment.TopEnd),
        )
        // 左下 LAP + delta（Score 斜体）
        LapTimeCorner(
            lapNumber = lap?.lapNumber,
            elapsedMs = lap?.currentLapElapsedMs,
            deltaMs = deltaMs,
            modifier = Modifier.align(Alignment.BottomStart),
        )
        // 右下 小地图（几何不足时隐藏，其余角标不受影响）
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

@Composable
private fun SpeedCorner(
    speedKmh: Double?,
    modifier: Modifier = Modifier,
) {
    OverlayPanel(modifier = modifier, content = {
        Column {
            Text(
                text = "SPEED",
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val display = speedKmh?.let { "%.0f".format(it) } ?: "--"
            val speedColor = if ((speedKmh ?: 0.0) >= 120.0) TrackTechColors.Cyan else TrackTechColors.TextPrimary
            MetricNumber(
                value = display,
                unit = "km/h",
                size = MetricSize.Medium,
                kind = MetricKind.Mechanical,
                valueColor = speedColor,
            )
        }
    })
}

@Composable
private fun GForceCorner(
    latG: Double?,
    lonG: Double?,
    modifier: Modifier = Modifier,
) {
    OverlayPanel(modifier = modifier, content = {
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "G-FORCE",
                style = TrackTechTypography.UiTextLabel,
                color = TrackTechColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            GRow(label = "LAT", value = latG)
            Spacer(Modifier.height(2.dp))
            GRow(label = "LON", value = lonG)
        }
    })
}

@Composable
private fun GRow(label: String, value: Double?) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = label,
            style = TrackTechTypography.UiTextLabel,
            color = TrackTechColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        MetricNumber(
            value = value?.let { "%.1f".format(abs(it)) } ?: "--",
            size = MetricSize.Small,
            kind = MetricKind.Mechanical,
            valueColor = TrackTechColors.TextPrimary,
        )
    }
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

/**
 * 进屏一次性读 session metadata + 整 session overlay 上下文（在 Dispatchers.IO 调）。
 * 返回 null 表示无法播放（无 session / 无 video / 无样本）。
 */
private suspend fun loadPlaybackData(
    sessionId: String,
    repo: TelemetryRepository,
    trackCatalog: TrackCatalog,
): Pair<TelemetrySession, PlaybackContext>? {
    val session = repo.getSession(sessionId) ?: return null
    val videoStartedAt = session.videoStartedAtWallClock ?: return null
    if (session.videoFilePath == null) return null

    // 逐圈拼接整 session 样本（升序 absoluteTsMs）+ 各圈窗口
    val allSamples = mutableListOf<LapTelemetrySample>()
    val lapWindows = mutableListOf<VideoOverlayTelemetry.LapWindow>()
    var lapIndex = 0
    while (true) {
        val lap = repo.getLapTelemetry(sessionId, lapIndex) ?: break
        allSamples.addAll(lap.samples)
        lapWindows.add(
            VideoOverlayTelemetry.LapWindow(
                lapNumber = lapIndex + 1,
                lapStartWallClock = lap.lapStartWallClock,
                lapEndWallClock = lap.lapEndWallClock,
            ),
        )
        lapIndex++
        if (lapIndex > 1000) break // 安全上界防意外死循环
    }
    if (allSamples.isEmpty()) return null

    // 样本可能跨圈有重叠（圈尾==下圈头）；按 absoluteTsMs 升序排序 + 去重相邻同 ts
    val sorted = allSamples.sortedBy { it.absoluteTsMs }
    val frames = VideoOverlayTelemetry.buildFrames(sorted)
    val sampleWallClocks = frames.map { it.absoluteTsMs }

    // best 圈 reference：bestLapMs 对应的圈（duration == bestLapMs）
    val bestReference = buildBestReference(sessionId, repo, session.bestLapMs)

    // 赛道几何（小地图）：getTrack 同步，冷缓存时 warmup 一次
    val trackPoints = resolveTrackPoints(session.trackId, trackCatalog)

    return session to PlaybackContext(
        frames = frames,
        sampleWallClocks = sampleWallClocks,
        lapWindows = lapWindows,
        bestReference = bestReference,
        trackPoints = trackPoints,
        videoStartedAtWallClock = videoStartedAt,
    )
}

/** 定位 bestLapMs 对应的圈并构建 ReferenceLapIndex；无 best / 样本不足 → null。 */
private suspend fun buildBestReference(
    sessionId: String,
    repo: TelemetryRepository,
    bestLapMs: Long?,
): ReferenceLapIndex? {
    if (bestLapMs == null) return null
    var lapIndex = 0
    while (lapIndex <= 1000) {
        val lap = repo.getLapTelemetry(sessionId, lapIndex) ?: break
        if (lap.lapDurationMs == bestLapMs) {
            val ref = VideoOverlayTelemetry.buildReferenceFromSamples(
                bestLapSamples = lap.samples,
                lapStartWallClock = lap.lapStartWallClock,
                lapDurationMs = lap.lapDurationMs,
            )
            FileLogger.d(TAG, "best ref built lapIndex=$lapIndex dur=$bestLapMs pts=${lap.samples.size} ok=${ref != null}")
            return ref
        }
        lapIndex++
    }
    FileLogger.d(TAG, "no best lap matched bestLapMs=$bestLapMs")
    return null
}

/** 解析赛道轮廓点；trackId null / 解析不到 → 空列表（小地图降级隐藏）。 */
private suspend fun resolveTrackPoints(
    trackId: String?,
    trackCatalog: TrackCatalog,
): List<GeoPoint> {
    if (trackId == null) return emptyList()
    // getTrack 同步，冷缓存返回 fallback；先 warmup getAllTracks 暖缓存
    runCatching { trackCatalog.getAllTracks() }
    val track = trackCatalog.getTrack(trackId)
    val points = track?.referencePath?.points ?: emptyList()
    FileLogger.d(TAG, "track geometry trackId=$trackId pts=${points.size}")
    return points
}

private tailrec fun Context.findPlaybackActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findPlaybackActivity()
    else -> null
}
