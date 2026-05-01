// @IgnoreFormatCheck
// 理由：纯函数工具集，class-comment 等规范由 D round 统一处理；本文件随
//       round wire-real-data-to-records-and-laps-tabs §3.1 新建。
package com.blazepush.feature.test.ui.tracktech.format

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 把 lap 毫秒数格式化为 `M:SS.mmm` 字符串。
 *
 * 例：92457 → "1:32.457"，0 → "0:00.000"，60000 → "1:00.000"，3600000 → "60:00.000"
 *
 * 不强制小时位分割（即使超 60 分仍输出 `MM:SS.mmm`），因为圈速场景单圈极少超 60 分。
 */
fun formatLapMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val millis = ms % 1000
    return "%d:%02d.%03d".format(minutes, seconds, millis)
}

/**
 * 把 epoch ms 格式化为 `MMM d, yyyy` 字符串。
 *
 * 例：epochMs for 2024-05-18 → "May 18, 2024"
 *
 * 用 [Locale.ENGLISH] 默认月份缩写为英文（"May" / "Apr"）。
 */
fun formatDate(epochMs: Long, locale: Locale = Locale.ENGLISH): String =
    SimpleDateFormat("MMM d, yyyy", locale).format(Date(epochMs))

/**
 * 把 epoch ms 按距今时长分级格式化：
 * - 同日 → "Today, HH:mm"
 * - 昨天 → "Yesterday, HH:mm"
 * - 7 天内（不含同日 / 昨天） → "EEE, HH:mm"（如 "Mon, 14:35"）
 * - 超 7 天 → "MMM d, yyyy"（同 [formatDate]）
 *
 * @param now 注入参考时间点便于单元测试（默认 [System.currentTimeMillis]）
 */
fun formatRunTimestamp(
    epochMs: Long,
    now: Long = System.currentTimeMillis(),
    locale: Locale = Locale.ENGLISH,
): String {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
    val nowCal = Calendar.getInstance().apply { timeInMillis = now }
    val sameYear = cal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
    val sameDay = sameYear && cal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
    val yesterday = sameYear && cal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR) - 1
    val daysAgo = ((now - epochMs) / (24L * 60 * 60 * 1000)).toInt()

    val timeFmt = SimpleDateFormat("HH:mm", locale).format(Date(epochMs))
    return when {
        sameDay -> "Today, $timeFmt"
        yesterday -> "Yesterday, $timeFmt"
        daysAgo in 0..6 -> SimpleDateFormat("EEE, HH:mm", locale).format(Date(epochMs))
        else -> formatDate(epochMs, locale)
    }
}
