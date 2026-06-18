// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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
import com.blazepush.feature.test.export.VideoExportClip
import com.blazepush.feature.test.export.VideoExportProgressBus
import com.blazepush.feature.test.export.VideoExportService
import com.blazepush.feature.test.model.track.GeoPoint
import com.blazepush.feature.test.recording.VideoTelemetrySync
import com.blazepush.feature.test.repository.TrackCatalog
import com.blazepush.feature.test.usecase.GaugeMath
import com.blazepush.feature.test.usecase.VideoOverlayTelemetry
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    // lap-detail-triview-panel:面板进全屏的进度接力(wallClock;-1 = 无,从圈起点开始)
    initialPlayheadWallClock: Long = -1L,
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

    // 视频 setMediaItems（②c 多段 playlist，选中段升序=item 顺序）；playWhenReady=false，
    // 由播放循环段感知状态机控制 play/pause/跨段 seek。
    // pauseAtEndOfMediaItems：item 播完不自动续下一段——段间 gap 必须交还黑屏 ticker
    // 推进时间轴（保真），由状态机在 playhead 进入下段区间时主动 seekTo(index, pos)。
    LaunchedEffect(playbackContext) {
        val ctx = playbackContext
        if (ctx != null && ctx.segments.isNotEmpty()) {
            exoPlayer.setMediaItems(ctx.segments.map { MediaItem.fromUri(it.filePath) })
            exoPlayer.pauseAtEndOfMediaItems = true
            exoPlayer.prepare()
            exoPlayer.playWhenReady = false
            FileLogger.d(
                TAG,
                "setMediaItems n=${ctx.segments.size} idx=${ctx.segments.map { it.segmentIndex }} " +
                    "starts=${ctx.segments.map { it.startWallClock }}",
            )
        }
    }

    // ②c playable 首播回写（spec Req4）：首帧渲染成功 → 当前段 playable null→true；
    // 播放错误 → null→false。仅 null 段写（幂等）；回写失败仅日志不阻塞播放。
    val playableScope = rememberCoroutineScope()
    DisposableEffect(playbackContext) {
        val ctx = playbackContext
        val listener = object : Player.Listener {
            private fun writeBack(value: Boolean) {
                val segs = ctx?.segments ?: return
                val idx = exoPlayer.currentMediaItemIndex
                val seg = segs.getOrNull(idx) ?: return
                if (seg.playable != null) return // 幂等：已知段不重复写
                playableScope.launch(Dispatchers.IO) {
                    runCatching { telemetryRepository.updateSegmentPlayable(seg.id, value) }
                        .onSuccess { FileLogger.d(TAG, "playable write-back seg=${seg.segmentIndex} id=${seg.id} -> $value") }
                        .onFailure { t -> FileLogger.e(TAG, "playable write-back failed id=${seg.id}", t) }
                }
            }
            override fun onRenderedFirstFrame() = writeBack(true)
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                FileLogger.e(TAG, "player error: ${error.errorCodeName}", error)
                writeBack(false)
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // 当前帧 overlay 数据（轮询更新）
    var overlayFrame by remember { mutableStateOf<VideoOverlayTelemetry.OverlayFrame?>(null) }
    var overlayLap by remember { mutableStateOf<VideoOverlayTelemetry.LapResolution?>(null) }
    var overlayDeltaMs by remember { mutableStateOf<Long?>(null) }
    // 当前是否落在视频覆盖段外（true → 黑屏遮罩盖 PlayerView）
    var blackout by remember { mutableStateOf(true) }
    // lap-detail-triview-panel:playhead 暴露给进度条(state 镜像循环内局部值,TICK 节流)
    var playheadUiWallClock by remember { mutableStateOf<Long?>(null) }
    var playheadRangeUi by remember { mutableStateOf<LongRange?>(null) }
    // 进度条拖动 seek 请求(循环每 tick 消费;atEnd 停驻态也会被它唤醒)
    var seekRequestWallClock by remember { mutableStateOf<Long?>(null) }
    // round fix-lap-detail-ux-three-touch-issues Bug 3：用户暂停 flag。
    // true 时状态机循环开头 exoPlayer.pause() 且 skip playhead 推进；
    // 双击屏幕 / 中央 IconButton 都切换它。Slider seek 仍写入，恢复播放后下一 tick 消费。
    var userPaused by remember { mutableStateOf(false) }
    // round move-export-to-playback-and-relax-replay-gate：视频时长（ExoPlayer READY 后回填），
    // 用于在播放页算当前圈相对视频覆盖段的覆盖程度（决定导出按钮 enable/disable）。<=0 表示未知。
    var videoDurationMs by remember { mutableStateOf(0L) }

    // 当前圈相对视频覆盖段的覆盖程度（导出按钮 gate）：
    // FULL → 导出可点；PARTIAL → 置灰 + Toast 提示视频不完整；NONE → 理论上进不来（回放入口已挡），保险也禁用。
    val coverage = remember(playbackContext, videoDurationMs) {
        val ctx = playbackContext
        if (ctx == null || videoDurationMs <= 0L) {
            VideoExportClip.Coverage.NONE
        } else {
            VideoExportClip.lapCoverage(
                ctx.lapStartWallClock, ctx.lapEndWallClock,
                ctx.videoStartedAtWallClock, videoDurationMs,
            )
        }
    }

    // 改动 1：POST_NOTIFICATIONS 运行时请求（Android 13+ / API33 TIRAMISU）。
    // manifest 已声明权限但缺运行时请求 → 13+ 导出前台 Service 进度通知不显示。
    // 复用工程 rememberLauncherForActivityResult 范式（LapLiveScreen 相机权限同款）。
    // 授予/拒绝都继续启动导出（拒绝降级：导出照跑，仅无进度通知 + Toast 提示）。
    val startExport: () -> Unit = {
        val ctx = playbackContext
        if (ctx != null) {
            FileLogger.d(TAG, "start export sid=$sessionId lapIndex=$lapIndex lapNumber=${ctx.lapNumber}")
            VideoExportService.start(context, sessionId, lapIndex)
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            FileLogger.d(TAG, "POST_NOTIFICATIONS denied → 导出降级（无进度通知）")
            Toast.makeText(context, "未授权通知，导出在后台进行", Toast.LENGTH_SHORT).show()
        } else {
            FileLogger.d(TAG, "POST_NOTIFICATIONS granted")
        }
        // 授予/拒绝都启动导出
        startExport()
    }
    // 点导出：13+ 未授予通知权限 → 先请求（回调里再 startExport）；否则直接导。
    val onExportClick: () -> Unit = {
        if (coverage != VideoExportClip.Coverage.FULL) {
            Toast.makeText(context, "该圈视频不完整，暂不可导出", Toast.LENGTH_SHORT).show()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            FileLogger.d(TAG, "request POST_NOTIFICATIONS before export")
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startExport()
        }
    }

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
        val playheadStart = if (initialPlayheadWallClock in range) initialPlayheadWallClock else range.first
        val playheadEnd = range.last
        playheadRangeUi = range
        if (initialPlayheadWallClock in range) {
            FileLogger.d(TAG, "进度接力:面板带入 playhead=$initialPlayheadWallClock")
        }

        // 等 ExoPlayer READY 拿到 duration（黑屏段也要 duration 判定覆盖段右边界）
        var localDurationMs = exoPlayer.duration
        while (isActive && (localDurationMs <= 0L || exoPlayer.playbackState == Player.STATE_IDLE ||
                exoPlayer.playbackState == Player.STATE_BUFFERING)
        ) {
            delay(PLAYHEAD_TICK_MS)
            localDurationMs = exoPlayer.duration
        }
        if (!isActive) return@LaunchedEffect
        // round move-export-to-playback-and-relax-replay-gate：回填外层 state → 触发 coverage 重算 → 导出按钮 gate 生效。
        videoDurationMs = localDurationMs
        FileLogger.d(
            TAG,
            "video READY duration=$localDurationMs lapIndex=$lapIndex " +
                "uiRefreshThrottle=${PLAYHEAD_TICK_MS}ms(${1000 / PLAYHEAD_TICK_MS}Hz, 采样仍25Hz)",
        )

        // ②c 段感知 helper（闭包捕获 ctx.segments；单段时与旧单文件行为完全等价）
        fun segDurationMs(idx: Int): Long = ctx.segments[idx].durationMs ?: 0L // <=0 → position 不设上界 clamp
        fun seekIntoSegment(playhead: Long, segIdx: Int) {
            val seg = ctx.segments[segIdx]
            val pos = VideoTelemetrySync.playheadToVideoPosition(playhead, seg.startWallClock, segDurationMs(segIdx))
            exoPlayer.seekTo(segIdx, pos)
            exoPlayer.play()
            FileLogger.d(TAG, "seekIntoSegment item=$segIdx pos=$pos playhead=$playhead segStart=${seg.startWallClock}")
        }
        fun playheadFromPlayer(): Long {
            val idx = exoPlayer.currentMediaItemIndex.coerceIn(0, ctx.segments.lastIndex)
            return VideoTelemetrySync.frameWallClock(ctx.segments[idx].startWallClock, exoPlayer.currentPosition)
        }

        // playhead 从圈起点前导秒开始
        var playheadWallClock = playheadStart
        // 进圈初始定位：若起点已落某段内 → 跨段 seek + play；否则黑屏 ticker 起步
        var currentSegIdx = VideoTelemetrySync.segmentIndexAt(playheadWallClock, ctx.segments)
        var wasWithinCoverage = currentSegIdx != null
        if (currentSegIdx != null) {
            seekIntoSegment(playheadWallClock, currentSegIdx!!)
            blackout = false
            FileLogger.d(TAG, "init within segment=$currentSegIdx playhead=$playheadWallClock")
        } else {
            exoPlayer.pause()
            blackout = true
            FileLogger.d(TAG, "init blackout (gap/越界): playhead=$playheadWallClock firstSegStart=${ctx.segments.firstOrNull()?.startWallClock}")
        }

        var lastIdx = -1
        var tickCounter = 0
        var lastTickRealtimeMs = System.currentTimeMillis()
        // 圈播完停驻(原 break 退出——进度条拖动需要循环常驻,atEnd 可被 seek 唤醒)
        var atEnd = false

        while (isActive) {
            // round fix-lap-detail-ux-three-touch-issues Bug 3：用户暂停 flag 优先判定。
            // userPaused 时 ExoPlayer pause + playhead 不推进 + 不消费 seek（待恢复播放后消费），
            // 段切换 / 覆盖判定 / atEnd 既有语义全跳过。
            if (userPaused) {
                if (exoPlayer.isPlaying) exoPlayer.pause()
                playheadUiWallClock = playheadWallClock
                delay(PLAYHEAD_TICK_MS)
                continue
            }
            val nowRealtime = System.currentTimeMillis()

            // 进度条 seek 请求(任意时刻,含 atEnd 停驻态)
            seekRequestWallClock?.let { req ->
                seekRequestWallClock = null
                playheadWallClock = req.coerceIn(playheadStart, playheadEnd)
                atEnd = false
                lastTickRealtimeMs = nowRealtime
                val segIdx = VideoTelemetrySync.segmentIndexAt(playheadWallClock, ctx.segments)
                if (segIdx != null) {
                    seekIntoSegment(playheadWallClock, segIdx)
                    blackout = false
                } else {
                    exoPlayer.pause()
                    blackout = true
                }
                wasWithinCoverage = segIdx != null
                FileLogger.d(TAG, "进度条 seek → playhead=$playheadWallClock segIdx=$segIdx")
            }

            if (atEnd) {
                playheadUiWallClock = playheadWallClock
                delay(PLAYHEAD_TICK_MS)
                continue
            }

            val segIdxNow = VideoTelemetrySync.segmentIndexAt(playheadWallClock, ctx.segments)
            // STATE_ENDED（救援段 endWallClock=null 无法靠区间离段）强制交还黑屏 ticker
            val withinCoverage = segIdxNow != null && exoPlayer.playbackState != Player.STATE_ENDED

            if (withinCoverage) {
                // 覆盖段：视频驱动 playhead
                if (!wasWithinCoverage) {
                    // 黑屏/gap → 进段：跨段 seek + play（playhead 刚追到某段 start）
                    seekIntoSegment(playheadWallClock, segIdxNow!!)
                    blackout = false
                    FileLogger.d(TAG, "blackout->segment $segIdxNow play; playhead=$playheadWallClock")
                } else if (segIdxNow != exoPlayer.currentMediaItemIndex) {
                    // 段感知防御：playhead 所在段与 player 当前 item 不一致（进度条快拖等）→ 对齐
                    seekIntoSegment(playheadWallClock, segIdxNow!!)
                } else if (!exoPlayer.isPlaying && exoPlayer.playbackState == Player.STATE_READY) {
                    exoPlayer.play()
                }
                playheadWallClock = playheadFromPlayer()
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

            // 圈播完停驻圈末(不自动续下一圈;循环常驻等进度条唤醒)
            if (playheadWallClock >= playheadEnd) {
                playheadWallClock = playheadEnd
                exoPlayer.pause()
                atEnd = true
                FileLogger.d(TAG, "lap end reached: playhead=$playheadWallClock lapNumber=${ctx.lapNumber}; 停驻待 seek")
                updateOverlay(
                    ctx, playheadWallClock,
                    onFrame = { overlayFrame = it },
                    onLap = { overlayLap = it },
                    onDelta = { overlayDeltaMs = it },
                )
                playheadUiWallClock = playheadWallClock
                continue
            }
            playheadUiWallClock = playheadWallClock

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
            .background(Color.Black)
            // round fix-lap-detail-ux-three-touch-issues Bug 3：双击屏幕任意非按钮位置切换 userPaused
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    userPaused = !userPaused
                    FileLogger.d(TAG, "userPaused -> $userPaused via doubleTap")
                })
            },
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
            // 改动 2：导出按钮移到播放页。顶部居中（避开四角 overlay：左上速度/右上G/左下圈速/右下地图）。
            // 完整覆盖 → 可点启动导出；部分覆盖 → 置灰，点击 Toast 提示视频不完整（onExportClick 内处理）。
            ExportButton(
                enabled = coverage == VideoExportClip.Coverage.FULL,
                onClick = onExportClick,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
            )
            // lap-detail-triview-panel:全屏页进度条(圈窗口坐标系,底部居中;拖动经
            // seekRequestWallClock 由状态机消费——含播完停驻态唤醒)。限宽 0.5 居中,
            // 避开左下圈时/右下小地图两角。
            val rangeUi = playheadRangeUi
            val playheadUi = playheadUiWallClock
            if (rangeUi != null && playheadUi != null && rangeUi.last > rangeUi.first) {
                androidx.compose.material3.Slider(
                    value = playheadUi.coerceIn(rangeUi.first, rangeUi.last).toFloat(),
                    onValueChange = { seekRequestWallClock = it.toLong() },
                    valueRange = rangeUi.first.toFloat()..rangeUi.last.toFloat(),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = TrackTechColors.Cyan,
                        activeTrackColor = TrackTechColors.Cyan,
                        inactiveTrackColor = TrackTechColors.Border,
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(0.5f)
                        .padding(bottom = 8.dp),
                )
            }
            // round fix-lap-detail-ux-three-touch-issues Bug 3：中央播放按钮仅暂停态可见，
            // 半透明圆形背景 + Play 图标；点击恢复播放。播放中无视觉干扰。
            if (userPaused) {
                IconButton(
                    onClick = {
                        userPaused = false
                        FileLogger.d(TAG, "userPaused -> false via centerButton")
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(80.dp)
                        .background(Color.Black.copy(alpha = 0.4f), shape = CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "play",
                        tint = Color.White,
                        modifier = Modifier.size(56.dp),
                    )
                }
            }
            // 导出进度遮罩（观察 VideoExportProgressBus；完成弹分享 / 失败 Toast）。
            // 导出从本屏发起，进度/分享在本屏显示。M2：三态 if/else 禁 early-return。
            VideoExportProgressOverlay(sessionId = sessionId)
        }
    }
}

/**
 * 导出进度遮罩对话框（观察 [VideoExportProgressBus]）。
 * - Running：全屏半透明遮罩 + 进度面板（"导出中 N%" + 取消按钮）。
 * - Done：Toast "已保存到相册" + 拉起系统分享 Intent（ACTION_SEND video/mp4，content URI）。
 * - Failed：Toast 提示。
 * M2 教训：三态用 when/if 渲染，不在 scope 内 early-return。
 *
 * round move-export-to-playback-and-relax-replay-gate：从 LapSessionDetailScreen 迁来（导出入口移播放页）。
 */
@Composable
private fun VideoExportProgressOverlay(sessionId: String) {
    val context = LocalContext.current
    val state by VideoExportProgressBus.state.collectAsState()

    // 终态副作用：完成弹分享 / 失败 Toast，消费后 reset。
    LaunchedEffect(state) {
        when (val s = state) {
            is VideoExportProgressBus.State.Done -> {
                Toast.makeText(context, "已保存到相册", Toast.LENGTH_LONG).show()
                val uri = s.uri
                if (uri != null) {
                    runCatching {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, "分享圈速视频"))
                    }
                }
                VideoExportProgressBus.reset()
            }
            is VideoExportProgressBus.State.Failed -> {
                Toast.makeText(context, s.message, Toast.LENGTH_LONG).show()
                VideoExportProgressBus.reset()
            }
            else -> Unit
        }
    }

    val running = state as? VideoExportProgressBus.State.Running
    // 仅当前 session 的导出在本屏显示进度面板（避免别的 session 残留态串显）。
    if (running != null && running.sessionId == sessionId) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            CutCornerPanel(
                cutSize = 8.dp,
                cutCorners = cutCornersAll,
                contentPadding = 20.dp,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "导出 Lap ${running.lapNumber} 视频",
                        style = TrackTechTypography.UiTextLabel,
                        color = TrackTechColors.Cyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "导出中 ${running.percent}%",
                        style = TrackTechTypography.ScoreMedium,
                        color = TrackTechColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(TrackTechColors.Red.copy(alpha = 0.18f))
                            .clickable {
                                FileLogger.d(TAG, "user cancel export sid=$sessionId")
                                VideoExportService.cancel(context)
                            }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = "取消",
                            style = TrackTechTypography.UiTextBody,
                            color = TrackTechColors.Red,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 改动 2：播放页顶部导出按钮（chip 风格：下载图标 + "导出" 文字）。
 *
 * enabled（完整覆盖）→ Cyan 高亮可点；disabled（部分覆盖）→ 置灰但仍接收点击（onClick 内 Toast 提示不可导）。
 * V2 视觉：单行 Ellipsis + 末尾固定元素前 Spacer。
 */
@Composable
private fun ExportButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (enabled) TrackTechColors.Cyan else TrackTechColors.TextMuted
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                color = if (enabled) {
                    TrackTechColors.Surface.copy(alpha = 0.72f)
                } else {
                    TrackTechColors.Surface.copy(alpha = 0.45f)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "⬇",
            style = TrackTechTypography.UiTextBody,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (enabled) "导出" else "视频不完整",
            style = TrackTechTypography.UiTextLabel,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 按 playheadWallClock 更新 overlay 三态（frame / lap resolution / delta）。
 * 抽出复用：循环中与圈末停止各调一次，避免最后一帧 overlay 不刷新。
 */
internal fun updateOverlay(
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
internal fun OverlayHud(
    frame: VideoOverlayTelemetry.OverlayFrame?,
    lap: VideoOverlayTelemetry.LapResolution?,
    deltaMs: Long?,
    trackPoints: List<GeoPoint>,
    gaugeMaxKmh: Double = GaugeMath.SPEEDO_MAX_KMH,
    // lap-detail-triview-panel 二轮(2026-06-05 真机反馈):全屏件原尺寸塞小面板挤爆视频——
    // 面板传 ~0.5f 整体缩放四角(仪表直径/地图/圈时字号/padding);全屏默认 1f 零变化
    scale: Float = 1f,
) {
    // round video-export-burned-overlay Round A：回放端四角已经共享绘制层 OverlayCanvasPainter
    // （speedo/gball/minimap 经 nativeCanvas，laptime 暂保留 Compose Text）。首次组装打一条锚点。
    LaunchedEffect(Unit) {
        FileLogger.d(TAG, "shared painter wired: speedo/gball/minimap via OverlayCanvasPainter; laptime Compose")
    }
    Box(modifier = Modifier.fillMaxSize().padding(12.dp * scale)) {
        SpeedCorner(
            speedKmh = frame?.speedKmh,
            maxSpeedKmh = gaugeMaxKmh,
            diameter = 120.dp * scale,
            modifier = Modifier.align(Alignment.TopStart),
        )
        GForceCorner(
            latG = frame?.latG,
            lonG = frame?.lonG,
            diameter = 120.dp * scale,
            modifier = Modifier.align(Alignment.TopEnd),
        )
        LapTimeCorner(
            lapNumber = lap?.lapNumber,
            elapsedMs = lap?.currentLapElapsedMs,
            deltaMs = deltaMs,
            compact = scale < 1f,
            modifier = Modifier.align(Alignment.BottomStart),
        )
        if (trackPoints.size >= 2) {
            MiniMapCorner(
                trackPoints = trackPoints,
                currentLat = frame?.lat,
                currentLon = frame?.lon,
                mapSize = 96.dp * scale,
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
    diameter: androidx.compose.ui.unit.Dp = 120.dp,
    modifier: Modifier = Modifier,
) {
    SpeedometerGauge(
        speedKmh = speedKmh,
        maxSpeedKmh = maxSpeedKmh,
        modifier = modifier,
        diameter = diameter,
    )
}

/**
 * 右上 G 值角标：round redo-video-overlay-visual-gauges 起改为摩擦圆 / G 球（[GForceBall]），
 * 替换原 G 数字。横轴=横向 G（过弯）、纵轴=纵向 G（减速向上 / 加速向下，仿飞机摇杆惯例），±1.5G 映射半径边界。
 */
@Composable
private fun GForceCorner(
    latG: Double?,
    lonG: Double?,
    diameter: androidx.compose.ui.unit.Dp = 120.dp,
    modifier: Modifier = Modifier,
) {
    GForceBall(
        latG = latG,
        lonG = lonG,
        modifier = modifier,
        diameter = diameter,
    )
}

@Composable
private fun LapTimeCorner(
    lapNumber: Int?,
    elapsedMs: Long?,
    deltaMs: Long?,
    compact: Boolean = false,
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
                style = if (compact) TrackTechTypography.ScoreSmall else TrackTechTypography.ScoreMedium,
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
    mapSize: androidx.compose.ui.unit.Dp = 96.dp,
    modifier: Modifier = Modifier,
) {
    OverlayPanel(modifier = modifier, content = {
        TrackMiniMap(
            points = trackPoints,
            currentLat = currentLat,
            currentLon = currentLon,
            modifier = Modifier.size(mapSize),
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
