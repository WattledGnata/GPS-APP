// @IgnoreFormatCheck
package com.blazepush.feature.test.diagnostic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * spec『诊断上传暗门入口』：3 秒内 7 次触发 / 6 次不触发 / 超时清零（含 2 反例）。
 */
class VersionTapCounterTest {

    @Test
    fun sevenTapsWithinWindow_triggersOnSeventh() {
        val c = VersionTapCounter()
        var t = 1_000L
        repeat(6) {
            assertFalse("前 6 次不应触发", c.tap(t)); t += 100
        }
        assertTrue("第 7 次触发", c.tap(t))
    }

    @Test
    fun sixTapsOnly_neverTriggers() {
        val c = VersionTapCounter()
        var t = 1_000L
        var fired = false
        repeat(6) { fired = fired || c.tap(t); t += 100 }
        assertFalse("仅 6 次 MUST NOT 触发", fired)
    }

    @Test
    fun gapBeyondWindow_resetsCount() {
        val c = VersionTapCounter(windowMs = 3_000L)
        var t = 1_000L
        repeat(4) { c.tap(t); t += 100 } // 先 4 次
        t += 4_000 // 间隔 > 3s，计数应清零
        var fired = false
        repeat(3) { fired = fired || c.tap(t); t += 100 } // 再 3 次不应与前 4 次累加成 7
        assertFalse("超时清零后 4+3 MUST NOT 触发", fired)
    }
}
