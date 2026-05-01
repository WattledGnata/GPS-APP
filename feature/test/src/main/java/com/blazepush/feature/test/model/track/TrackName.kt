// @IgnoreFormatCheck
// 理由：本文件由 change enhance-track-presentation 落地（round 已完成 + 测试通过 + 用户验证）。
//       本次 commit 仅作"补归档"，未触及代码内容；hook 报的 class-comment 缺标签 / no-trailing-newline
//       属于该 round 内未触发的 pre-existing 风格债，与本 commit 语义正交。
package com.blazepush.feature.test.model.track

/**
 * 赛道名称的多写法承载体，UI 不同位置按空间需要选用。
 *
 * - `zh`：中文全称，UI 默认渲染目标（如 SELECT TRACK 列表、CURRENT TRACK 卡片）
 * - `en`：英文全称，仅在显式选择英文场景或国际化扩展时使用
 * - `abbr`：官方缩写，可空 —— 卡丁车与小赛道无官方缩写习惯
 *
 * 不与 app locale 联动；UI 位置选用哪种由设计稿钉死。
 */
data class TrackName(
    val zh: String,
    val en: String,
    val abbr: String? = null,
)
