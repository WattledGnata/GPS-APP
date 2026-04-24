package com.blazepush.feature.test.model.track

data class Track(
    val id: String,
    val name: String,
    val layoutName: String? = null,
    val source: TrackSource = TrackSource.Preset,
    val referencePath: TrackPath,
    val startFinishGate: TimingGate,
    val sectorGates: List<TimingGate> = emptyList()
) {
    /**
     * `sectorGates` 按 `sequenceIndex` 升序排列的**单点真理**派生字段。
     *
     * 引入理由（openspec fix-lap-timing-campaign-c-tail-cleanup A36）：engine 内消费
     * sector 顺序的两处（`handleSectorCrossing` / `expectedGate`）原本各自
     * `sortedBy { sequenceIndex }`，单点真理缺失 —— 未来某一处被误改为
     * `sortedByDescending` / 改 comparator / 不 sort 时，另一处不会同步改、测试也不
     * 会立刻 fail。本字段把排序语义统一到 Track 模型上，engine 只读字段不再重复 sort。
     *
     * **计算方式**：Kotlin `by lazy`，首次访问计算一次缓存；对"Track 只用
     * startFinishGate 不消费 sectorGates"的场景（JSON 解析中间态、UI 展示）零开销。
     *
     * **data class 相等性**：本字段是 data class body 内成员属性，**不在 primary
     * constructor 中**，因此不参与 Kotlin data class 自动生成的 `equals` /
     * `hashCode` / `copy`。两个声明字段相同但一个触发过 lazy 一个未触发的 Track
     * 实例仍然相等。
     */
    val orderedSectorGates: List<TimingGate> by lazy { sectorGates.sortedBy { it.sequenceIndex } }
}
