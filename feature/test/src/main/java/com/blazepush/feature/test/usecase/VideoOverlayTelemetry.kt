// @IgnoreFormatCheck
package com.blazepush.feature.test.usecase

import com.blazepush.core.domain.model.LapTelemetrySample
import kotlin.math.abs

/**
 * 视频实时叠加 overlay 的遥测离线计算 use-case（纯函数，可单测）。
 *
 * round video-overlay-realtime-playback（Phase 2 第六刀）。binary 17-byte sample 无加速度字段
 * （accelerationG 全 null），G 值 MUST 由相邻样本 speed/bearing 离线重算（design Decision 6）：
 * - 纵向 G（加减速）= (ΔspeedKmh/3.6) / Δt(秒) / 9.8（公式同 GpsDataFilter.calculateAcceleration 再 /9.8）
 * - 横向 G（过弯）= (speedMps × Δbearing_rad / Δt) / 9.8（向心加速度 a = v·ω）
 * 两者均做轻量滑动平均平滑（窗口 smoothingWindow），避免 GPS 噪声让 overlay 数字乱跳。
 *
 * 圈窗口判定（design Decision 7）：frameWallClock 落哪一圈窗口由 [resolveCurrentLap] 判定；
 * 落两圈之间（pit/出入场）返回 null → overlay 显示降级占位（"--"）。
 *
 * @author CC
 * @description offline overlay telemetry computation for video playback HUD
 * @date 2026-05-31
 */
object VideoOverlayTelemetry {

    private const val GRAVITY = 9.8

    /**
     * 单帧 overlay 数据（与样本一一对应，进屏一次性预算好存数组，轮询时只查表不算）。
     *
     * @property absoluteTsMs 该样本的绝对 wallClock（与 frameWallClock 同时钟域，供二分查最近邻）
     * @property speedKmh     瞬时速度（km/h）
     * @property latG         横向 G（过弯，已平滑）
     * @property lonG         纵向 G（加减速，已平滑）
     * @property lat          当前纬度（小地图当前点 + delta 投影用）
     * @property lon          当前经度
     */
    data class OverlayFrame(
        val absoluteTsMs: Long,
        val speedKmh: Double,
        val latG: Double,
        val lonG: Double,
        val lat: Double,
        val lon: Double,
    )

    /**
     * 一圈的时间窗口（用 crossing wallClock 判定 frameWallClock 落哪圈）。
     *
     * @property lapNumber          圈号（1-based，与详情屏 UiLapRecord.lapNumber 同源）
     * @property lapStartWallClock  开圈 crossing 真壁钟（含）
     * @property lapEndWallClock    收圈 crossing 真壁钟（不含 / 边界含均可，落区间内即命中）
     */
    data class LapWindow(
        val lapNumber: Int,
        val lapStartWallClock: Long,
        val lapEndWallClock: Long,
    )

    /**
     * frameWallClock 落圈的判定结果。
     *
     * @property lapNumber           落到的圈号
     * @property currentLapElapsedMs frameWallClock - lapStartWallClock（当前圈已用毫秒）
     */
    data class LapResolution(
        val lapNumber: Int,
        val currentLapElapsedMs: Long,
    )

    /**
     * 离线预算每样本的 overlay 数据（纵向/横向 G + 滑动平均平滑）。
     *
     * 关键约束（design Decision 6）：
     * - 输入样本 accelerationG 恒 null（binary 缺字段）→ MUST 由 speed/bearing 重算非 null G。
     * - 首样本（i==0）无前序帧 → G = 0。
     * - Δt <= 0（同一时刻或时钟回绕）→ 该帧瞬时 G = 0（不强算）。
     * - bearing 任一端 null（GPS 静止哨兵）→ 横向 G = 0。
     * - bearing 差分跨 ±180° 边界（如 359°→1°）→ 归一化到 [-180, 180]，避免假性大角速度。
     *
     * @param lapTelemetrySamples 整 session 样本（升序 absoluteTsMs）
     * @param smoothingWindow     滑动平均窗口大小（默认 5；<=1 时不平滑）
     * @return 与输入一一对应的 OverlayFrame 列表（空输入返回空列表）
     */
    fun buildFrames(
        lapTelemetrySamples: List<LapTelemetrySample>,
        smoothingWindow: Int = 5,
    ): List<OverlayFrame> {
        if (lapTelemetrySamples.isEmpty()) return emptyList()

        val n = lapTelemetrySamples.size
        val rawLonG = DoubleArray(n)
        val rawLatG = DoubleArray(n)

        for (i in 0 until n) {
            if (i == 0) {
                rawLonG[i] = 0.0
                rawLatG[i] = 0.0
                continue
            }
            val cur = lapTelemetrySamples[i]
            val prev = lapTelemetrySamples[i - 1]
            val dtSec = (cur.absoluteTsMs - prev.absoluteTsMs) / 1000.0
            if (dtSec <= 0.0) {
                rawLonG[i] = 0.0
                rawLatG[i] = 0.0
                continue
            }
            // 纵向 G：速度差 (m/s) = Δv(km/h) / 3.6；a = dv/dt (m/s²)；G = a / 9.8
            val dvMps = (cur.speedKmh - prev.speedKmh) / 3.6
            rawLonG[i] = dvMps / dtSec / GRAVITY

            // 横向 G：向心加速度 a = v·ω，ω = Δbearing_rad / dt；G = a / 9.8
            val curBearing = cur.bearingDeg
            val prevBearing = prev.bearingDeg
            rawLatG[i] = if (curBearing != null && prevBearing != null) {
                val dBearingDeg = normalizeBearingDelta(curBearing - prevBearing)
                val omegaRadPerSec = Math.toRadians(dBearingDeg) / dtSec
                val speedMps = cur.speedKmh / 3.6
                (speedMps * omegaRadPerSec) / GRAVITY
            } else {
                0.0
            }
        }

        val smoothLonG = movingAverage(rawLonG, smoothingWindow)
        val smoothLatG = movingAverage(rawLatG, smoothingWindow)

        return (0 until n).map { i ->
            val s = lapTelemetrySamples[i]
            OverlayFrame(
                absoluteTsMs = s.absoluteTsMs,
                speedKmh = s.speedKmh,
                latG = smoothLatG[i],
                lonG = smoothLonG[i],
                lat = s.lat,
                lon = s.lon,
            )
        }
    }

    /**
     * frameWallClock 落哪一圈窗口。落某圈 [start, end] 内返回 lapNumber + elapsed；
     * 落两圈之间（pit/出入场）或无任何窗口命中返回 null（overlay 显示 "--"，不强算）。
     *
     * 多窗口重叠时取第一个命中（lapWindows 应按 lapStartWallClock 升序、互不重叠）。
     *
     * @param frameWallClock 当前帧绝对 wallClock
     * @param lapWindows     各圈窗口（升序）
     * @return 命中返回 LapResolution，未命中返回 null
     */
    fun resolveCurrentLap(
        frameWallClock: Long,
        lapWindows: List<LapWindow>,
    ): LapResolution? {
        for (w in lapWindows) {
            if (frameWallClock in w.lapStartWallClock..w.lapEndWallClock) {
                return LapResolution(
                    lapNumber = w.lapNumber,
                    currentLapElapsedMs = frameWallClock - w.lapStartWallClock,
                )
            }
        }
        return null
    }

    /**
     * bearing 差分归一化到 [-180, 180]，处理跨 ±180° 边界（359°→1° 的真实变化是 +2° 而非 -358°）。
     */
    private fun normalizeBearingDelta(deltaDeg: Double): Double {
        var d = deltaDeg % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    /**
     * 居中滑动平均（窗口 [i-half, i+half]，边界截断）。窗口 <=1 直接返回拷贝。
     */
    private fun movingAverage(values: DoubleArray, window: Int): DoubleArray {
        val n = values.size
        if (n == 0) return DoubleArray(0)
        if (window <= 1) return values.copyOf()
        val half = window / 2
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val lo = (i - half).coerceAtLeast(0)
            val hi = (i + half).coerceAtMost(n - 1)
            var sum = 0.0
            for (j in lo..hi) sum += values[j]
            out[i] = sum / (hi - lo + 1)
        }
        return out
    }

    /**
     * 用 LapTelemetry 样本（best 圈）构建 [ReferenceLapIndex]，供 [projectDelta] 投影算 delta。
     *
     * 与 [buildReferenceLapIndex]（吃 LapRecord）口径一致但消费 LapTelemetrySample：
     * - refLat/refLon = 首样本经纬度（投影原点）
     * - elapsedMs[i] = sample.absoluteTsMs - lapStartWallClock（首点 ≥ 0 反映滞后）
     * - xs/ys 用 LocalPlaneProjection 投影到本地米坐标系
     * 样本 < 2 返回 null（无法 segment 投影）。
     *
     * @param bestLapSamples best 圈样本（升序 absoluteTsMs）
     * @param lapStartWallClock best 圈开圈 crossing wallClock（elapsed 锚点）
     * @param lapDurationMs best 圈耗时
     */
    internal fun buildReferenceFromSamples(
        bestLapSamples: List<LapTelemetrySample>,
        lapStartWallClock: Long,
        lapDurationMs: Long,
    ): ReferenceLapIndex? {
        if (bestLapSamples.size < 2) return null
        val first = bestLapSamples.first()
        val refLat = first.lat
        val refLon = first.lon
        val n = bestLapSamples.size
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val cumDistanceM = FloatArray(n)
        val elapsedMs = LongArray(n)
        for (i in 0 until n) {
            val s = bestLapSamples[i]
            val (x, y) = LocalPlaneProjection.toMeters(refLat, refLon, s.lat, s.lon)
            xs[i] = x
            ys[i] = y
            elapsedMs[i] = s.absoluteTsMs - lapStartWallClock
            cumDistanceM[i] = if (i == 0) {
                0f
            } else {
                val dx = xs[i] - xs[i - 1]
                val dy = ys[i] - ys[i - 1]
                cumDistanceM[i - 1] + kotlin.math.sqrt(dx * dx + dy * dy)
            }
        }
        return ReferenceLapIndex(
            refLat = refLat,
            refLon = refLon,
            xs = xs,
            ys = ys,
            cumDistanceM = cumDistanceM,
            elapsedMs = elapsedMs,
            lapStartTsMs = lapStartWallClock,
            lapDurationMs = lapDurationMs,
        )
    }

    /**
     * 当前帧 lat/lon 投影到 [reference] best 圈坐标系算实时 delta（复用 [projectDelta]）。
     * 返回 deltaMs（当前圈比 best 快=负 / 慢=正）；投影失效（距离超阈值 / reference 不足 2 点）返回 null。
     *
     * @param reference best 圈索引
     * @param currentLapElapsedMs 当前圈已用毫秒（[resolveCurrentLap] 算出）
     * @param currentLat 当前帧纬度
     * @param currentLon 当前帧经度
     */
    internal fun computeDeltaMs(
        reference: ReferenceLapIndex,
        currentLapElapsedMs: Long,
        currentLat: Double,
        currentLon: Double,
    ): Long? {
        val (x, y) = reference.toLocalMeters(currentLat, currentLon)
        return projectDelta(reference, currentLapElapsedMs, x, y)?.deltaMs
    }
}
