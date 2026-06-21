// @IgnoreFormatCheck
package com.blazepush.feature.test.diagnostic

/**
 * 暗门连点计数器（add-diagnostic-log-upload，design Decision 1）。
 *
 * 在 [windowMs] 滑动窗口内累计点击达 [threshold] 次即触发（并重置计数）；
 * 相邻两次点击间隔超过 [windowMs] 则计数清零。纯逻辑，无 Android 依赖，可单测。
 */
class VersionTapCounter(
    private val windowMs: Long = 3_000L,
    private val threshold: Int = 7,
) {
    private var count = 0
    private var lastTapMs = Long.MIN_VALUE

    /**
     * 记一次点击。
     * @param nowMs 当前时刻（毫秒）
     * @return true 表示本次点击使累计达到阈值（计数已重置）；否则 false
     */
    fun tap(nowMs: Long): Boolean {
        if (lastTapMs != Long.MIN_VALUE && nowMs - lastTapMs > windowMs) {
            count = 0 // 超时清零
        }
        lastTapMs = nowMs
        count++
        if (count >= threshold) {
            count = 0
            return true
        }
        return false
    }
}
