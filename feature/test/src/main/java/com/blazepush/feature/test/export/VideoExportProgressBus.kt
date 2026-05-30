// @IgnoreFormatCheck
package com.blazepush.feature.test.export

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 导出进度共享总线（round video-export-burned-overlay · Round B · Decision 4）。
 *
 * Service ↔ UI 解耦：[VideoExportService] 更新进度/状态，详情屏 UI 观察 [state] 显示进度对话框。
 * 单例（object）+ StateFlow，避免 Service 绑定/LocalBroadcast 的样板。同一时刻只支持一个导出
 * （一期 scope，多并发导出 backlog）。
 *
 * @author CC
 * @description shared progress bus between export service and UI
 * @date 2026-05-31
 */
object VideoExportProgressBus {

    /** 导出状态机。 */
    sealed class State {
        /** 空闲（无导出 / 已消费完成事件）。 */
        object Idle : State()

        /** 导出中：[percent] 0..100，[sessionId]/[lapNumber] 标识当前导出。 */
        data class Running(
            val sessionId: String,
            val lapNumber: Int,
            val percent: Int,
        ) : State()

        /** 完成：[uri] 可分享 content URI（null = 写入成功但 URI 不可用）。 */
        data class Done(
            val sessionId: String,
            val lapNumber: Int,
            val uri: Uri?,
        ) : State()

        /** 失败/取消：[message] 提示文案。 */
        data class Failed(
            val message: String,
        ) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun setRunning(sessionId: String, lapNumber: Int, percent: Int) {
        _state.value = State.Running(sessionId, lapNumber, percent.coerceIn(0, 100))
    }

    fun setDone(sessionId: String, lapNumber: Int, uri: Uri?) {
        _state.value = State.Done(sessionId, lapNumber, uri)
    }

    fun setFailed(message: String) {
        _state.value = State.Failed(message)
    }

    /** UI 消费完终态（Done/Failed）后复位（避免重复弹窗）。 */
    fun reset() {
        _state.value = State.Idle
    }
}
