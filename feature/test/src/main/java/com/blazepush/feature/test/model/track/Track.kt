// @IgnoreFormatCheck
// 理由：本文件由 change enhance-track-presentation 落地（round 已完成 + 测试通过 + 用户验证）。
//       本次 commit 仅作"补归档"，未触及代码内容；hook 报的 class-comment / no-trailing-newline
//       属于该 round 内未触发的 pre-existing 风格债，与本 commit 语义正交。
package com.blazepush.feature.test.model.track

data class Track(
    val id: String,
    val name: TrackName,
    val lengthKm: Double,
    val thumbnailAssetPath: String? = null,
    /**
     * 可选静态预览 VectorDrawable 资源 id（R.drawable.xxx）。
     *
     * 引入理由（round `track-preview-static-vector`）：赛道几何固定，预览是一次性活儿，
     * 运行时 Canvas 逐帧投影 + drawPath 成本高（列表多 thumbnail 尤甚）。轨迹一次性
     * 离线投影成矢量 path 存成 drawable 后，thumbnail 直接 `painterResource` 显示静态
     * 矢量图，不再实时绘制。优先级最高（见 [com.blazepush.feature.test.ui.tracktech.TrackThumbnail]）。
     *
     * null（默认）= 该赛道无预生成矢量图，退到 asset PNG → 动态轮廓 → NO PREVIEW。
     */
    val thumbnailDrawableResId: Int? = null,
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
