// @IgnoreFormatCheck
package com.blazepush.feature.test.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import com.blazepush.feature.test.FileLogger
import com.blazepush.feature.test.overlay.OverlayCanvasPainter
import com.blazepush.feature.test.recording.VideoTelemetrySync
import com.blazepush.feature.test.ui.tracktech.TrackTechColors
import com.blazepush.feature.test.usecase.GaugeMath
import com.blazepush.feature.test.usecase.VideoOverlayTelemetry
import kotlin.math.sqrt

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
) {
    private val tag = "ExportOverlay"

    /** 复用同一张透明 Bitmap（每帧 eraseColor(0) 清空重画，避免每帧重分配 ≈8MB@1080p）。 */
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)

    private val w = width.toFloat()
    private val h = height.toFloat()

    // 仪表尺寸（按帧高度比例，对齐回放 120dp/96dp 在横屏的观感）
    private val pad = h * 0.03f                 // 四角留边（≈回放 12dp）
    private val gaugeRadius = h * 0.11f          // 速度表/ G 球半径（直径≈22% 帧高）
    private val miniMapW = h * 0.20f
    private val miniMapH = h * 0.20f
    private val lapLabelTextSize = h * 0.035f
    private val lapScoreTextSize = h * 0.05f

    private val maxSpeedKmh = GaugeMath.speedGaugeMax(ctx.topSpeedKmh).toDouble()

    // ── 颜色容器（TrackTechColors 真相源 → toArgb；与回放端各 gauge composable 同源） ──
    private val dialArgb = TrackTechColors.Surface.copy(alpha = 0.55f).toArgb()

    private fun speedPaints(needleArgb: Int) = OverlayCanvasPainter.SpeedometerPaints(
        dialColor = dialArgb,
        borderColor = TrackTechColors.Border.toArgb(),
        tickMajorColor = TrackTechColors.TextSecondary.toArgb(),
        tickMinorColor = TrackTechColors.TextMuted.toArgb(),
        labelColor = TrackTechColors.TextMuted.toArgb(),
        needleColor = needleArgb,
        hubColor = TrackTechColors.TextPrimary.toArgb(),
    )

    private fun gPaints(dotArgb: Int) = OverlayCanvasPainter.GForcePaints(
        dialColor = dialArgb,
        outerColor = TrackTechColors.Border.toArgb(),
        ringColor = TrackTechColors.BorderAlpha60.toArgb(),
        axisColor = TrackTechColors.TextMuted.toArgb(),
        dotColor = dotArgb,
    )

    private val miniMapPaints = OverlayCanvasPainter.MiniMapPaints(
        lineColor = TrackTechColors.BorderAlpha60.toArgb(),
        dotColor = TrackTechColors.Cyan.toArgb(),
    )

    // Score 风格 = SansSerif Bold Italic（V2：时间字符串 MUST NOT DSEG7）；LAP 标签 = SansSerif。
    private val scoreTypeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)
    private val labelTypeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

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

        val speedKmh = frame?.speedKmh ?: 0.0
        val latG = frame?.latG ?: 0.0
        val lonG = frame?.lonG ?: 0.0

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

        // ── 左上：速度指针表（高速段红，与回放 SpeedometerGauge 同逻辑） ──
        val needleArgb = if (speedKmh >= maxSpeedKmh * 0.7) {
            TrackTechColors.Red.toArgb()
        } else {
            TrackTechColors.Cyan.toArgb()
        }
        OverlayCanvasPainter.drawSpeedometer(
            canvas = canvas,
            cx = pad + gaugeRadius,
            cy = pad + gaugeRadius,
            radius = gaugeRadius,
            speedKmh = speedKmh,
            maxSpeedKmh = maxSpeedKmh,
            paints = speedPaints(needleArgb),
        )

        // ── 右上：G 球（高合成 G 红，与回放 GForceBall 同逻辑） ──
        val gMag = sqrt(latG * latG + lonG * lonG)
        val dotArgb = if (gMag >= GaugeMath.GBALL_MAX_G * 0.8) {
            TrackTechColors.Red.toArgb()
        } else {
            TrackTechColors.Cyan.toArgb()
        }
        OverlayCanvasPainter.drawGForceBall(
            canvas = canvas,
            cx = w - pad - gaugeRadius,
            cy = pad + gaugeRadius,
            radius = gaugeRadius,
            latG = latG,
            lonG = lonG,
            paints = gPaints(dotArgb),
        )

        // ── 左下：圈速计时面板（LAP N + 圈速 + delta；delta 快绿慢红，与回放 LapTimeCorner 同逻辑） ──
        val deltaArgb = when {
            deltaMs == null -> TrackTechColors.TextMuted.toArgb()
            deltaMs < 0 -> TrackTechColors.Green.toArgb()
            deltaMs > 0 -> TrackTechColors.Red.toArgb()
            else -> TrackTechColors.TextPrimary.toArgb()
        }
        val lapPaints = OverlayCanvasPainter.LapTimePaints(
            panelColor = dialArgb,
            labelColor = TrackTechColors.Cyan.toArgb(),
            elapsedColor = TrackTechColors.TextPrimary.toArgb(),
            deltaColor = deltaArgb,
            labelTypeface = labelTypeface,
            scoreTypeface = scoreTypeface,
        )
        // 面板高度由字号估算（OverlayCanvasPainter 内部按字号布局），左下角对齐
        val panelEstH = lapLabelTextSize + lapScoreTextSize * 2 + lapLabelTextSize * 1.5f
        OverlayCanvasPainter.drawLapTimePanel(
            canvas = canvas,
            x = pad,
            y = h - pad - panelEstH,
            lapNumber = lap?.lapNumber,
            elapsedMs = lap?.currentLapElapsedMs,
            deltaMs = deltaMs,
            labelTextSize = lapLabelTextSize,
            scoreTextSize = lapScoreTextSize,
            paints = lapPaints,
        )

        // ── 右下：赛道小地图（≥2 点才画） ──
        if (ctx.trackPoints.size >= 2) {
            // 用一个子区域坐标系：把 canvas 平移到右下角矩形（painter 用 0..miniMapW/H 局部坐标）
            canvas.save()
            canvas.translate(w - pad - miniMapW, h - pad - miniMapH)
            OverlayCanvasPainter.drawTrackMiniMap(
                canvas = canvas,
                width = miniMapW,
                height = miniMapH,
                padding = miniMapW * 0.06f,
                points = ctx.trackPoints,
                currentLat = frame?.lat,
                currentLon = frame?.lon,
                paints = miniMapPaints,
            )
            canvas.restore()
        }

        return bitmap
    }

    /** 释放 bitmap（drain loop finally 调）。 */
    fun release() {
        if (!bitmap.isRecycled) bitmap.recycle()
        FileLogger.d(tag, "overlay renderer released ${w.toInt()}x${h.toInt()}")
    }
}
