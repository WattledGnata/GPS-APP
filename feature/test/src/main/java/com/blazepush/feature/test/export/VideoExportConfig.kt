// @IgnoreFormatCheck
package com.blazepush.feature.test.export

/**
 * 视频导出编码参数常量（round video-export-burned-overlay · Round B · Decision 8）。
 *
 * 一期固定同源分辨率 + 固定码率档（不给用户选）。常量抽出便于真机后按观感/兼容性调。
 *
 * @author CC
 * @description video export encoder config constants
 * @date 2026-05-31
 */
object VideoExportConfig {

    /** 视频编码 MIME（H.264）。 */
    const val VIDEO_MIME = "video/avc"

    /** 默认帧率（源 MediaFormat 缺 KEY_FRAME_RATE 时用）。 */
    const val DEFAULT_FRAME_RATE = 30

    /** I 帧间隔（秒）。 */
    const val I_FRAME_INTERVAL_SEC = 1

    /**
     * 码率系数：bitrate = width * height * frameRate * BPP_FACTOR（bits/pixel/frame）。
     * 0.15 对 1080p30 约 ≈ 9.3Mbps，画质/体积平衡档（H.264）。
     */
    const val BPP_FACTOR = 0.15

    /** 码率下限（bps），防低分辨率算出过低码率糊画面。 */
    const val MIN_BITRATE = 2_000_000

    /** 码率上限（bps），防超大分辨率算出过高码率。 */
    const val MAX_BITRATE = 20_000_000

    /** 计算编码码率（bps）。 */
    fun computeBitrate(width: Int, height: Int, frameRate: Int): Int {
        val raw = (width.toLong() * height.toLong() * frameRate * BPP_FACTOR).toLong()
        return raw.coerceIn(MIN_BITRATE.toLong(), MAX_BITRATE.toLong()).toInt()
    }
}
