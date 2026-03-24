package com.blazepush.feature.test.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 语音播报工具
 * 封装 Android 原生 TTS，用于播报测试成绩
 */
class VoiceAnnouncer(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var isEnabled = true

    /**
     * 初始化 TTS
     */
    fun init(onReady: (() -> Unit)? = null) {
        if (isInitialized) {
            onReady?.invoke()
            return
        }

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                }
                isInitialized = true
                onReady?.invoke()
            }
        }
    }

    /**
     * 是否启用语音播报
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    /**
     * 播报零百加速成绩
     * @param seconds 成绩（秒），如 6.8
     */
    fun announceAccelerationResult(seconds: Double) {
        if (!isEnabled) return
        val text = formatTimeForSpeech(seconds, "百公里加速")
        speak(text, "acceleration_result")
    }

    /**
     * 播报刹车成绩
     * @param seconds 成绩（秒），如 2.8
     */
    fun announceBrakingResult(seconds: Double) {
        if (!isEnabled) return
        val text = formatTimeForSpeech(seconds, "百公里刹车")
        speak(text, "braking_result")
    }

    /**
     * 播报圈速
     * @param lapNumber 圈号
     * @param timeMillis 圈速时间（毫秒）
     */
    fun announceLapTime(lapNumber: Int, timeMillis: Long) {
        if (!isEnabled) return
        val text = formatLapTimeForSpeech(lapNumber, timeMillis)
        speak(text, "lap_$lapNumber")
    }

    /**
     * 格式化时间用于语音播报
     * 6.8 → "百公里加速六点八秒"
     */
    private fun formatTimeForSpeech(seconds: Double, prefix: String): String {
        val numStr = formatNumberForSpeech(seconds)
        val suffix = "秒"
        return prefix + numStr + suffix
    }

    /**
     * 格式化数字用于语音播报
     * 6.8 → "六点八" / 12.34 → "十二点三四"
     * 保留2位小数
     */
    private fun formatNumberForSpeech(value: Double): String {
        val scaled = (value * 100).toLong() / 100.0
        val str = "%.2f".format(scaled)

        val sb = StringBuilder()
        for (c in str) {
            when (c) {
                '0' -> sb.append("零")
                '1' -> sb.append("一")
                '2' -> sb.append("二")
                '3' -> sb.append("三")
                '4' -> sb.append("四")
                '5' -> sb.append("五")
                '6' -> sb.append("六")
                '7' -> sb.append("七")
                '8' -> sb.append("八")
                '9' -> sb.append("九")
                '.' -> sb.append("点")
            }
        }
        return sb.toString()
    }

    /**
     * 格式化圈速用于语音播报
     * 83234ms → "第一圈，八分三十秒二百三十四毫秒"
     */
    private fun formatLapTimeForSpeech(lapNumber: Int, timeMillis: Long): String {
        val lapWord = formatLapNumber(lapNumber)
        val minutes = timeMillis / 60000
        val seconds = (timeMillis % 60000) / 1000
        val millis = timeMillis % 1000

        val numToSpeech = { v: Double -> formatNumberForSpeech(v) }

        return when {
            minutes > 0 -> {
                lapWord + "，" + numToSpeech(minutes.toDouble()) + "分" +
                    numToSpeech(seconds.toDouble()) + "秒" +
                    numToSpeech(millis.toDouble()) + "毫秒"
            }
            else -> {
                lapWord + "，" + numToSpeech(seconds.toDouble()) + "秒" +
                    numToSpeech(millis.toDouble()) + "毫秒"
            }
        }
    }

    /**
     * 格式化圈号
     */
    private fun formatLapNumber(n: Int): String {
        return when (n) {
            1 -> "第一圈"
            2 -> "第二圈"
            3 -> "第三圈"
            4 -> "第四圈"
            5 -> "第五圈"
            6 -> "第六圈"
            7 -> "第七圈"
            8 -> "第八圈"
            9 -> "第九圈"
            else -> "第" + formatNumberForSpeech(n.toDouble()) + "圈"
        }
    }

    private fun speak(text: String, utteranceId: String) {
        if (!isInitialized) {
            init {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    /**
     * 释放资源
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}
