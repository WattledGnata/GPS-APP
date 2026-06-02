// @IgnoreFormatCheck
package com.blazepush.feature.test.livetiming

import com.blazepush.feature.test.model.laptiming.LapRecord

/**
 * 出圈上报触发接口。TestSessionViewModel 只依赖此接口（解耦 + 单测可传 no-op，
 * 不必构造完整 LapUploadOrchestrator + 网络/Room/DataStore 一套 fake）。
 * 生产实现为 [LapUploadOrchestrator]。
 */
interface LapUploadTrigger {
    suspend fun onLapCompleted(record: LapRecord)
    suspend fun flush()
}
