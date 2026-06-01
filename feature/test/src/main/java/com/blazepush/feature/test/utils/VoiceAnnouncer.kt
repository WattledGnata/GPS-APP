// @IgnoreFormatCheck
package com.blazepush.feature.test.utils

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.blazepush.feature.test.FileLogger
import java.util.Locale

/**
 * 语音播报工具
 * 封装 Android 原生 TTS，用于播报测试成绩。
 *
 * 播报风格（2026-06-01 调整为简洁版）：先"叮"一声提示音，再直接报数：
 * - 加速 / 刹车：`叮` + `X.XX秒`（例：6.8s → 叮 + "六点八零秒"）
 * - 圈速：`叮` + `第N圈，X分XX秒.X`（例：83234ms → 叮 + "第一圈，一分二十三秒二"）
 *
 * 数字念法：整数部分按自然中文（12 → "十二"、23 → "二十三"），小数部分逐位（.34 → "三四"）。
 */
class VoiceAnnouncer(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var isEnabled = true

    /** 提示音生成器；个别设备构造会抛 RuntimeException，失败则降级为"不响铃直接说"。 */
    private val toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
    } catch (e: RuntimeException) {
        null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

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
     * 播报零百加速成绩：叮 + "X.XX秒"
     * @param seconds 成绩（秒），如 6.8 → "六点八零秒"
     */
    fun announceAccelerationResult(seconds: Double) {
        if (!isEnabled) return
        dingThenSpeak(formatSecondsTwoDecimals(seconds), "acceleration_result")
    }

    /**
     * 播报刹车成绩：叮 + "X.XX秒"
     * @param seconds 成绩（秒），如 2.83 → "二点八三秒"
     */
    fun announceBrakingResult(seconds: Double) {
        if (!isEnabled) return
        dingThenSpeak(formatSecondsTwoDecimals(seconds), "braking_result")
    }

    /**
     * 播报圈速：叮 + "第N圈，X分XX秒.X"
     * @param lapNumber 圈号
     * @param timeMillis 圈速时间（毫秒）
     */
    fun announceLapTime(lapNumber: Int, timeMillis: Long) {
        if (!isEnabled) return
        dingThenSpeak(formatLapTimeForSpeech(lapNumber, timeMillis), "lap_$lapNumber")
    }

    /**
     * 成绩秒数 → "X.XX秒"，整数自然中文 + 两位小数逐位。
     * 6.8 → "六点八零秒" / 12.34 → "十二点三四秒" / 0.95 → "零点九五秒"
     */
    private fun formatSecondsTwoDecimals(value: Double): String {
        val str = "%.2f".format(value)
        val dot = str.indexOf('.')
        val intPart = str.substring(0, dot).toInt()
        val decPart = str.substring(dot + 1)
        val decSpeech = decPart.map { CHINESE_DIGITS[it - '0'] }.joinToString("")
        return chineseNumber(intPart) + "点" + decSpeech + "秒"
    }

    /**
     * 圈速毫秒 → "第N圈，X分XX秒.X"（赛车惯例：分、秒自然中文，末尾十分位单数字）。
     * 83234ms → "第一圈，一分二十三秒二"；45678ms → "第一圈，四十五秒六"（不足 1 分省略"分"）。
     */
    private fun formatLapTimeForSpeech(lapNumber: Int, timeMillis: Long): String {
        val safe = if (timeMillis < 0) 0L else timeMillis
        val minutes = (safe / 60000).toInt()
        val seconds = ((safe % 60000) / 1000).toInt()
        val tenth = ((safe % 1000) / 100).toInt() // 十分位（截断，不进位）

        val body = if (minutes > 0) {
            chineseNumber(minutes) + "分" + chineseNumber(seconds) + "秒" + CHINESE_DIGITS[tenth]
        } else {
            chineseNumber(seconds) + "秒" + CHINESE_DIGITS[tenth]
        }
        return "第" + chineseNumber(lapNumber) + "圈，" + body
    }

    /**
     * 整数 → 自然中文（0-99）。10→"十"、11→"十一"、20→"二十"、23→"二十三"。
     * ≥100 或负数走兜底逐位（圈号/分/秒不会到这量级，仅防御）。
     */
    private fun chineseNumber(n: Int): String {
        if (n < 0) return "负" + chineseNumber(-n)
        return when {
            n < 10 -> CHINESE_DIGITS[n]
            n < 20 -> "十" + if (n % 10 == 0) "" else CHINESE_DIGITS[n % 10]
            n < 100 -> CHINESE_DIGITS[n / 10] + "十" + if (n % 10 == 0) "" else CHINESE_DIGITS[n % 10]
            else -> n.toString().map { CHINESE_DIGITS[it - '0'] }.joinToString("")
        }
    }

    private fun dingThenSpeak(text: String, utteranceId: String) {
        val tg = toneGenerator
        FileLogger.d("VoiceAnnouncer", "announce id=$utteranceId text=\"$text\" ding=${tg != null}")
        if (tg == null) {
            speak(text, utteranceId)
            return
        }
        // 先"叮"一声（短蜂鸣），约 250ms 后再说，避免提示音盖住开头。
        tg.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_DURATION_MS)
        mainHandler.postDelayed({ speak(text, utteranceId) }, SPEAK_DELAY_MS)
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
        mainHandler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        toneGenerator?.release()
    }

    private companion object {
        val CHINESE_DIGITS = arrayOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")
        const val TONE_VOLUME = 80 // 0-100
        const val TONE_DURATION_MS = 150
        const val SPEAK_DELAY_MS = 250L
    }
}
