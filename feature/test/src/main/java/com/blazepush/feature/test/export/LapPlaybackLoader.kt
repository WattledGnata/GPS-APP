// @IgnoreFormatCheck
package com.blazepush.feature.test.export

import com.blazepush.core.data.repository.TelemetryRepository
import com.blazepush.core.domain.model.LapTelemetry
import com.blazepush.core.domain.model.LapTelemetrySample
import com.blazepush.core.domain.model.TelemetrySession
import com.blazepush.feature.test.FileLogger
import com.blazepush.feature.test.model.track.GeoPoint
import com.blazepush.feature.test.repository.TrackCatalog
import com.blazepush.feature.test.usecase.ReferenceLapIndex
import com.blazepush.feature.test.usecase.VideoOverlayTelemetry

/**
 * 按圈回放/导出共享数据加载 helper（round video-export-burned-overlay · Round B）。
 *
 * 把原 `LapVideoPlaybackScreen.loadLapPlaybackData` 的"进屏一次性读 session + 整 session overlay 帧 +
 * 各圈窗口 + best reference + 赛道轮廓 + 目标圈起止 wallClock"逻辑下沉为**无 Compose 依赖**的纯 IO helper，
 * 让回放屏与导出管线（[VideoExportPipeline]）复用同一套加载逻辑 → overlay 帧/圈窗口/best/轨道点
 * **两端同源零漂移**（避免导出端另起一套加载导致 overlay 数据与回放不一致）。
 *
 * 仅做 repo IO + 复用 [VideoOverlayTelemetry] 纯函数，无 GL/Compose/Service 依赖（可在任意后台线程调）。
 *
 * @author CC
 * @description shared per-lap playback/export data loader (no Compose)
 * @date 2026-05-31
 */
object LapPlaybackLoader {

    private const val TAG = "VideoOverlay"

    /**
     * 单圈回放/导出上下文（与原 `LapPlaybackContext` 字段对齐，但放在 export 包供两端复用）。
     *
     * @property frames            整 session 帧（按样本预算好的 G/速度/经纬度）
     * @property sampleWallClocks  与 frames 一一对应的 absoluteTsMs 升序列表（二分查最近邻）
     * @property lapWindows        全 session 各圈窗口（resolveCurrentLap 显示圈号/elapsed）
     * @property bestReference     best 圈索引（实时 delta 投影；无 best → null）
     * @property trackPoints       赛道轮廓（小地图）
     * @property videoStartedAtWallClock 视频录制开始 wallClock（与样本同时钟域）
     * @property lapStartWallClock 当前回放/导出圈开圈 crossing wallClock
     * @property lapEndWallClock   当前回放/导出圈收圈 crossing wallClock
     * @property lapNumber         当前圈号（1-based）
     * @property topSpeedKmh       本 session 最高尾速（km/h，速度表动态量程）
     */
    // internal：bestReference 是 internal 类型 ReferenceLapIndex，类与字段同 module 可见即可
    // （回放屏 typealias + 导出端 ExportOverlayRenderer/Pipeline 均在 feature:test module 内）。
    internal data class LapPlaybackContext(
        val frames: List<VideoOverlayTelemetry.OverlayFrame>,
        val sampleWallClocks: List<Long>,
        val lapWindows: List<VideoOverlayTelemetry.LapWindow>,
        val bestReference: ReferenceLapIndex?,
        val trackPoints: List<GeoPoint>,
        val videoStartedAtWallClock: Long,
        val lapStartWallClock: Long,
        val lapEndWallClock: Long,
        val lapNumber: Int,
        val topSpeedKmh: Double?,
    )

    /**
     * 进屏/进导出一次性读 session metadata + 当前圈起止 wallClock + 整 session overlay 上下文。
     * 返回 null 表示无法播放/导出（无 session / 无 video / 无目标圈 / 无样本）。
     *
     * 注：overlay 帧 / 圈窗口 / best reference 仍按整 session 构建（resolveCurrentLap / delta 投影需要
     * 全圈窗口与 best 圈），但圈时间轴由 lapIndex 指向的目标圈起止 wallClock 主导。
     *
     * MUST 在后台线程调（repo 是 suspend）。
     */
    internal suspend fun load(
        sessionId: String,
        lapIndex: Int,
        repo: TelemetryRepository,
        trackCatalog: TrackCatalog,
    ): Pair<TelemetrySession, LapPlaybackContext>? {
        val session = repo.getSession(sessionId) ?: return null
        val videoStartedAt = session.videoStartedAtWallClock ?: return null
        if (session.videoFilePath == null) return null

        // 目标圈：lapIndex 指向的圈（lapNumber = lapIndex + 1，与详情屏 VALID/BEST 圈一致）
        val targetLap: LapTelemetry = repo.getLapTelemetry(sessionId, lapIndex) ?: return null

        // 逐圈拼接整 session 样本（升序 absoluteTsMs）+ 各圈窗口（overlay 仍需全 session 上下文）
        val allSamples = mutableListOf<LapTelemetrySample>()
        val lapWindows = mutableListOf<VideoOverlayTelemetry.LapWindow>()
        var i = 0
        while (true) {
            val lap = repo.getLapTelemetry(sessionId, i) ?: break
            allSamples.addAll(lap.samples)
            lapWindows.add(
                VideoOverlayTelemetry.LapWindow(
                    lapNumber = i + 1,
                    lapStartWallClock = lap.lapStartWallClock,
                    lapEndWallClock = lap.lapEndWallClock,
                ),
            )
            i++
            if (i > 1000) break // 安全上界防意外死循环
        }
        if (allSamples.isEmpty()) return null

        // 样本可能跨圈有重叠（圈尾==下圈头）；按 absoluteTsMs 升序排序
        val sorted = allSamples.sortedBy { it.absoluteTsMs }
        val frames = VideoOverlayTelemetry.buildFrames(sorted)
        val sampleWallClocks = frames.map { it.absoluteTsMs }

        val bestReference = buildBestReference(sessionId, repo, session.bestLapMs)
        val trackPoints = resolveTrackPoints(session.trackId, trackCatalog)

        // 最高尾速：优先 session.topSpeedKmh（endSession 时 binary 全扫派生，可靠），
        // null 时 fallback 到已加载 overlay 样本的最大 speedKmh。
        val topSpeedKmh: Double? = session.topSpeedKmh
            ?: frames.maxOfOrNull { it.speedKmh }?.takeIf { it > 0.0 }
        FileLogger.d(TAG, "loader topSpeedKmh=$topSpeedKmh (session=${session.topSpeedKmh}) sid=$sessionId")

        return session to LapPlaybackContext(
            frames = frames,
            sampleWallClocks = sampleWallClocks,
            lapWindows = lapWindows,
            bestReference = bestReference,
            trackPoints = trackPoints,
            videoStartedAtWallClock = videoStartedAt,
            lapStartWallClock = targetLap.lapStartWallClock,
            lapEndWallClock = targetLap.lapEndWallClock,
            lapNumber = lapIndex + 1,
            topSpeedKmh = topSpeedKmh,
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
                FileLogger.d(TAG, "loader best ref lapIndex=$lapIndex dur=$bestLapMs pts=${lap.samples.size} ok=${ref != null}")
                return ref
            }
            lapIndex++
        }
        FileLogger.d(TAG, "loader no best lap matched bestLapMs=$bestLapMs")
        return null
    }

    /** 解析赛道轮廓点；trackId null / 解析不到 → 空列表（小地图降级隐藏）。 */
    private suspend fun resolveTrackPoints(
        trackId: String?,
        trackCatalog: TrackCatalog,
    ): List<GeoPoint> {
        if (trackId == null) return emptyList()
        runCatching { trackCatalog.getAllTracks() }
        val track = trackCatalog.getTrack(trackId)
        val points = track?.referencePath?.points ?: emptyList()
        FileLogger.d(TAG, "loader track geometry trackId=$trackId pts=${points.size}")
        return points
    }
}
