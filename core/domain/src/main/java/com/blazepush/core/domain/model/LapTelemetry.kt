package com.blazepush.core.domain.model

// W3 round 占位类型，W1 lap-data-readers 合回后由 worktree rebase 期 git rm 删除
// （详见本 round design D6 + tasks §7.3 rebase 流程）

data class LapTelemetry(
    val sessionId: String,
    val lapIndex: Int,                    // 0-based
    val lapStartWallClock: Long,           // 真壁钟 ms
    val lapEndWallClock: Long,             // 真壁钟 ms
    val lapDurationMs: Long,               // == lapEnd - lapStart
    val samples: List<LapTelemetrySample>, // 按 absoluteTs 升序
    val sectorBoundaries: List<Long>,      // 各 sector 起点 absoluteTs
    val trackId: String?,
    val trackNameSnapshot: String?,
)

data class LapTelemetrySample(
    val absoluteTsMs: Long,
    val elapsedMsInLap: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Double,
    val bearingDeg: Double?,               // nullable，跨 0°/360° 走最近邻
    val accelerationG: Double?,            // nullable，W1 不强制，W3 派生；初期生产数据全 null
)
