// @IgnoreFormatCheck
package com.blazepush.feature.test.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
    private var audioFocusRequest: AudioFocusRequest? = null

    /** 提示音生成器；个别设备构造会抛 RuntimeException，失败则降级为"不响铃直接说"。 */
    private val toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
    } catch (e: RuntimeException) {
        null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 初始化 TTS。
     *
     * 路测修复（2026-06-04）：原版 init ERROR 分支无任何处理无日志 → vivo 真机播报无效时
     * 无法事后诊断（2026-06-03 路测日志零 TTS 痕迹）。现 init 全结果落 FileLogger：
     * status / 绑定引擎 / 可用引擎列表 / setLanguage 两次返回值。
     */
    fun init(onReady: (() -> Unit)? = null) {
        if (isInitialized) {
            onReady?.invoke()
            return
        }

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts?.defaultEngine
                val langChinese = tts?.setLanguage(Locale.CHINESE)
                val langResult = if (langChinese == TextToSpeech.LANG_MISSING_DATA || langChinese == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                } else {
                    langChinese
                }
                // 语言不可用也继续（部分引擎 isLanguageAvailable 报不支持但 speak 中文照常）,关键是可观测
                FileLogger.d(
                    TAG,
                    "init OK engine=$engine langCN=$langChinese langFinal=$langResult engines=${tts?.engines?.map { it.name }}",
                )
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        FileLogger.d(TAG, "utterance start id=$utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        FileLogger.d(TAG, "utterance done id=$utteranceId")
                        abandonAudioFocus()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        FileLogger.e(TAG, "utterance ERROR id=$utteranceId")
                        abandonAudioFocus()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        FileLogger.e(TAG, "utterance ERROR id=$utteranceId code=$errorCode")
                        abandonAudioFocus()
                    }
                })
                isInitialized = true
                onReady?.invoke()
            } else {
                // ERROR：设备无可用 TTS 引擎 / 绑定失败 —— vivo 等设备的头号嫌疑,必须落盘
                FileLogger.e(TAG, "init FAILED status=$status（设备可能无可用 TTS 引擎,语音播报不可用）")
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
     * 播报测试未完成（DNF，unify-speed-judgement-source Decision 3）：叮 + "测试未完成"。
     * 未达目标速度的测试结束时替代"零点零零秒"。
     */
    fun announceTestNotCompleted() {
        if (!isEnabled) return
        dingThenSpeak("测试未完成", "test_dnf")
    }

    /**
     * 播报起步武装就绪（launch-arming-feedback）：叮 + "条件就绪,随时可以起步"。
     * 静止确认(滤波速度 <1.0 km/h 持续 1 秒)通过时播一次,引导用户无需看屏即可起步。
     */
    fun announceLaunchReady() {
        if (!isEnabled) return
        dingThenSpeak("条件就绪，随时可以起步", "launch_ready")
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
            FileLogger.d(TAG, "speak 时未初始化,补 init 后播 id=$utteranceId")
            init { doSpeak(text, utteranceId) }
        } else {
            doSpeak(text, utteranceId)
        }
    }

    /** 申请 AudioFocus（压低导航/音乐）后 speak；返回值落日志（原版 speak 失败完全静默）。 */
    private fun doSpeak(text: String, utteranceId: String) {
        requestAudioFocus()
        val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (r != TextToSpeech.SUCCESS) {
            FileLogger.e(TAG, "speak 返回 $r id=$utteranceId（引擎拒绝/未就绪）")
            abandonAudioFocus()
        }
    }

    /** 行车播报压低（duck）导航与音乐,播完即还（utterance onDone/onError abandon）。拿不到 focus 也照播。 */
    private fun requestAudioFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val req = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
            .also { audioFocusRequest = it }
        val result = am.requestAudioFocus(req)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            FileLogger.d(TAG, "audioFocus 未授予 result=$result（照常播报）")
        }
    }

    private fun abandonAudioFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
    }

    /**
     * 释放资源
     */
    fun shutdown() {
        mainHandler.removeCallbacksAndMessages(null)
        abandonAudioFocus()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        toneGenerator?.release()
    }

    private companion object {
        const val TAG = "VoiceAnnouncer"
        val CHINESE_DIGITS = arrayOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")
        const val TONE_VOLUME = 80 // 0-100
        const val TONE_DURATION_MS = 150
        const val SPEAK_DELAY_MS = 250L
    }
}
