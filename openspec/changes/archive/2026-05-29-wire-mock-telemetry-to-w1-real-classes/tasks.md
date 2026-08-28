## 1. 协同看板登记 + apply 期 #3 锚点自查

- [ ] 1.1 看 看板 §5/§6 核对：`feature/test/src/test/.../ui/components/` 当前无并行 round 占用（W2 已合回归档 `fc0afc1`；H round `improve-test-execution-progress-bar` 独占 `ui/tracktech/` 零交叉；G round done）。在 §5 登记本 round 独占路径 `feature/test/src/test/java/com/blazepush/feature/test/ui/components/`
- [ ] 1.2 **apply 期 #3 自查（grep 锚点对齐）**：实测以下命令确认锚点未漂移：
  - `grep -n "data class LapTelemetrySample\|data class LapTelemetry\|val sessionId\|val lapIndex\|val lapDurationMs\|val trackId\|val trackNameSnapshot\|val flags" core/domain/src/main/java/com/blazepush/core/domain/model/LapTelemetry.kt`（确认 real `LapTelemetry` 9 字段 + `LapTelemetrySample` 含 `flags:Int=0`；ff 期实测：`LapTelemetrySample` 定义在 line 13、`LapTelemetry` 定义在 line 32）
  - `grep -n "internal data class FakeLapTelemetry\|fun mockSingleLap\|fun mockMultiLap\|FakeLapTelemetry(" feature/test/src/test/java/com/blazepush/feature/test/ui/components/MockTelemetry.kt`（ff 期实测：`FakeLapTelemetry` 定义 line 14；`mockSingleLap` line 26；`FakeLapTelemetry(` 构造在 line 67 + line 81；`mockMultiLap` line 75）
  - done condition：锚点行号与本 tasks 描述一致或记录实测偏移
- [ ] 1.3 **apply 期 #14 自查（fake DAO 漏 abstract）**：本 round 不碰任何 DAO 接口（纯 UI 组件 mock helper），grep 确认 0 个 DAO 接口签名变化 → #14 N/A，记录「本 round 无 DAO 改动」
- [ ] 1.4 **apply 期 #16 自查（跨 round 共享字段 drift）**：本 round **消费** real `LapTelemetry`，**不扩展**任何共享 entity 字段（不改 `core/domain`），故不触发 #16 producer 责任；但 verify real `LapTelemetry` 的 `flags:Int=0` 在 mock 切换后通过 `LapTelemetrySample` 默认值正确传播（mock samples 不显式传 flags → 默认 0，与 reader 透传语义无冲突，因为 mock 不模拟 binary flags 信号）

## 2. 切换 MockTelemetry.kt 到正式 LapTelemetry

- [ ] 2.1 在 `feature/test/src/test/java/com/blazepush/feature/test/ui/components/MockTelemetry.kt` 顶部 import 段新增 `import com.blazepush.core.domain.model.LapTelemetry`（`LapTelemetrySample` 已在 line 3 import）
- [ ] 2.2 **删除** `internal data class FakeLapTelemetry`（当前 `MockTelemetry.kt:14-19`，4 字段占位类）。done condition：grep `internal data class FakeLapTelemetry` 命中 0
- [ ] 2.3 改 `mockSingleLap(n: Int = 100, lapDurationMs: Long = 60_000)`（当前 line 26）返回类型 `FakeLapTelemetry` → `LapTelemetry`；把 line 67-72 的 `FakeLapTelemetry(samples=..., sectorBoundaries=..., lapStartWallClock=..., lapEndWallClock=...)` 构造改为 `LapTelemetry(...)`，按 design Decision 2 字段填充表补全 5 字段：
  - `sessionId = "mock-session"`
  - `lapIndex = 0`
  - `lapDurationMs = lapDurationMs`（复用入参；== `lapEnd - lapStart`）
  - `trackId = null`
  - `trackNameSnapshot = null`
  - done condition：9 字段全填，`lapDurationMs == lapEnd - lapStart` 自洽
- [ ] 2.4 改 `mockMultiLap(n: Int = 3)`（当前 line 75）返回类型 `List<FakeLapTelemetry>` → `List<LapTelemetry>`；其内部当前是 `durations.map { duration -> ... FakeLapTelemetry(samples=..., sectorBoundaries=..., lapStartWallClock=..., lapEndWallClock=...) }`（line 78-92，**从头重建容器，不是 `.copy`**，只从 `mockSingleLap(...)` 结果取 `samples`/`sectorBoundaries` 做 offset 平移）：
  - 把 `durations.map { duration ->` 改为 `durations.mapIndexed { index, duration ->`（拿到圈索引）
  - 把重建的 `FakeLapTelemetry(...)` 改为 `LapTelemetry(...)`，9 字段全填：`samples`/`sectorBoundaries`（仍取 `it` 平移后的值）+ `lapStartWallClock = currentStart` + `lapEndWallClock = currentStart + duration` + 5 个新字段：
    - `sessionId = "mock-session"`（各圈共享）
    - `lapIndex = index`（实现 design Decision 2 「第 i 圈 lapIndex == i」）
    - `lapDurationMs = duration`（== `(currentStart + duration) - currentStart`，自洽）
    - `trackId = null`；`trackNameSnapshot = null`
  - 注意：从头重建会丢弃 `mockSingleLap` 结果（`it`）自带的 `sessionId`/`lapIndex`/`lapDurationMs`，所以这 3 个字段必须在重建时显式赋值（用上面的 index/duration），不能依赖 `it` 的值（`it.lapIndex` 恒为 0）
  - done condition：`mockMultiLap(3)[i].lapIndex == i` && 各圈 `lapDurationMs == lapEndWallClock - lapStartWallClock`

## 3. verify 5 个 contract test（预期零 diff）

- [ ] 3.1 实测 `:feature:test:compileDebugUnitTestKotlin` 通过——编译期强制 real `LapTelemetry` 全 non-null 字段填全（spec 反例 scenario「遗漏 non-null 必填字段则编译失败」的正向验证）
- [ ] 3.2 实测 `:feature:test:testDebugUnitTest --tests "*ui.components*"` 全绿（`SpeedTimeChartContractTest` / `AccelTimeChartContractTest` / `SectorBarContractTest` / `TrackPolylineMapContractTest` / `GrepGateTest`）。这 5 个文件消费 `lap.samples` / `lap.sectorBoundaries` / `lap.lapStartWallClock` / `lap.lapEndWallClock`，real `LapTelemetry` 同名提供 → 预期零 diff
- [ ] 3.3 **仅当某断言实测 fail 才微调**（design Decision 3）：fail 时定位是否因 real 容器形态变化，做最小修订并在此勾选时记录 fail 的 case 名 + 修订原因；若全绿则记录「5 个 contract test 零 diff 全绿」
- [ ] 3.4 verify GrepGateTest §8.6（`mockSingleLap`/`mockMultiLap` import ≥4 + prod 0 引用）+ §8.7（`LapTelemetry.kt` 8 字段含 `val flags: Int`）仍绿——本 round 不改 `core/domain`、不改 helper 函数名，这两 gate 状态不变（risks 段已分析）

## 4. 编译 + 单测全套

- [ ] 4.1 `:feature:test:testDebugUnitTest`（feature/test 全套零回归，确认切换未波及 components 外的测试）
- [ ] 4.2 `:feature:test:compileDebugKotlin`（生产编译确认——本 round 不改生产代码，应原样通过）

## 5. 真机验证

- [ ] 5.1 **SKIP**：本 round 纯测试代码改动（test source set），无 UI / 运行时行为改动，无真机验证场景。在 metrics.yaml 透明声明 SKIP 理由（按 round 实际 UI 路径判定，与加速通道无关）

## 6. commit + 合回 + L2 + 归档

- [ ] 6.1 commit：`test(chart): MockTelemetry FakeLapTelemetry 切到正式 LapTelemetry 补全 W1 5 字段`
- [ ] 6.2 ff-only 合回主区 `feature/track-tech-v2`（合回由主会话串行做）
- [ ] 6.3 主区编译确认（`:feature:test:testDebugUnitTest --tests "*ui.components*"`）
- [ ] 6.4 **L2**：road-test-first 模式下去 Codex（按 user 当前授权批次）；若回退到加速通道则提醒 user 触发 Codex L2 单线兜底
- [ ] 6.5 写 metrics.yaml：`complexity: small` + `review_mode`（road-test-first 或 accelerated，按合回时 user 授权批次）+ `review_rounds_l1/l2: 0` + `codex_l1/l2_findings`（去 Codex 则 `[]` 注明）+ `accelerated_escalation: null` + `design_decisions_diverged_during_apply: []`（透明声明无 drift）+ `cross_round_field_drift_resolved: []`（本 round 消费方不扩展字段）+ 真机 SKIP 理由
- [ ] 6.6 归档为 `archive/<date>-wire-mock-telemetry-to-w1-real-classes`（proposal/design/specs/tasks/metrics.yaml）
- [ ] 6.7 看板 §5 状态改 done + 最近合回 commit；W2 归档 tasks.md §11.2 disposition 标 done（本 round 即其 follow-up）
- [ ] 6.8 **需用户显式确认才能 push**

## 10. follow-up backlog（不在本 round 实现）

- [ ] 10.1 `future-sector-derivation-round`（路线图 §3，medium）：`getLapTelemetry` 用 sector gate crossing 真实派生多元素 `sectorBoundaries`（当前 reader 只放 `listOf(lapStartWallClock)` 单元素，见 `TelemetryRepository.kt:305`），喂 `SectorBar` 画多段。本 round mock 已保留 3-sector 测试信号，reader 侧补齐后 detail 屏 SectorBar 才能在生产显示多段。**触发条件**：`lap-detail-screen-with-cursor` 组屏前（路线图 §4 第二批排序修正：sector 派生须前置/并入 detail 屏，否则 detail 屏先做 1 段废 SectorBar 二次返工）
- [ ] 10.2 `lap-detail-screen-with-cursor`（路线图 §3，medium）detail 屏 R1：accelerationG 派生位置拍板（reader 侧 vs UI 层接 `AccelerationSmoother` 从 `speedKmh` 反算）。当前 reader `getLapTelemetry` 硬编码 `accelerationG = null`（`TelemetryRepository.kt:294`），本 round mock 已保留中央差分 accelerationG 测试信号，生产链路需在 R1 决策后灌回。**本 round 是该屏的前置依赖（验证 chart 消费正式 LapTelemetry 形态），不在本 round 做组屏**
