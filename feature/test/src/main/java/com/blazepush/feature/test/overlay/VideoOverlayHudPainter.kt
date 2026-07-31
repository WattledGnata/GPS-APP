// @IgnoreFormatCheck
package com.blazepush.feature.test.overlay

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import com.blazepush.feature.test.ui.tracktech.TrackTechColors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal object VideoOverlayHudPainter {
    private val regular = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    private val medium = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val score = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)

    fun draw(canvas: Canvas, width: Float, height: Float, style: VideoOverlayStyle, frame: OverlayHudFrame) {
        if (width <= 0f || height <= 0f) return
        val layout = OverlayHudLayout.calculate(style, width, height)
        when (style) {
            VideoOverlayStyle.FLAT -> drawFlat(canvas, layout, frame)
            VideoOverlayStyle.RAIL -> drawRail(canvas, layout, frame)
            VideoOverlayStyle.MECHANICAL -> drawMechanical(canvas, layout, frame)
        }
    }

    private fun drawFlat(canvas: Canvas, layout: OverlayHudLayout, frame: OverlayHudFrame) {
        val unit = min(layout.speed.width, layout.speed.height)
        drawScrim(canvas, layout.timing, 0.48f, unit * 0.08f)
        val cyan = TrackTechColors.Cyan.toArgb()
        val white = TrackTechColors.TextPrimary.toArgb()
        val muted = TrackTechColors.TextSecondary.toArgb()
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cyan
            strokeWidth = unit * 0.025f
        }
        canvas.drawLine(
            layout.speed.left,
            layout.speed.top,
            layout.speed.left,
            layout.speed.bottom * 0.92f,
            accent,
        )
        drawText(canvas, speedText(frame.speedKmh), layout.speed.left + unit * 0.10f, layout.speed.top + unit * 0.62f, unit * 0.50f, white, medium)
        drawText(canvas, "KM/H", layout.speed.left + unit * 0.12f, layout.speed.bottom, unit * 0.13f, muted, regular)
        drawTiming(canvas, layout.timing, frame, compact = false)
        drawGForce(canvas, layout.gForce, frame, panel = false)
        drawMap(canvas, layout.map, frame, panel = false)
    }

    private fun drawRail(canvas: Canvas, layout: OverlayHudLayout, frame: OverlayHudFrame) {
        val rail = layout.container ?: return
        drawScrim(canvas, rail, 0.72f, rail.height * 0.14f, border = true)
        val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TrackTechColors.BorderAlpha60.toArgb()
            strokeWidth = rail.height * 0.008f
        }
        listOf(layout.speed.right, layout.timing.right, layout.gForce.left, layout.map.left).forEach { x ->
            canvas.drawLine(x, rail.top + rail.height * 0.16f, x, rail.bottom - rail.height * 0.16f, divider)
        }
        val white = TrackTechColors.TextPrimary.toArgb()
        val muted = TrackTechColors.TextSecondary.toArgb()
        drawText(canvas, speedText(frame.speedKmh), layout.speed.left + layout.speed.width * 0.12f, layout.speed.top + layout.speed.height * 0.58f, layout.speed.height * 0.44f, white, medium)
        drawText(canvas, "KM/H", layout.speed.left + layout.speed.width * 0.14f, layout.speed.bottom - layout.speed.height * 0.16f, layout.speed.height * 0.12f, muted, regular)
        drawTiming(canvas, layout.timing, frame, compact = true)
        val deltaRect = HudRect(layout.timing.right, rail.top, layout.gForce.left, rail.bottom)
        drawDeltaPill(canvas, deltaRect, frame.deltaMs)
        drawGForce(canvas, layout.gForce, frame, panel = false)
        drawMap(canvas, layout.map, frame, panel = false)
    }

    private fun drawMechanical(canvas: Canvas, layout: OverlayHudLayout, frame: OverlayHudFrame) {
        val cluster = HudRect(
            layout.speed.left,
            layout.speed.top,
            layout.timing.right,
            layout.gForce.bottom,
        )
        drawChamferedPanel(canvas, cluster)
        drawMechanicalSpeed(canvas, layout.speed, frame)
        drawTiming(canvas, layout.timing, frame, compact = true)
        drawGBar(canvas, layout.gForce, frame)
        val baseline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TrackTechColors.Border.toArgb()
            strokeWidth = cluster.height * 0.008f
        }
        canvas.drawLine(cluster.right, cluster.bottom, layout.map.right, layout.map.bottom, baseline)
        drawMap(canvas, layout.map, frame, panel = false)
    }

    private fun drawTiming(canvas: Canvas, rect: HudRect, frame: OverlayHudFrame, compact: Boolean) {
        val labelSize = rect.height * if (compact) 0.17f else 0.16f
        val timeSize = rect.height * if (compact) 0.35f else 0.31f
        val x = rect.left + rect.width * 0.08f
        drawText(canvas, "LAP ${frame.lapNumber ?: "--"}", x, rect.top + rect.height * 0.25f, labelSize, TrackTechColors.Cyan.toArgb(), regular)
        drawText(canvas, formatElapsed(frame.elapsedMs), x, rect.top + rect.height * 0.66f, timeSize, TrackTechColors.TextPrimary.toArgb(), score)
        if (!compact) {
            drawText(canvas, formatDelta(frame.deltaMs), x, rect.bottom - rect.height * 0.08f, rect.height * 0.18f, deltaColor(frame.deltaMs), medium)
        }
    }

    private fun drawDeltaPill(canvas: Canvas, rect: HudRect, deltaMs: Long?) {
        val padX = rect.width * 0.12f
        val padY = rect.height * 0.30f
        val pill = RectF(rect.left + padX, rect.top + padY, rect.right - padX, rect.bottom - padY)
        val color = deltaColor(deltaMs)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = withAlpha(color, 0.16f)
            style = Paint.Style.FILL
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = rect.height * 0.012f
        }
        canvas.drawRoundRect(pill, pill.height() * 0.22f, pill.height() * 0.22f, fill)
        canvas.drawRoundRect(pill, pill.height() * 0.22f, pill.height() * 0.22f, border)
        drawCenteredText(canvas, formatDelta(deltaMs), rect.centerX, rect.centerY, rect.height * 0.24f, color, medium)
    }

    private fun drawGForce(canvas: Canvas, rect: HudRect, frame: OverlayHudFrame, panel: Boolean) {
        if (panel) drawScrim(canvas, rect, 0.35f, rect.height * 0.10f)
        val radius = min(rect.width, rect.height) * 0.34f
        val mag = sqrt((frame.latG ?: 0.0) * (frame.latG ?: 0.0) + (frame.lonG ?: 0.0) * (frame.lonG ?: 0.0))
        val dotColor = if (mag >= 1.2) TrackTechColors.Red.toArgb() else TrackTechColors.Cyan.toArgb()
        OverlayCanvasPainter.drawGForceBall(
            canvas,
            rect.centerX,
            rect.centerY,
            radius,
            frame.latG ?: 0.0,
            frame.lonG ?: 0.0,
            OverlayCanvasPainter.GForcePaints(
                dialColor = android.graphics.Color.TRANSPARENT,
                outerColor = TrackTechColors.TextSecondary.toArgb(),
                ringColor = TrackTechColors.BorderAlpha60.toArgb(),
                axisColor = TrackTechColors.TextMuted.toArgb(),
                dotColor = dotColor,
            ),
        )
    }

    private fun drawGBar(canvas: Canvas, rect: HudRect, frame: OverlayHudFrame) {
        val y = rect.centerY
        val start = rect.left + rect.width * 0.12f
        val end = rect.right - rect.width * 0.10f
        val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TrackTechColors.BorderAlpha60.toArgb()
            strokeWidth = rect.height * 0.025f
        }
        canvas.drawLine(start, y, end, y, axis)
        val g = (frame.latG ?: 0.0).coerceIn(-1.5, 1.5)
        val x = ((g + 1.5) / 3.0 * (end - start) + start).toFloat()
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TrackTechColors.Cyan.toArgb() }
        canvas.drawCircle(x, y, rect.height * 0.10f, dot)
        drawText(canvas, "G", rect.left, y + rect.height * 0.08f, rect.height * 0.22f, TrackTechColors.TextSecondary.toArgb(), medium)
    }

    private fun drawMechanicalSpeed(canvas: Canvas, rect: HudRect, frame: OverlayHudFrame) {
        val radius = min(rect.width, rect.height) * 0.41f
        val arcRect = RectF(
            rect.left + rect.width * 0.07f,
            rect.top + rect.height * 0.03f,
            rect.left + rect.width * 0.07f + radius * 2f,
            rect.top + rect.height * 0.03f + radius * 2f,
        )
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TrackTechColors.BorderAlpha60.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = rect.height * 0.040f
            strokeCap = Paint.Cap.BUTT
        }
        canvas.drawArc(arcRect, 145f, 250f, false, base)
        val fraction = ((frame.speedKmh ?: 0.0) / frame.maxSpeedKmh.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
        val needleColor = if (fraction >= 0.78) {
            TrackTechColors.Red.toArgb()
        } else {
            TrackTechColors.Cyan.toArgb()
        }
        base.color = needleColor
        canvas.drawArc(arcRect, 145f, (250f * fraction).toFloat(), false, base)

        val cx = arcRect.centerX()
        val cy = arcRect.centerY()
        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TrackTechColors.TextSecondary.toArgb()
            strokeCap = Paint.Cap.ROUND
        }
        repeat(11) { index ->
            val tickAngle = Math.toRadians((145f + index * 25f).toDouble())
            val major = index % 2 == 0
            val outerRadius = radius * 0.90f
            val innerRadius = radius * if (major) 0.74f else 0.79f
            tickPaint.strokeWidth = rect.height * if (major) 0.014f else 0.008f
            canvas.drawLine(
                cx + cos(tickAngle).toFloat() * innerRadius,
                cy + sin(tickAngle).toFloat() * innerRadius,
                cx + cos(tickAngle).toFloat() * outerRadius,
                cy + sin(tickAngle).toFloat() * outerRadius,
                tickPaint,
            )
        }

        drawCenteredText(
            canvas,
            speedText(frame.speedKmh),
            cx,
            rect.top + rect.height * 0.75f,
            rect.height * 0.31f,
            TrackTechColors.TextPrimary.toArgb(),
            medium,
        )
        drawCenteredText(
            canvas,
            "KM/H",
            cx,
            rect.bottom - rect.height * 0.07f,
            rect.height * 0.10f,
            TrackTechColors.TextMuted.toArgb(),
            regular,
        )

        val needleAngle = Math.toRadians(
            mechanicalSpeedNeedleAngle(frame.speedKmh, frame.maxSpeedKmh).toDouble(),
        )
        val ux = cos(needleAngle).toFloat()
        val uy = sin(needleAngle).toFloat()
        val px = -uy
        val py = ux
        val tipX = cx + ux * radius * 0.72f
        val tipY = cy + uy * radius * 0.72f
        val tailX = cx - ux * radius * 0.18f
        val tailY = cy - uy * radius * 0.18f
        val halfWidth = rect.height * 0.018f

        val needleShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(android.graphics.Color.BLACK, 0.72f)
            style = Paint.Style.STROKE
            strokeWidth = rect.height * 0.040f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(tailX, tailY, tipX, tipY, needleShadow)

        val needle = Path().apply {
            moveTo(tipX, tipY)
            lineTo(cx + px * halfWidth, cy + py * halfWidth)
            lineTo(tailX, tailY)
            lineTo(cx - px * halfWidth, cy - py * halfWidth)
            close()
        }
        canvas.drawPath(
            needle,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = needleColor
                style = Paint.Style.FILL
                setShadowLayer(rect.height * 0.015f, 0f, rect.height * 0.008f, android.graphics.Color.BLACK)
            },
        )
        canvas.drawCircle(
            cx,
            cy,
            rect.height * 0.046f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TrackTechColors.TextPrimary.toArgb() },
        )
        canvas.drawCircle(
            cx,
            cy,
            rect.height * 0.025f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = needleColor },
        )
    }

    private fun drawMap(canvas: Canvas, rect: HudRect, frame: OverlayHudFrame, panel: Boolean) {
        if (frame.trackPoints.size < 2) return
        if (panel) drawScrim(canvas, rect, 0.35f, rect.height * 0.10f)
        canvas.save()
        canvas.translate(rect.left, rect.top)
        OverlayCanvasPainter.drawTrackMiniMap(
            canvas = canvas,
            width = rect.width,
            height = rect.height,
            padding = min(rect.width, rect.height) * 0.10f,
            points = frame.trackPoints,
            currentLat = frame.currentLat,
            currentLon = frame.currentLon,
            paints = OverlayCanvasPainter.MiniMapPaints(
                lineColor = TrackTechColors.Cyan.toArgb(),
                dotColor = TrackTechColors.TextPrimary.toArgb(),
                strokeWidth = min(rect.width, rect.height) * 0.015f,
            ),
        )
        canvas.restore()
    }

    private fun drawChamferedPanel(canvas: Canvas, rect: HudRect) {
        val cut = rect.height * 0.12f
        val path = Path().apply {
            moveTo(rect.left + cut, rect.top)
            lineTo(rect.right - cut, rect.top)
            lineTo(rect.right, rect.top + cut)
            lineTo(rect.right, rect.bottom - cut)
            lineTo(rect.right - cut, rect.bottom)
            lineTo(rect.left + cut, rect.bottom)
            lineTo(rect.left, rect.bottom - cut)
            lineTo(rect.left, rect.top + cut)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(TrackTechColors.Surface.toArgb(), 0.76f)
            style = Paint.Style.FILL
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TrackTechColors.Border.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = rect.height * 0.008f
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, border)
    }

    private fun drawScrim(canvas: Canvas, rect: HudRect, alpha: Float, radius: Float, border: Boolean = false) {
        val r = RectF(rect.left, rect.top, rect.right, rect.bottom)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(TrackTechColors.Surface.toArgb(), alpha)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(r, radius, radius, fill)
        if (border) {
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = TrackTechColors.BorderAlpha60.toArgb()
                style = Paint.Style.STROKE
                strokeWidth = rect.height * 0.008f
            }
            canvas.drawRoundRect(r, radius, radius, stroke)
        }
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, baseline: Float, size: Float, color: Int, typeface: Typeface) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            this.typeface = typeface
            setShadowLayer(size * 0.08f, 0f, size * 0.03f, android.graphics.Color.BLACK)
        }
        canvas.drawText(text, x, baseline, paint)
    }

    private fun drawCenteredText(canvas: Canvas, text: String, cx: Float, cy: Float, size: Float, color: Int, typeface: Typeface) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            this.typeface = typeface
            textAlign = Paint.Align.CENTER
        }
        val baseline = cy - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, cx, baseline, paint)
    }

    private fun speedText(speedKmh: Double?): String = speedKmh?.let { "%.0f".format(it) } ?: "--"

    private fun formatElapsed(ms: Long?): String {
        if (ms == null || ms < 0) return "--:--.---"
        val minutes = ms / 60_000
        val seconds = (ms / 1_000) % 60
        return "%d:%02d.%03d".format(minutes, seconds, ms % 1_000)
    }

    private fun formatDelta(ms: Long?): String {
        if (ms == null) return "--"
        return "%s%.2f".format(if (ms >= 0) "+" else "-", abs(ms) / 1000.0)
    }

    private fun deltaColor(ms: Long?): Int = when {
        ms == null -> TrackTechColors.TextMuted.toArgb()
        ms < 0 -> TrackTechColors.Green.toArgb()
        ms > 0 -> TrackTechColors.Red.toArgb()
        else -> TrackTechColors.TextPrimary.toArgb()
    }

    private fun withAlpha(argb: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        return (argb and 0x00ffffff) or (a shl 24)
    }
}
