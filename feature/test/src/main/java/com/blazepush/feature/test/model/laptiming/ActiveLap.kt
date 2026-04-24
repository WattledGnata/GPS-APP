package com.blazepush.feature.test.model.laptiming

/**
 * 进行中的圈状态快照。
 *
 * 语义（openspec `fix-lap-timing-closure-and-precision-contract` R2 / R3）：
 * - [startedAtMillis] 是**过线插值毫秒时刻**（`prev.ts + crossingProgress × (current.ts - prev.ts)`），
 *   不是帧粒度 ts；等于对应起点过线 CrossingEvent.timestampMillis。
 * - [sampleStartIndex] v2 语义降级为**性能索引**：仅作 `subList` 起点跳过非本圈前驱帧，
 *   **不参与归属判定**。最终 trajectory 归属严格按时间窗口 `[startedAtMillis, finishedAtMillis)`
 *   由 filter 裁剪（见 `LapTimingEngine.handleStartFinishCrossing` R3 两段式切分）。
 *   若未来某重构让 `sampleStartIndex` 位置与时间窗口边界不一致（理论可因 A38 ts 回跳
 *   守卫产生"幻帧"），filter 时间窗口兜底保证 `trajectory` 不越界。
 */
data class ActiveLap(
    val lapIndex: Int,
    val startedAtMillis: Long,
    val passedGateIds: List<String> = emptyList(),
    val sectorEntries: List<SectorEntry> = emptyList(),
    val sampleStartIndex: Int
)
