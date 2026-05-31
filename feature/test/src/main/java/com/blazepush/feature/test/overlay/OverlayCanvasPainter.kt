// @IgnoreFormatCheck
package com.blazepush.feature.test.overlay

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.blazepush.feature.test.model.track.GeoPoint
import com.blazepush.feature.test.usecase.GaugeMath
import com.blazepush.feature.test.ui.tracktech.TrackMiniMapProjection
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 共享 overlay 绘制层（round video-export-burned-overlay · Round A）。
 *
 * 把速度指针表 / G 球摩擦圆 / 赛道小地图 / 圈速计时面板的**绘制逻辑**下沉成纯
 * [android.graphics.Canvas] 绘制函数，**不依赖 Compose**。两端复用同一套代码：
 *
 * - **回放端（Compose）**：`SpeedometerGauge` / `GForceBall` / `TrackMiniMap` /
 *   `LapVideoPlaybackScreen.LapTimeCorner` 经 `drawIntoCanvas { OverlayCanvasPainter.xxx(it.nativeCanvas, …) }`
 *   调用（`nativeCanvas` 即 [android.graphics.Canvas]）。视觉与重构前零变化（同图元、同坐标、同数学）。
 * - **导出端（round B）**：每帧画到透明 `Bitmap(ARGB_8888)` 的 `Canvas(bitmap)` 上调同一套函数，
 *   上传为 GL 纹理叠到视频帧。一套绘制代码两端共享 → 导出成片 = 回放所见，杜绝漂移。
 *
 * 几何全部复用已单测纯函数 [GaugeMath]（速度→指针角 / G→动点偏移）与
 * [TrackMiniMapProjection]（赛道投影）；绘制层只把这些数学结果翻译成 `drawLine` / `drawCircle`
 * / `drawPath` / `drawText` 图元（Compose `DrawScope` 的标准图元在 `android.graphics.Canvas` 上都有等价 API）。
 *
 * 颜色**不在本类复刻 hex**：由调用方用各自的 Compose `Color` 转 ARGB int 填入 [Paints] 容器
 * （回放端真相源仍是 `TrackTechColors`），杜绝色号双份漂移。
 *
 * "角度/偏移→像素点" 的最后一跳几何抽成 internal 纯函数（[needleTip] / [tickEndpoints] /
 * [ballDotCenter]）供 JVM 单测（本工程测试无 Robolectric，`Bitmap`/`Canvas` 是 stub 不可像素断言，
 * 故几何最后一跳必须纯函数化才能验证）。
 *
 * @author CC
 * @description shared android.graphics.Canvas overlay painter reused by playback + export
 * @date 2026-05-31
 */
object OverlayCanvasPainter {

    // ── 速度表绘制参数（与 SpeedometerGauge.kt 原 DrawScope 块 1:1 一致） ───────────
    private const val SPEEDO_MAJOR_STEP_KMH = 20.0
    private const val SPEEDO_MINOR_STEP_KMH = 10.0

    // ── 赛道小地图场景常量 ────────────────────────────────────────────────────────────
    /** overlay 播放页小地图轮廓线宽（px）：保持原细线 */
    const val MINIMAP_STROKE_OVERLAY = 2f
    /** thumbnail 预览场景轮廓线宽（px）：加粗 2.5× 在小尺寸卡片下清晰可见 */
    const val MINIMAP_STROKE_THUMBNAIL = 5f
    /** thumbnail 起点标记半径（px）：比线宽大、足够醒目 */
    const val MINIMAP_START_MARKER_RADIUS = 8f

    // ── G 球绘制参数（与 GForceBall.kt 原 DrawScope 块 1:1 一致） ──────────────────
    // 同心刻度圈占外圈比例：0.5G / 1.0G / 1.5G(外圈)。

    /**
     * 速度指针表绘制 Paint 容器。颜色由回放端 `TrackTechColors` 转 ARGB int 填入。
     *
     * @property dialColor       表盘半透明底（Surface @0.55）
     * @property borderColor     外环
     * @property tickMajorColor  主刻度
     * @property tickMinorColor  次刻度
     * @property labelColor      主刻度数字标注
     * @property needleColor     指针（高速段红 / 否则 cyan，由调用方按当前速度算好传入）
     * @property hubColor        中心轴外圈
     */
    data class SpeedometerPaints(
        val dialColor: Int,
        val borderColor: Int,
        val tickMajorColor: Int,
        val tickMinorColor: Int,
        val labelColor: Int,
        val needleColor: Int,
        val hubColor: Int,
    )

    /**
     * G 球摩擦圆绘制 Paint 容器。
     *
     * @property dialColor  表盘半透明底（Surface @0.55）
     * @property outerColor 外圈（1.5G 边界）
     * @property ringColor  内同心圈（0.5/1.0G）
     * @property axisColor  十字轴
     * @property dotColor   动点（合成 G 接近极限红 / 否则 cyan，由调用方算好传入）
     */
    data class GForcePaints(
        val dialColor: Int,
        val outerColor: Int,
        val ringColor: Int,
        val axisColor: Int,
        val dotColor: Int,
    )

    /**
     * 赛道小地图绘制 Paint 容器。
     *
     * @property lineColor        赛道轮廓 polyline（BorderAlpha60）
     * @property dotColor         当前位置高亮点（Cyan）
     * @property strokeWidth      轮廓线宽（px）；overlay 播放页小地图传 [MINIMAP_STROKE_OVERLAY]（细线），
     *                            thumbnail 预览场景传 [MINIMAP_STROKE_THUMBNAIL]（粗线）
     * @property startMarkerColor 起点标记填充色（ARGB int）；0（透明）= 不画起点标记（overlay 播放页默认）；
     *                            thumbnail 场景传 Cyan ARGB
     */
    data class MiniMapPaints(
        val lineColor: Int,
        val dotColor: Int,
        val strokeWidth: Float = MINIMAP_STROKE_OVERLAY,
        val startMarkerColor: Int = 0,
    )

    /**
     * 圈速计时面板绘制 Paint 容器。
     *
     * @property panelColor    面板半透明底（Surface @0.55）
     * @property labelColor    "LAP N" 标签（Cyan）
     * @property elapsedColor  圈速字符串（TextPrimary）
     * @property deltaColor    delta 字符串（快=Green / 慢=Red / 无=TextMuted，由调用方算好传入）
     * @property labelTypeface "LAP N" 字体（V2：UiTextLabel = SansSerif Medium）
     * @property scoreTypeface 圈速/delta 字体（V2：Score = SansSerif Bold Italic，MUST NOT DSEG7）
     */
    data class LapTimePaints(
        val panelColor: Int,
        val labelColor: Int,
        val elapsedColor: Int,
        val deltaColor: Int,
        val labelTypeface: Typeface,
        val scoreTypeface: Typeface,
    )

    /**
     * 画老式圆形指针速度表（表盘底 + 外环 + 主/次刻度 + 主刻度数字 + 指针 + 尾翼 + 中心轴）。
     * 与 `SpeedometerGauge.kt:57-145` 原 DrawScope 块图元 1:1 对应。
     *
     * @param canvas    目标 [android.graphics.Canvas]（回放端 = nativeCanvas / 导出端 = Bitmap canvas）
     * @param cx        表盘中心 x（px）
     * @param cy        表盘中心 y（px）
     * @param radius    表盘半径（px，= min(w,h)/2）
     * @param speedKmh  当前速度（km/h）；调用方对 null 传 0（指针停量程起点）
     * @param maxSpeedKmh 量程上界（km/h）
     * @param paints    颜色容器
     */
    fun drawSpeedometer(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        speedKmh: Double,
        maxSpeedKmh: Double,
        paints: SpeedometerPaints,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 表盘底（半透明圆）
        paint.style = Paint.Style.FILL
        paint.color = paints.dialColor
        canvas.drawCircle(cx, cy, radius, paint)
        // 外环
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = paints.borderColor
        canvas.drawCircle(cx, cy, radius, paint)

        // 刻度：主刻度每 20km/h、次刻度每 10km/h
        val tickOuter = radius - 4f
        val tickMajorLen = radius * 0.16f
        val tickMinorLen = radius * 0.08f
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = paints.labelColor
            textSize = radius * 0.16f
            textAlign = Paint.Align.CENTER
        }
        var v = 0.0
        while (v <= maxSpeedKmh + 1e-6) {
            val isMajor = (v % SPEEDO_MAJOR_STEP_KMH) < 1e-6
            val angleDeg = GaugeMath.speedToNeedleAngle(v, maxKmh = maxSpeedKmh)
            val len = if (isMajor) tickMajorLen else tickMinorLen
            val (inner, outer) = tickEndpoints(cx, cy, angleDeg, tickOuter, len)
            paint.style = Paint.Style.STROKE
            paint.color = if (isMajor) paints.tickMajorColor else paints.tickMinorColor
            paint.strokeWidth = if (isMajor) 2.5f else 1.5f
            canvas.drawLine(inner.first, inner.second, outer.first, outer.second, paint)
            // 主刻度数字标注（往内一点）
            if (isMajor) {
                val labelR = tickOuter - tickMajorLen - radius * 0.12f
                val rad = Math.toRadians(angleDeg)
                val lx = cx + cos(rad).toFloat() * labelR
                val ly = cy + sin(rad).toFloat() * labelR
                // 垂直居中：baseline 偏移
                canvas.drawText(v.toInt().toString(), lx, ly + labelPaint.textSize / 3f, labelPaint)
            }
            v += SPEEDO_MINOR_STEP_KMH
        }

        // 指针
        val needleAngleDeg = GaugeMath.speedToNeedleAngle(speedKmh, maxKmh = maxSpeedKmh)
        val needleLen = radius * 0.72f
        val tailLen = radius * 0.18f
        val tip = needleTip(cx, cy, needleAngleDeg, needleLen)
        val tail = needleTip(cx, cy, needleAngleDeg, -tailLen)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = paints.needleColor
        canvas.drawLine(tail.first, tail.second, tip.first, tip.second, paint)
        paint.strokeCap = Paint.Cap.BUTT
        // 中心轴
        paint.style = Paint.Style.FILL
        paint.color = paints.hubColor
        canvas.drawCircle(cx, cy, radius * 0.06f, paint)
        paint.color = paints.needleColor
        canvas.drawCircle(cx, cy, radius * 0.03f, paint)
    }

    /**
     * 画 G 球摩擦圆（表盘底 + 同心刻度圈 + 十字轴 + 动点光晕 + 实心点）。
     * 与 `GForceBall.kt:51-93` 原 DrawScope 块图元 1:1 对应。
     *
     * @param canvas 目标 Canvas
     * @param cx     摩擦圆中心 x（px）
     * @param cy     摩擦圆中心 y（px）
     * @param radius 外圈半径（px，= min(w,h)/2 - 2）
     * @param latG   横向 G（过弯）；调用方对 null 传 0
     * @param lonG   纵向 G（加速正 / 制动负）；调用方对 null 传 0
     * @param paints 颜色容器
     */
    fun drawGForceBall(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        latG: Double,
        lonG: Double,
        paints: GForcePaints,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 表盘底
        paint.style = Paint.Style.FILL
        paint.color = paints.dialColor
        canvas.drawCircle(cx, cy, radius, paint)

        // 同心刻度圈：0.5G / 1.0G / 1.5G（1.5G = 外圈边界）
        val ringFractions = listOf(0.5 / GaugeMath.GBALL_MAX_G, 1.0 / GaugeMath.GBALL_MAX_G, 1.0)
        paint.style = Paint.Style.STROKE
        ringFractions.forEachIndexed { idx, f ->
            val isOuter = idx == ringFractions.lastIndex
            paint.color = if (isOuter) paints.outerColor else paints.ringColor
            paint.strokeWidth = if (isOuter) 2f else 1f
            canvas.drawCircle(cx, cy, (radius * f).toFloat(), paint)
        }

        // 十字轴
        paint.color = paints.axisColor
        paint.strokeWidth = 1f
        canvas.drawLine(cx - radius, cy, cx + radius, cy, paint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, paint)

        // 动点：纯函数映射归一化偏移 → 像素
        val (dotX, dotY) = ballDotCenter(cx, cy, radius, latG, lonG)
        // 动点光晕 + 实心点
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(paints.dotColor, 0.3f)
        canvas.drawCircle(dotX, dotY, radius * 0.16f, paint)
        paint.color = paints.dotColor
        canvas.drawCircle(dotX, dotY, radius * 0.08f, paint)
    }

    /**
     * 画赛道小地图（轮廓 polyline 闭环 + 当前位置高亮点 + 可选起点标记）。
     * 与 `TrackMiniMap.kt:138-163` 原 DrawScope 块图元 1:1 对应。
     * `points` < 2 时（投影返回 null）不画任何内容（不崩，与回放端 `?: return@Canvas` 一致）。
     *
     * 场景区分：
     * - **overlay 播放页小地图**：[MiniMapPaints.strokeWidth] = [MINIMAP_STROKE_OVERLAY]（细线 2px），
     *   [MiniMapPaints.startMarkerColor] = 0（不画起点标记），另有 currentLat/Lon 驱动的当前位置点。
     * - **thumbnail 预览场景**：[MiniMapPaints.strokeWidth] = [MINIMAP_STROKE_THUMBNAIL]（粗线 5px），
     *   [MiniMapPaints.startMarkerColor] = Cyan ARGB（画实心圆起点标记），currentLat/Lon = null。
     *
     * @param canvas     目标 Canvas
     * @param width      画布宽（px）
     * @param height     画布高（px）
     * @param padding    四周留边（px，回放端 = 6dp.toPx()）
     * @param points     赛道轮廓
     * @param currentLat 当前帧纬度（null 不画当前位置点）
     * @param currentLon 当前帧经度
     * @param paints     颜色容器（含 strokeWidth + startMarkerColor 场景区分参数）
     */
    fun drawTrackMiniMap(
        canvas: Canvas,
        width: Float,
        height: Float,
        padding: Float,
        points: List<GeoPoint>,
        currentLat: Double?,
        currentLon: Double?,
        paints: MiniMapPaints,
    ) {
        val projected = TrackMiniMapProjection.project(
            points = points,
            currentLat = currentLat,
            currentLon = currentLon,
            canvasWidth = width,
            canvasHeight = height,
            padding = padding,
        ) ?: return

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 赛道轮廓 polyline（闭环：首尾相连）
        if (projected.polyline.size >= 2) {
            val path = Path().apply {
                moveTo(projected.polyline.first().x, projected.polyline.first().y)
                projected.polyline.drop(1).forEach { lineTo(it.x, it.y) }
                lineTo(projected.polyline.first().x, projected.polyline.first().y)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = paints.strokeWidth
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = paints.lineColor
            canvas.drawPath(path, paint)
        }

        // 当前位置高亮点（overlay 播放页场景）
        projected.current?.let { c ->
            paint.style = Paint.Style.FILL
            paint.color = paints.dotColor
            canvas.drawCircle(c.x, c.y, 5f, paint)
        }

        // 起点标记（thumbnail 场景）：startMarkerColor != 0 时画实心圆 + 轮廓环
        // 起点 = points.first() 投影后的 polyline[0]
        if (paints.startMarkerColor != 0 && projected.polyline.isNotEmpty()) {
            val startPt = projected.polyline.first()
            // 外圈轮廓环（深色，增对比）
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = android.graphics.Color.BLACK
            canvas.drawCircle(startPt.x, startPt.y, MINIMAP_START_MARKER_RADIUS, paint)
            // 实心填充（Cyan / 起点色）
            paint.style = Paint.Style.FILL
            paint.color = paints.startMarkerColor
            canvas.drawCircle(startPt.x, startPt.y, MINIMAP_START_MARKER_RADIUS, paint)
        }
    }

    /**
     * 画圈速计时面板（半透明背景 + "LAP N" + 圈速字符串 + delta 字符串）。
     * 对应回放端 `LapVideoPlaybackScreen.LapTimeCorner`（OverlayPanel 背景 + 三行 Text）。
     *
     * **导出端专用**（回放端 LapTimeCorner Round A 仍保留 Compose Text，零视觉变化）；
     * 本函数让 round B 导出无 Compose 时复刻同一视觉。圈速/delta 字符串走 Score 风格
     * （V2 约束：时间字符串 MUST NOT DSEG7），由 [LapTimePaints.scoreTypeface] 提供。
     *
     * @param canvas    目标 Canvas
     * @param x         面板左上角 x（px）
     * @param y         面板左上角 y（px）
     * @param lapNumber 当前圈号（null → "LAP --"）
     * @param elapsedMs 当前圈已用时（null/负 → "--:--.---"）
     * @param deltaMs   与 best 圈 delta（null → "--"）
     * @param labelTextSize  "LAP N" 字号（px）
     * @param scoreTextSize  圈速/delta 字号（px）
     * @param paints    颜色 + typeface 容器
     */
    fun drawLapTimePanel(
        canvas: Canvas,
        x: Float,
        y: Float,
        lapNumber: Int?,
        elapsedMs: Long?,
        deltaMs: Long?,
        labelTextSize: Float,
        scoreTextSize: Float,
        paints: LapTimePaints,
    ) {
        val labelText = if (lapNumber != null) "LAP $lapNumber" else "LAP --"
        val elapsedText = formatElapsed(elapsedMs)
        val deltaText = formatDelta(deltaMs)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = paints.labelColor
            textSize = labelTextSize
            typeface = paints.labelTypeface
            textAlign = Paint.Align.LEFT
        }
        val elapsedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = paints.elapsedColor
            textSize = scoreTextSize
            typeface = paints.scoreTypeface
            textAlign = Paint.Align.LEFT
        }
        val deltaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = paints.deltaColor
            textSize = scoreTextSize
            typeface = paints.scoreTypeface
            textAlign = Paint.Align.LEFT
        }

        val padH = labelTextSize           // 水平内边距（≈ 12dp 量级，按字号比例）
        val padV = labelTextSize * 0.67f   // 垂直内边距（≈ 8dp 量级）
        val lineGap = labelTextSize * 0.17f

        // 三行高度
        val labelH = textHeight(labelPaint)
        val elapsedH = textHeight(elapsedPaint)
        val deltaH = textHeight(deltaPaint)
        // 面板宽：取三行最宽 + 左右内边距
        val maxTextW = maxOf(
            labelPaint.measureText(labelText),
            elapsedPaint.measureText(elapsedText),
            deltaPaint.measureText(deltaText),
        )
        val panelW = maxTextW + padH * 2
        val panelH = labelH + elapsedH + deltaH + lineGap * 2 + padV * 2

        // 半透明背景面板
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = paints.panelColor
        }
        canvas.drawRect(RectF(x, y, x + panelW, y + panelH), bgPaint)

        // 三行文字（drawText 的 y 是 baseline）
        val textX = x + padH
        var baseline = y + padV - labelPaint.fontMetrics.ascent
        canvas.drawText(labelText, textX, baseline, labelPaint)
        baseline += labelPaint.fontMetrics.descent + lineGap - elapsedPaint.fontMetrics.ascent
        canvas.drawText(elapsedText, textX, baseline, elapsedPaint)
        baseline += elapsedPaint.fontMetrics.descent + lineGap - deltaPaint.fontMetrics.ascent
        canvas.drawText(deltaText, textX, baseline, deltaPaint)
    }

    // ── internal 纯几何（最后一跳：角度/偏移 → 像素点），供 JVM 单测验证 ──────────────

    /**
     * 指针/尾翼端点：从中心 (cx,cy) 沿 [angleDeg]（Compose 角度系，0°=3点钟、顺时针为正）
     * 延伸 [len] 像素的点。`len` 为负即反向（尾翼）。
     */
    internal fun needleTip(cx: Float, cy: Float, angleDeg: Double, len: Float): Pair<Float, Float> {
        val rad = Math.toRadians(angleDeg)
        return (cx + cos(rad).toFloat() * len) to (cy + sin(rad).toFloat() * len)
    }

    /**
     * 刻度线两端点：沿 [angleDeg] 方向，外端在半径 [outerR]、内端在 [outerR] - [len]。
     * 返回 (inner, outer)，各为 (x, y)。
     */
    internal fun tickEndpoints(
        cx: Float,
        cy: Float,
        angleDeg: Double,
        outerR: Float,
        len: Float,
    ): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val rad = Math.toRadians(angleDeg)
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val outer = (cx + cosA * outerR) to (cy + sinA * outerR)
        val inner = (cx + cosA * (outerR - len)) to (cy + sinA * (outerR - len))
        return inner to outer
    }

    /**
     * G 球动点像素中心：用 [GaugeMath.gForceToBallOffset] 归一化偏移 × 半径 + 中心。
     */
    internal fun ballDotCenter(
        cx: Float,
        cy: Float,
        radius: Float,
        latG: Double,
        lonG: Double,
    ): Pair<Float, Float> {
        val (nx, ny) = GaugeMath.gForceToBallOffset(latG, lonG)
        return (cx + nx.toFloat() * radius) to (cy + ny.toFloat() * radius)
    }

    /** 合成 G 大小（动点颜色判定用，与回放端 sqrt(lat²+lon²) 一致）。 */
    internal fun gMagnitude(latG: Double, lonG: Double): Double = sqrt(latG * latG + lonG * lonG)

    /** ARGB int 乘以 alpha 比例（保 RGB，等价 Compose `Color.copy(alpha=…)`）。 */
    internal fun withAlpha(argb: Int, alpha: Float): Int {
        val a = ((argb ushr 24) * alpha + 0.5f).toInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0x00FFFFFF)
    }

    // ── 文字格式化（与 LapVideoPlaybackScreen.formatElapsed/formatDelta 1:1 一致） ──────

    /** 圈速 elapsed 格式化 m:ss.mmm；null/负 → "--:--.---"。 */
    internal fun formatElapsed(ms: Long?): String {
        if (ms == null || ms < 0) return "--:--.---"
        val totalSec = ms / 1000
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        val millis = ms % 1000
        return "%d:%02d.%03d".format(minutes, seconds, millis)
    }

    /** delta 格式化 ±x.xx s；null → "--"。 */
    internal fun formatDelta(ms: Long?): String {
        if (ms == null) return "--"
        val sign = if (ms >= 0) "+" else "-"
        return "%s%.2f".format(sign, kotlin.math.abs(ms) / 1000.0)
    }

    private fun textHeight(paint: Paint): Float {
        val fm = paint.fontMetrics
        return fm.descent - fm.ascent
    }
}
