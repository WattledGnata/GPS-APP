// @IgnoreFormatCheck
// 理由：JUnit4 测试类命名 snake_case 承载 Gherkin 语义；本文件随
//       round wire-real-data-to-records-and-laps-tabs §3.2 新建。
package com.blazepush.feature.test.ui.tracktech.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class MetricFormatterTest {

    @Test
    fun formatLapMs_handlesTypicalAndEdgeCases() {
        assertEquals("1:32.457", formatLapMs(92457))
        assertEquals("0:00.000", formatLapMs(0))
        assertEquals("1:00.000", formatLapMs(60000))
        assertEquals("60:00.000", formatLapMs(3600000))
    }

    @Test
    fun formatLapMs_padsSecondsAndMillisCorrectly() {
        assertEquals("0:01.005", formatLapMs(1005))
        assertEquals("0:09.999", formatLapMs(9999))
        assertEquals("2:30.500", formatLapMs(150500))
    }

    @Test
    fun formatDate_outputsExpectedEnglishFormat() {
        // 2024-05-18 10:35 UTC → epochMs = 1716028500000
        // 但日期受时区影响；用 fixed UTC 时间 + 默认时区可能漂；改用 Calendar 构造确定性 ms
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply {
            clear()
            set(2024, Calendar.MAY, 18, 10, 35)
        }
        assertEquals("May 18, 2024", formatDate(cal.timeInMillis))
    }

    @Test
    fun formatRunTimestamp_today_returnsTodayPrefix() {
        val now = makeTime(2024, Calendar.MAY, 18, 14, 35)
        val epochMs = makeTime(2024, Calendar.MAY, 18, 13, 35)
        assertEquals("Today, 13:35", formatRunTimestamp(epochMs, now))
    }

    @Test
    fun formatRunTimestamp_yesterday_returnsYesterdayPrefix() {
        val now = makeTime(2024, Calendar.MAY, 18, 14, 35)
        val epochMs = makeTime(2024, Calendar.MAY, 17, 9, 12)
        assertEquals("Yesterday, 09:12", formatRunTimestamp(epochMs, now))
    }

    @Test
    fun formatRunTimestamp_olderThanWeek_fallsBackToAbsoluteDate() {
        val now = makeTime(2024, Calendar.MAY, 18, 14, 35)
        val epochMs = makeTime(2024, Calendar.MAY, 1, 9, 0)  // 17 天前
        assertEquals("May 1, 2024", formatRunTimestamp(epochMs, now))
    }

    private fun makeTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute)
        }.timeInMillis
}
