# Design: fix-crossing-events-write-amplification

## Context

`TestSessionViewModel.kt:933-953`:引擎每帧返回的 `newCrossings` 增量(游标 `lastWrittenCrossingCount`)逐条 `telemetryRepository.writeCrossing`。引擎对**期待门每帧**产出 CrossingEvent(accepted 或 rejected,LapTimingEngine R4 契约)——rejected 中 `NoIntersection` 是 25Hz 常规帧。`CrossingReason` 枚举:Accepted/WrongDirection/UnexpectedGateOrder/TooSlow/Cooldown/NoIntersection。

## Goals / Non-Goals

**Goals:** 写放大消除(NoIntersection 不入库);计时真相源(accepted)与有价值拒绝完整保留。
**Non-Goals:** 引擎 in-memory 累积(backlog);旧数据清理(行数有限,查询兼容);schema 改动。

## Decisions

### Decision 1: VM 写入侧过滤,谓词 = `accepted || reason != NoIntersection`

Alternatives:
- (a) repo `writeCrossing` 内过滤:策略下沉到存储层,将来调试 round 想写全量要改 repo。拒绝。
- (b) 引擎不产 NoIntersection 事件:破坏 R4 in-memory 全记录契约(调试屏/LapRecord 依赖)。拒绝。
- (c) VM 写入循环 filter(选):调用方决定持久化策略,1 行 filter + 纯函数谓词可测;游标 `lastWrittenCrossingCount` 仍按未过滤 size 推进(游标是 in-memory 列表索引,与入库量无关,语义不变)。
- near-miss 采样(原议)不可行:detector 对 NoIntersection 不输出距离信号,无采样依据;诊断由引擎 vSampled 日志(每秒 1 条含 reason)承担。

### Decision 2: 谓词提取为 internal 纯函数 `shouldPersistCrossing`

放 TestSessionViewModel companion(或文件顶层 internal fun),JUnit 直测 6 个 reason × accepted 组合,不需要 VM 集成基建。

## Risks / Trade-offs

- **未来若需 NoIntersection 统计**(如"接近漏检率"):DB 无数据——由 vSampled 引擎日志(1Hz 含 accepted/reason)与 in-memory LapRecord 兜底;真需要时单独立项加采样列。
- 旧 session 已有 1.8 万行垃圾不清理:行数封顶不再增长,查询全部带 sessionId 索引,无性能影响;主动清理脚本无收益不做。
