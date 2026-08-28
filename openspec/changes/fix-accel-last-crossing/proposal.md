# Proposal: fix-accel-last-crossing

## Why

2026-06-03 vivo V2405A 路测,0-100 加速测试出现两类假成绩:(a) 成绩 53.32s / 总距离 260m / avgAccel 0.105g(Room `test_records` 实测行)——车辆在起步前多次低速蠕动,冗余段全部计入;(b) 滤波后最高车速仅 99.0 km/h(`telemetry_sessions.topSpeedKmh`)从未破百,却仍产出"完成"成绩。

代码级根因(已逐行核实,非推测):

1. **`CalculateResultUseCase.correctTimingPoints`(core/domain/src/main/java/com/blazepush/core/domain/usecase/CalculateResultUseCase.kt:73-97)对 0-100 是 dead code**:`Acceleration0To100.startSpeed = 0`(TestModels.kt:56),`findPrecisePoint` 的过线条件 `prev.speed < 0.0 && curr.speed >= 0.0`(同文件 111-112)对恒非负的速度**永远为假**→ `preciseStart` 恒 null → line 86 `if (preciseStart == null || preciseEnd == null) return dataPoints` 整体短路。`totalTime` 退化为 session 首尾 elapsedTime 差(昨晚 session `a9c271b7` 时长 53324ms,与成绩 53.32s 严丝合缝)。
2. **100-0 刹车终点同理失效**:`Braking100To0.endSpeed = 0`,下行过线条件 `curr.speed <= 0.0` 在 GPS 速度噪声下几乎永假 → 同一条 null 短路路径。
3. **测试结束判定用 raw 未滤波速度**(`TestSessionViewModel.kt:686` `template.shouldEnd(filteredData.raw)`,`shouldEnd` 为 `gpsData.speed >= 100.0`,TestModels.kt:69-71):raw 噪声帧 ≥100 即触发完成,滤波后从未破百仍出成绩。
4. 即便修掉 0 阈值问题,`findPrecisePoint` 是 **first-crossing**(从头正向扫第一次过线),多次蠕动起步场景必然把最早蠕动至最终冲刺之间的全部时间计入——这正是用户反馈"没有从最后过线开始回溯"。

## What Changes

- 重写 `correctTimingPoints` 为 **last-crossing 回溯窗口提取**:从数据尾部反向找**最后一次**上行过终点线(0-100 的 100 km/h),再从该点反向回溯至**最近一次起步**(速度从 ≤运动阈值上穿的插值时刻),只保留这个干净窗口;窗口外的蠕动/冗余全部剔除。
- 0 阈值哨兵语义修正:模板 `startSpeed=0` / `endSpeed=0` 在计时窗口内替换为**运动阈值 1.0 km/h** 的插值过线(0 无法作为过线条件,GPS 速度恒非负)。模板字段本身不动。
- **DNF 语义**:找不到完整窗口(数据内从未真正过终点线 / 从未起步)时,不再 fallback 返回全程数据(假成绩之源),改为产出空结果(totalTime=0 + segments 空,复用现有 `emptyResult` 形态)。
- 100-0 刹车测试对称修复(last-crossing + 停车阈值)。
- 分段(`calculateSegments`)无需改动:其 `indexOfFirst` 在裁剪后的窗口内执行,语义自动变正确。
- `CalculateResultUseCaseTest` 补窗口提取全场景单测(多次蠕动 / 回落再破百 / 未破百 DNF / 刹车未停 DNF / 正常单次)。
- 调用方 `TestSessionViewModel.finishTest` 落 FileLogger 窗口摘要日志(road-test-first 模式安全网;core/domain 无日志依赖,锚点放 VM 层)。

非目标(透明声明):`shouldEnd(raw)` 用 raw 判停的问题**不在本 round 修**——修复后的 DNF fallback 已兜住其恶果(raw 误触发停止 → 窗口找不到过线 → DNF 而非假成绩);改 filtered 判停涉及触发余量调参,列入 §10 backlog 观察。

## Capabilities

### New Capabilities
- `perftest-timing-window`: 0-100/100-0 性能测试成绩计时窗口提取——last-crossing 回溯、运动阈值插值、无完整窗口的 DNF 语义。

### Modified Capabilities
<!-- 无:perftest-acceleration-smoothing(SG 加速度仍在 raw 等间距点上,不受影响)、test-execution-launch-threshold(UI 文案阈值,与成绩口径独立)的 requirements 均不变 -->

## Impact

- **代码**:`core/domain/.../CalculateResultUseCase.kt`(算法重写,单文件);`feature/test/.../TestSessionViewModel.kt`(finishTest 处 +1 条日志);`core/domain/.../CalculateResultUseCaseTest.kt`(新增场景)。
- **不碰**:Room schema(`TestResult`/`test_records` 字段零增减,DNF 用 totalTime=0 表征)、GPS 接收链路/replay 协议、`TestTemplate` 公共字段、UI 组件。
- **行为变化(用户可见)**:多次蠕动起步后成绩显著变短(变准);未真正破百的测试从"假成绩"变为 0.00s 空结果(UI 打磨为显式 DNF 标识列入 backlog)。
- **风险**:窗口算法边界(恰好压线/单点数据)由单测反例锁;旧持久化成绩不回算(历史数据保持原样)。
