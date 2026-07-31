// @IgnoreFormatCheck
package com.blazepush.feature.test.export

import android.graphics.Bitmap
import android.graphics.Canvas
import com.blazepush.feature.test.FileLogger
import com.blazepush.feature.test.overlay.OverlayCanvasPainter
import com.blazepush.feature.test.overlay.OverlayHudFrame
import com.blazepush.feature.test.overlay.VideoOverlayStyle
import com.blazepush.feature.test.recording.VideoTelemetrySync
import com.blazepush.feature.test.usecase.GaugeMath
import com.blazepush.feature.test.usecase.VideoOverlayTelemetry

/**
 * 导出端每帧 overlay 绘制器（round video-export-burned-overlay · Round B）。
 *
 * 把回放屏 `OverlayHud` 四角布局（左上 SPEED / 右上 G / 左下 LAP+delta / 右下小地图）复刻到一张
 * **透明 ARGB_8888 Bitmap**，调**同一套** [OverlayCanvasPainter] 绘制函数 → 与回放视觉一致（杜绝双份绘制）。
 *
 * 颜色真相源仍是 [TrackTechColors]（经 `Color.toArgb()` 转 ARGB int，与回放端 `SpeedometerGauge` /
 * `GForceBall` / `TrackMiniMap` 完全同源，不复刻 hex）。仪表尺寸按帧高度比例（非 dp）→ 不同源分辨率
 * overlay 占比稳定。
 *
 * 每帧 overlay 数据复用既有同步纯函数：帧 PTS → [VideoTelemetrySync.frameWallClock] →
 * [VideoTelemetrySync.findNearestSampleIndex] → [VideoOverlayTelemetry.resolveCurrentLap] /
 * [VideoOverlayTelemetry.computeDeltaMs]（与回放端 `updateOverlay` 同一口径）。
 *
 * @author CC
 * @description per-frame overlay bitmap renderer for export (reuses OverlayCanvasPainter)
 * @date 2026-05-31
 */
internal class ExportOverlayRenderer(
    private val ctx: LapPlaybackLoader.LapPlaybackContext,
    width: Int,
    height: Int,
    private val style: VideoOverlayStyle,
) {
    private val tag = "ExportOverlay"

    /** 复用同一张透明 Bitmap（每帧 eraseColor(0) 清空重画，避免每帧重分配 ≈8MB@1080p）。 */
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)

    private val w = width.toFloat()
    private val h = height.toFloat()

    private val maxSpeedKmh = GaugeMath.speedGaugeMax(ctx.topSpeedKmh).toDouble()

    /**
     * 画一帧 overlay 到 [bitmap]（先清空再四角绘制）。返回的 bitmap 供 GL 上传。
     *
     * @param frameWallClock 当前帧绝对 wallClock（[VideoTelemetrySync.frameWallClock] 算出）
     */
    fun renderFrame(frameWallClock: Long): Bitmap {
        bitmap.eraseColor(0) // 透明

        // 查最近邻样本（复用既有纯函数）
        val idx = VideoTelemetrySync.findNearestSampleIndex(frameWallClock, ctx.sampleWallClocks)
        val frame = ctx.frames.getOrNull(idx)

        // 圈号/elapsed + delta（复用既有纯函数，与回放 updateOverlay 同口径）
        val lap = VideoOverlayTelemetry.resolveCurrentLap(frameWallClock, ctx.lapWindows)
        val bestRef = ctx.bestReference
        val deltaMs = if (lap != null && bestRef != null && frame != null) {
            VideoOverlayTelemetry.computeDeltaMs(
                reference = bestRef,
                currentLapElapsedMs = lap.currentLapElapsedMs,
                currentLat = frame.lat,
                currentLon = frame.lon,
            )
        } else {
            null
        }

        OverlayCanvasPainter.drawHud(
            canvas = canvas,
            width = w,
            height = h,
            style = style,
            frame = OverlayHudFrame(
                speedKmh = frame?.speedKmh,
                latG = frame?.latG,
                lonG = frame?.lonG,
                lapNumber = lap?.lapNumber,
                elapsedMs = lap?.currentLapElapsedMs,
                deltaMs = deltaMs,
                trackPoints = ctx.trackPoints,
                currentLat = frame?.lat,
                currentLon = frame?.lon,
                maxSpeedKmh = maxSpeedKmh,
            ),
        )

        return bitmap
    }

    /** 释放 bitmap（drain loop finally 调）。 */
    fun release() {
        if (!bitmap.isRecycled) bitmap.recycle()
        FileLogger.d(tag, "overlay renderer released ${w.toInt()}x${h.toInt()}")
    }
}
