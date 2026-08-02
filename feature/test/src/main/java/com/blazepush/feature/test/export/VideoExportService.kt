// @IgnoreFormatCheck
package com.blazepush.feature.test.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.blazepush.core.data.repository.TelemetryRepository
import com.blazepush.feature.test.FileLogger
import com.blazepush.feature.test.overlay.VideoOverlayStyle
import com.blazepush.feature.test.repository.TrackCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 视频导出前台 Service（round video-export-burned-overlay · Round B · Decision 4）。
 *
 * 详情屏点"导出带数据视频"→ `startForegroundService(EXTRA_SESSION_ID/EXTRA_LAP_INDEX)`。Service：
 * 1. `startForeground` 带进度通知（API34 `foregroundServiceType=mediaProcessing`）。
 * 2. 后台线程跑：[LapPlaybackLoader.load] → [VideoExportClip.computeClipRange] →
 *    [VideoExportMediaStoreWriter.prepare] → [VideoExportPipeline.run]（带 muxer + 进度回调）→
 *    finalize（IS_PENDING=0）/ abort（删半成品）。
 * 3. 进度经 [VideoExportProgressBus] 回传 UI；完成弹"已保存到相册"；取消 = 中断 pipeline + stopSelf + 清理。
 *
 * 失败被 runCatching 捕获 → abort 半成品 + Bus.setFailed + FileLogger.e，app 不崩（spec 反例）。
 *
 * @author CC
 * @description foreground service running video export pipeline
 * @date 2026-05-31
 */
class VideoExportService : Service(), KoinComponent {

    private val tag = "ExportService"
    private val telemetryRepository: TelemetryRepository by inject()
    private val trackCatalog: TrackCatalog by inject()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var exportJob: Job? = null

    @Volatile
    private var pipeline: VideoExportPipeline? = null
    @Volatile
    private var multiSegmentPipeline: MultiSegmentVideoExportPipeline? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            FileLogger.d(tag, "onStartCommand ACTION_CANCEL")
            pipeline?.cancel()
            multiSegmentPipeline?.cancel()
            return START_NOT_STICKY
        }
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        val lapIndex = intent?.getIntExtra(EXTRA_LAP_INDEX, -1) ?: -1
        val overlayStyle = parseOverlayStyle(intent?.getStringExtra(EXTRA_OVERLAY_STYLE))
        if (sessionId == null || lapIndex < 0) {
            FileLogger.e(tag, "onStartCommand bad args sid=$sessionId lapIndex=$lapIndex -> stopSelf")
            stopSelf()
            return START_NOT_STICKY
        }
        FileLogger.d(tag, "onStartCommand start export sid=$sessionId lapIndex=$lapIndex style=$overlayStyle")
        startForegroundWithProgress(lapIndex + 1, 0)

        exportJob = scope.launch {
            runExport(sessionId, lapIndex, overlayStyle)
        }
        return START_NOT_STICKY
    }

    private suspend fun runExport(
        sessionId: String,
        lapIndex: Int,
        overlayStyle: VideoOverlayStyle,
    ) {
        val lapNumber = lapIndex + 1
        var target: VideoExportMediaStoreWriter.ExportTarget? = null
        val writer = VideoExportMediaStoreWriter(applicationContext)
        try {
            val loaded = LapPlaybackLoader.load(sessionId, lapIndex, telemetryRepository, trackCatalog)
            if (loaded == null) {
                fail("无法导出该圈（无视频/无样本）", sessionId, lapNumber)
                return
            }
            val (_, ctx) = loaded
            val timeline = ctx.timelinePlan
            if (!timeline.isExportable) {
                val longGap = timeline.blockingLapGaps.firstOrNull()
                val reason = if (longGap != null) {
                    "圈内缺少 ${"%.1f".format(longGap.durationMs / 1000f)} 秒录像，无法导出"
                } else {
                    "该圈头部或尾部缺少录像，无法导出"
                }
                fail(reason, sessionId, lapNumber)
                return
            }
            if (timeline.bridgeableLapGaps.isNotEmpty()) {
                FileLogger.d(
                    tag,
                    "chapter-bridge export gaps=${timeline.bridgeableLapGaps.map { it.durationMs }} " +
                        "sid=$sessionId lap=$lapNumber coverage=${timeline.coverage}",
                )
            }

            val displayName = buildFileName(sessionId, lapNumber)
            target = writer.prepare(displayName)
            VideoExportProgressBus.setRunning(sessionId, lapNumber, 0)

            val progressCallback: (Int, Int) -> Unit = { processed, total ->
                val pct = ((processed.toFloat() / total.toFloat()) * 100f).toInt().coerceIn(0, 99)
                if (pct % 5 == 0) {
                    FileLogger.d(tag, "progress $pct% ($processed/$total) sid=$sessionId lap=$lapNumber")
                }
                VideoExportProgressBus.setRunning(sessionId, lapNumber, pct)
                updateNotification(lapNumber, pct)
            }
            val completed = if (timeline.isCrossSegment) {
                FileLogger.d(
                    tag,
                    "cross-segment export slices=${timeline.slices.size} " +
                        "idx=${timeline.slices.map { it.segment.segmentIndex }}",
                )
                val pipe = MultiSegmentVideoExportPipeline(ctx, overlayStyle)
                multiSegmentPipeline = pipe
                pipe.run(target.muxer, progressCallback)
            } else {
                val slice = timeline.slices.single()
                val clip = VideoExportClip.ClipRange(
                    startPositionMs = slice.sourceStartMs,
                    endPositionMs = slice.sourceEndMs,
                )
                val singleSegmentContext = ctx.copy(
                    videoStartedAtWallClock = slice.segment.startWallClock,
                )
                val pipe = VideoExportPipeline(
                    slice.segment.filePath,
                    singleSegmentContext,
                    clip,
                    overlayStyle,
                )
                pipeline = pipe
                pipe.run(target.muxer, progressCallback)
            }

            if (completed) {
                val uri = writer.finalizeTarget(target)
                FileLogger.d(tag, "export DONE sid=$sessionId lap=$lapNumber uri=$uri")
                VideoExportProgressBus.setDone(sessionId, lapNumber, uri)
            } else {
                writer.abort(target)
                FileLogger.d(tag, "export CANCELLED -> abort cleanup sid=$sessionId lap=$lapNumber")
                VideoExportProgressBus.setFailed("已取消导出")
            }
        } catch (t: Throwable) {
            FileLogger.e(tag, "export FAILED sid=$sessionId lap=$lapNumber", t)
            target?.let { runCatching { writer.abort(it) } }
            VideoExportProgressBus.setFailed("导出失败：${t.message ?: "未知错误"}")
        } finally {
            pipeline = null
            multiSegmentPipeline = null
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun fail(message: String, sessionId: String, lapNumber: Int) {
        FileLogger.e(tag, "$message sid=$sessionId lap=$lapNumber")
        VideoExportProgressBus.setFailed(message)
    }

    private fun buildFileName(sessionId: String, lapNumber: Int): String {
        val shortId = sessionId.takeLast(6)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "BlazePush_${shortId}_lap${lapNumber}_$ts.mp4"
    }

    // ── 前台通知 ──

    private fun startForegroundWithProgress(lapNumber: Int, percent: Int) {
        ensureChannel()
        val notification = buildNotification(lapNumber, percent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // FOREGROUND_SERVICE_TYPE_DATA_SYNC = 1（与 manifest foregroundServiceType=dataSync 对齐；API34 支持）。
            startForeground(
                NOTIFICATION_ID, notification,
                FGS_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(lapNumber: Int, percent: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(lapNumber, percent))
    }

    private fun buildNotification(lapNumber: Int, percent: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("导出 Lap $lapNumber 视频")
            .setContentText("导出中 $percent%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "视频导出", NotificationManager.IMPORTANCE_LOW,
                )
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        pipeline?.cancel()
        super.onDestroy()
        FileLogger.d(tag, "onDestroy")
    }

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_LAP_INDEX = "extra_lap_index"
        const val EXTRA_OVERLAY_STYLE = "extra_overlay_style"
        const val ACTION_CANCEL = "com.blazepush.action.EXPORT_CANCEL"

        private const val CHANNEL_ID = "video_export"
        private const val NOTIFICATION_ID = 0x5E01

        // ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC = 1（API34 支持，与 manifest dataSync 对齐）。
        // 注意：mediaProcessing=8192(1 shl 13) 是 API35 才有，compileSdk34 + 该 manifest 会运行时崩，故用 dataSync。
        // 硬编码官方值避免 compileSdk stub 缺符号。
        private const val FGS_TYPE_DATA_SYNC = 1

        /** 启动导出 Service。 */
        fun start(
            context: Context,
            sessionId: String,
            lapIndex: Int,
            overlayStyle: VideoOverlayStyle = VideoOverlayStyle.FLAT,
        ) {
            val intent = Intent(context, VideoExportService::class.java).apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_LAP_INDEX, lapIndex)
                putExtra(EXTRA_OVERLAY_STYLE, overlayStyle.name)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        internal fun parseOverlayStyle(value: String?): VideoOverlayStyle =
            VideoOverlayStyle.fromStored(value)

        /** 取消正在跑的导出。 */
        fun cancel(context: Context) {
            val intent = Intent(context, VideoExportService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
