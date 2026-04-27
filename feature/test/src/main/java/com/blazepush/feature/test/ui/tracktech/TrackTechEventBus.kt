package com.blazepush.feature.test.ui.tracktech

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 跨 tab 通信用的 SharedFlow event bus（轻量 object 单例）。
 *
 * 主要场景：Test/Laps 主操作未 ready 时触发 BLE Scan Sheet 自动展开。
 *
 * 用 SharedFlow 而非 ViewModel state 是为了：
 * - 自动展开 sheet 是一次性事件，不属于 ViewModel 持久状态
 * - 跨 Composable lifecycle，不依赖 navController.savedStateHandle
 *
 * @author Track Tech V2
 * @description 跨 tab 通信 SharedFlow 事件总线
 * @date 2026-04-28
 */
object TrackTechEventBus {
    private val mutableShowScanSheetEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 外部订阅入口（DeviceHomeScreen.LaunchedEffect 收集）。 */
    val showScanSheetEvent: SharedFlow<Unit> = mutableShowScanSheetEvent.asSharedFlow()

    /**
     * 请求展开 BLE Scan Sheet（一次性事件，非阻塞 tryEmit）。
     *
     * 通常由 Test/Laps 首页主操作 onClick 在 readiness.canEnterTestFlow == false
     * 且 connectionState == DISCONNECTED 时调用，DeviceHomeScreen 会接到事件后
     * 自动调 startScan() 并展开 sheet。
     */
    fun requestShowScanSheet() {
        mutableShowScanSheetEvent.tryEmit(Unit)
    }
}