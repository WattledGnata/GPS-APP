// @IgnoreFormatCheck
package com.blazepush.feature.test.recording

/**
 * 录制状态机（sealed class）。
 *
 * 状态转移：
 *   Idle → [Recording]（startRecording 成功 + VideoRecordEvent.Start 到来）
 *   Recording → [Stopping]（stopRecording 请求已发，等待 Finalize）
 *   Stopping → [Idle]（VideoRecordEvent.Finalize 成功）
 *   任意状态 → [Error]（bind 失败 / Finalize error）
 *   Error → [Idle]（可手动 reset，UI 重试）
 *
 * @author CC
 * @description 录制状态机（Phase 2 round 3 · camera-recording-and-gps-sync）
 * @date 2026-05-30
 */
sealed class RecordingState {

    /** 空闲，未录制 */
    object Idle : RecordingState()

    /**
     * 录制中。
     *
     * @param startedAtWallClock VideoRecordEvent.Start 回调时取 System.currentTimeMillis()（与遥测同时钟域）
     * @param sessionId          录制时刻的 active lap session id；null = 无 active session（孤立视频）
     */
    data class Recording(
        val startedAtWallClock: Long,
        val sessionId: String?,
    ) : RecordingState()

    /**
     * 停止中：stopRecording() 已调用，等待 VideoRecordEvent.Finalize 完成落盘。
     * UI 可显示"停止中..."提示。
     */
    object Stopping : RecordingState()

    /**
     * 错误：bind 失败 / Finalize error code 非零 / 权限缺失等。
     *
     * @param message 错误描述，供 FileLogger 和 UI 显示
     */
    data class Error(val message: String) : RecordingState()
}
