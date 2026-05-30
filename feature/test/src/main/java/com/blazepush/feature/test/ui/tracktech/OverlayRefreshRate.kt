// @IgnoreFormatCheck
package com.blazepush.feature.test.ui.tracktech

/**
 * UI 数据刷新降频目标（round redo-video-overlay-visual-gauges）。
 *
 * 真机路测反馈：overlay playhead 轮询 + 实时 HUD 跟 25Hz GPS 流每帧重组 → 渲染负担偏高。
 * 降频策略：**采样链路仍 25Hz 不动**（GPS 接收 / 遥测写入 / binary sample 全不改），仅在 **UI 消费侧**
 * 把刷新节流到 ~10Hz（100ms 一次重组）。视频本身播放由 ExoPlayer 自渲染，不受此节流影响。
 *
 * 抽成常量便于真机后调（如发现 10Hz 圈速 timer 跳动太顿可回调到 15Hz）。
 */
const val OVERLAY_UI_REFRESH_PERIOD_MS: Long = 100L // 10Hz
