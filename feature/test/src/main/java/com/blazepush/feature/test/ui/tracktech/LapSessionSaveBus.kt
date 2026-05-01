package com.blazepush.feature.test.ui.tracktech

import com.blazepush.feature.test.viewmodel.LapSessionSaveResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * HOLD TO END 完成后由 LapLiveScreen emit、TrackTechAppShell 顶层 LaunchedEffect 唯一 collect 的事件总线。
 *
 * 关键约束：
 * - LapLiveScreen 不持有 SnackbarHostState；emit 后立刻 popBackStack 回 home，不阻塞等 Snackbar dismiss
 * - 唯一 collector 是 Shell-level LaunchedEffect，时序上 Shell collect 必先于 emit（Shell 在 LapLiveScreen 进入前已订阅）
 *
 * @author CC
 * @description lap session save event bus for shell-level snackbar dispatch
 * @date 2026-05-01
 */
object LapSessionSaveBus {
    private val mutableEvents = MutableSharedFlow<LapSessionSaveResult>(extraBufferCapacity = 1)

    val events: SharedFlow<LapSessionSaveResult> = mutableEvents.asSharedFlow()

    /**
     * LapLiveScreen 在 HOLD TO END / EndConfirmationDialog 完成后调用：把 save result 推到 bus。
     * Shell 顶层 LaunchedEffect 是唯一 collector，会在 Shell scope 显示 Snackbar。
     *
     * @author CC
     * @description emit lap session save result to shell collector
     * @date 2026-05-01
     */
    suspend fun emit(result: LapSessionSaveResult) {
        mutableEvents.emit(result)
    }
}