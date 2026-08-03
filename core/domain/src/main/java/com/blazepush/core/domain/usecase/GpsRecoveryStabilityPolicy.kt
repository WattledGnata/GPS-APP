package com.blazepush.core.domain.usecase

import java.util.ArrayDeque
import kotlin.math.ceil

/**
 * 恢复窗口的可校准参数。默认至少稳定 1 秒；25Hz 约需 26 帧、5Hz 约需 6 帧。
 * 1 秒覆盖短暂 reacquisition 抖动，同时保留至少 3 帧的绝对下限。未来硬件校准只改参数。
 */
data class GpsRecoveryStabilityPolicy(
    val minimumReliableFrames: Int = 3,
    val minimumStableDurationMs: Long = 1_000L,
    val defaultCadenceMs: Long = 100L,
    val minimumCadenceMs: Long = 20L,
    val maximumCadenceMs: Long = 1_000L,
    val cadenceSampleWindowSize: Int = 9,
) {
    init {
        require(minimumReliableFrames > 0)
        require(minimumStableDurationMs >= 0L)
        require(defaultCadenceMs in minimumCadenceMs..maximumCadenceMs)
        require(cadenceSampleWindowSize > 0)
    }

    fun requiredFrames(cadenceMs: Long): Int {
        val boundedCadence = cadenceMs.coerceIn(minimumCadenceMs, maximumCadenceMs)
        val durationFrames = ceil(minimumStableDurationMs.toDouble() / boundedCadence).toInt() + 1
        return maxOf(minimumReliableFrames, durationFrames)
    }
}

data class GpsRecoveryStability(
    val consecutiveReliableFrames: Int = 0,
    val stableDurationMs: Long = 0L,
    val observedCadenceMs: Long,
    val requiredReliableFrames: Int,
    val requiredStableDurationMs: Long,
    val isStable: Boolean = false,
)

/** Pure Kotlin recovery window. Generation, gap, or unreliable/no-fix input resets the window. */
class GpsRecoveryStabilityTracker(
    private val policy: GpsRecoveryStabilityPolicy = GpsRecoveryStabilityPolicy(),
) {
    private val reliableIntervalsMs = ArrayDeque<Long>()
    private var generation: Long? = null
    private var windowStartedAtMs: Long? = null
    private var lastReliableFrameAtMs: Long? = null
    private var consecutiveReliableFrames = 0

    fun onMainFrame(
        connectionGeneration: Long,
        receivedAtElapsedRealtimeMs: Long,
        isReliable: Boolean,
        maximumGapMs: Long,
    ): GpsRecoveryStability {
        if (generation != connectionGeneration) {
            reset(connectionGeneration)
        }
        val previousAt = lastReliableFrameAtMs
        val hasGap = previousAt != null &&
            receivedAtElapsedRealtimeMs - previousAt !in 0..<maximumGapMs
        if (!isReliable || hasGap) {
            reset(connectionGeneration)
            if (!isReliable) return emptySnapshot()
        }

        val lastAt = lastReliableFrameAtMs
        if (lastAt != null) {
            val interval = receivedAtElapsedRealtimeMs - lastAt
            if (interval > 0L) {
                if (reliableIntervalsMs.size == policy.cadenceSampleWindowSize) {
                    reliableIntervalsMs.removeFirst()
                }
                reliableIntervalsMs.addLast(interval)
            }
        }
        if (windowStartedAtMs == null) windowStartedAtMs = receivedAtElapsedRealtimeMs
        lastReliableFrameAtMs = receivedAtElapsedRealtimeMs
        consecutiveReliableFrames++

        val cadenceMs = observedCadenceMs()
        val requiredFrames = policy.requiredFrames(cadenceMs)
        val stableDurationMs = receivedAtElapsedRealtimeMs - checkNotNull(windowStartedAtMs)
        return GpsRecoveryStability(
            consecutiveReliableFrames = consecutiveReliableFrames,
            stableDurationMs = stableDurationMs,
            observedCadenceMs = cadenceMs,
            requiredReliableFrames = requiredFrames,
            requiredStableDurationMs = policy.minimumStableDurationMs,
            isStable = consecutiveReliableFrames >= requiredFrames &&
                stableDurationMs >= policy.minimumStableDurationMs,
        )
    }

    fun reset(connectionGeneration: Long? = generation) {
        generation = connectionGeneration
        reliableIntervalsMs.clear()
        windowStartedAtMs = null
        lastReliableFrameAtMs = null
        consecutiveReliableFrames = 0
    }

    fun emptySnapshot(): GpsRecoveryStability {
        val cadenceMs = policy.defaultCadenceMs
        return GpsRecoveryStability(
            observedCadenceMs = cadenceMs,
            requiredReliableFrames = policy.requiredFrames(cadenceMs),
            requiredStableDurationMs = policy.minimumStableDurationMs,
        )
    }

    private fun observedCadenceMs(): Long = if (reliableIntervalsMs.isEmpty()) {
        policy.defaultCadenceMs
    } else {
        reliableIntervalsMs.sorted()[reliableIntervalsMs.size / 2]
            .coerceIn(policy.minimumCadenceMs, policy.maximumCadenceMs)
    }
}
