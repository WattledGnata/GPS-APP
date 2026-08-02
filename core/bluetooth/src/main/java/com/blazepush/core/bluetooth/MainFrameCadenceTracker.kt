package com.blazepush.core.bluetooth

import com.blazepush.core.domain.model.GPS_MAIN_SILENCE_MAX_TIMEOUT_MS
import java.util.ArrayDeque

/**
 * 根据最近 GPS Main 帧的本地单调到达间隔估算设备节拍。
 * 中位数抵抗单次调度抖动/恢复 gap；超时表示连续缺失约 10 个预期帧，并限制在 0.4~1 秒。
 */
internal class MainFrameCadenceTracker(
    private val minimumTimeoutMs: Long = 400L,
    private val maximumTimeoutMs: Long = GPS_MAIN_SILENCE_MAX_TIMEOUT_MS,
    private val missedFrameBudget: Int = 10,
    private val sampleWindowSize: Int = 9,
    private val minimumSamples: Int = 3,
) {
    private val recentIntervalsMs = ArrayDeque<Long>()
    private var lastFrameAtElapsedRealtimeMs: Long? = null

    @Synchronized
    fun onMainFrame(receivedAtElapsedRealtimeMs: Long) {
        val previous = lastFrameAtElapsedRealtimeMs
        if (previous != null) {
            val interval = receivedAtElapsedRealtimeMs - previous
            if (interval in 1..maximumTimeoutMs) {
                if (recentIntervalsMs.size == sampleWindowSize) {
                    recentIntervalsMs.removeFirst()
                }
                recentIntervalsMs.addLast(interval)
            }
        }
        lastFrameAtElapsedRealtimeMs = receivedAtElapsedRealtimeMs
    }

    @Synchronized
    fun currentSilenceTimeoutMs(): Long {
        if (recentIntervalsMs.size < minimumSamples) return maximumTimeoutMs
        val medianIntervalMs = recentIntervalsMs.sorted()[recentIntervalsMs.size / 2]
        return (medianIntervalMs * missedFrameBudget).coerceIn(
            minimumValue = minimumTimeoutMs,
            maximumValue = maximumTimeoutMs,
        )
    }

    @Synchronized
    fun reset() {
        recentIntervalsMs.clear()
        lastFrameAtElapsedRealtimeMs = null
    }
}
