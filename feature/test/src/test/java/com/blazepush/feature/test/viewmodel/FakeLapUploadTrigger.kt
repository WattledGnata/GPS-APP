// @IgnoreFormatCheck
package com.blazepush.feature.test.viewmodel

import com.blazepush.feature.test.livetiming.LapUploadTrigger
import com.blazepush.feature.test.model.laptiming.LapRecord

/**
 * TestSessionViewModel 单测用 no-op 上报触发：这些测试不验证 livetiming 上报，
 * 用 no-op 避免构造完整 LapUploadOrchestrator + 网络/Room/DataStore 一套 fake。
 * 上报逻辑由 LapUploadOrchestratorTest 独立覆盖。
 */
class FakeLapUploadTrigger : LapUploadTrigger {
    override suspend fun onLapCompleted(record: LapRecord) {}
    override suspend fun flush() {}
}
