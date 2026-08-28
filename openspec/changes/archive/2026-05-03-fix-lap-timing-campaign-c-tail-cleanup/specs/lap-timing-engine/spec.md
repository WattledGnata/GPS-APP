# Spec Delta: lap-timing-engine

> Capability: **Track.orderedSectorGates 单点真理（A36）**。`Track` 数据类提供
> 按 `sequenceIndex` 升序排列的 sector gates 派生字段；engine 内消费 sector 顺序的
> 所有位置 MUST 读此字段而非各自独立 sort，消除"两处独立 sort 可能被改出语义分歧"
> 的风险。
>
> 本 Requirement 不影响已闭环的 `fix-lap-timing-engine-entry-hardening`（A19 白名单 /
> A21 入口 ts 单调 / filter 严格 `>` 边界）和 `fix-lap-timing-closure-and-precision-contract`
> （A15 插值时刻 / A20 多门遍历 / A32 trajectory 时间窗口 / A33 断言补齐）语义。

## ADDED Requirements

### Requirement: Track 提供 orderedSectorGates 单点真理派生字段（A36）

`Track` 数据类 MUST 提供派生字段 `orderedSectorGates: List<TimingGate>`，作为
`sectorGates` 按 `sequenceIndex` 升序排列的唯一来源。字段 MUST 用 `by lazy` 委托，
首次访问时计算并缓存，之后返回相同引用。

engine 内所有消费 sector 顺序的位置 MUST 读 `track.orderedSectorGates`，不得再
各自执行 `sectorGates.sortedBy { it.sequenceIndex }`：

- `LapTimingEngine.handleSectorCrossing` 内遍历 sector 门：读 `track.orderedSectorGates`
- `LapTimingEngine.expectedGate(track, nextExpectedGateIndex)`：读 `track.orderedSectorGates.getOrNull(nextExpectedGateIndex - 1)`

`Track.equals` / `hashCode` / `copy` 的语义 MUST NOT 因新增 `orderedSectorGates`
字段而变化（`by lazy` 委托生成的是 getter，不在 Kotlin data class 自动生成范围内）。

#### Scenario: orderedSectorGates 对反序数据源按 sequenceIndex 升序排列

- **GIVEN** `Track(sectorGates = [S3(sequenceIndex=2), S2(sequenceIndex=1), S1(sequenceIndex=0)])`（数据层面反序）
- **WHEN** 访问 `track.orderedSectorGates`
- **THEN** 返回 `[S1, S2, S3]`（按 `sequenceIndex` 从 0 / 1 / 2 升序）

#### Scenario: orderedSectorGates 连续访问缓存同一结果

- **GIVEN** 任意 `Track` 实例
- **WHEN** 连续两次访问 `track.orderedSectorGates`
- **THEN** 两次返回的 List **是同一引用**（`first === second` 为 true，证明 `by lazy` 缓存生效）
- **AND** 元素顺序稳定（两次遍历结果逐元素 `===` 相等）

#### Scenario: Track.equals 不因 orderedSectorGates 派生字段变化

- **GIVEN** 两个 `Track` 实例 `trackA` / `trackB` 声明字段完全相同（`sectorGates` 输入 List 相等）
- **AND** `trackA` 已先访问过 `orderedSectorGates`（触发 `by lazy` 计算），`trackB` 未访问
- **WHEN** 比较 `trackA == trackB`
- **THEN** 结果为 `true`（`by lazy` 属性是 data class body 内成员属性，不在 primary constructor 中，不参与 data class 自动 `equals`/`hashCode`/`copy`）
- **AND** `trackA.hashCode() == trackB.hashCode()`
- **AND** lazy 计算是否被触发不影响相等性契约

#### Scenario: engine handleSectorCrossing 读 Track.orderedSectorGates

- **GIVEN** `LapTimingEngine.handleSectorCrossing` 入口
- **WHEN** 需要遍历 sector 门做 detection
- **THEN** 实现 MUST 读 `track.orderedSectorGates`，MUST NOT 执行 `track.sectorGates.sortedBy { it.sequenceIndex }`
- **AND** 所有非期待门 CrossingEvent 追加顺序仍按 `sequenceIndex` 升序（R4 契约保留）

#### Scenario: engine expectedGate 读 Track.orderedSectorGates

- **GIVEN** `LapTimingEngine.expectedGate(track, nextExpectedGateIndex)`
- **WHEN** 需要定位第 N 个期待 sector 门
- **THEN** 实现 MUST 调 `track.orderedSectorGates.getOrNull(nextExpectedGateIndex - 1)`，
  MUST NOT 独立执行 `sectorGates.sortedBy { ... }.getOrNull(...)`
