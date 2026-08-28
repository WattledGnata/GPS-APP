# Proposal: fix-crossing-events-write-amplification

## Why

2026-06-03 路测设备 Room 库 `crossing_events` 表 18086 行,绝大多数是 25Hz 逐帧写入的 `accepted=0 reason=NoIntersection` 事件(几何不相交的常规帧,相邻行仅时间戳差 40ms,零诊断价值)——一晚路测 1.8 万行,长 session 写放大线性失控,违反"Room 只存 metadata 和事件"的 A56 架构约束。写入点:`TestSessionViewModel.kt:933-953` 把引擎每帧产生的全部 CrossingEvent(含逐帧拒绝)无差别 `writeCrossing` 入库。

消费方核查(盲点 #16,已逐一确认):lapCount/bestLapMs 派生(TelemetryRepository endSession)、LapSessionDetailScreen 指标、getLapTelemetry 全部为 `accepted=true && StartFinish` 配对语义——**拒绝事件无任何 DB 消费方**;调试屏显示的是 in-memory `LapRecord.crossingEvents`,不读库。

## What Changes

- 写入侧过滤(VM 层,repo 保持忠实写入):`accepted == true` 全写(计时真相源不动);`accepted == false` 仅保留 reason ≠ NoIntersection 的拒绝(WrongDirection/UnexpectedGateOrder/TooSlow/Cooldown——真实过线被拒,罕见且有诊断价值);**NoIntersection 拒绝不入库**。
- 过滤谓词提取纯函数 + 单测。
- 设计修正透明声明:原议"near-miss 采样"不可行——NoIntersection 时 detector 不输出距 gate 距离,无 near-miss 信号;全滤是唯一可执行语义(诊断"为何没过线"由引擎 vSampled 采样日志承担)。

非目标:引擎 in-memory `session.crossingEvents` 全量累积(2h ≈ 18 万对象 ≈ ~18MB)——改引擎契约面大,列 backlog 观察。

## Capabilities

### New Capabilities
<!-- 无:写入过滤是实现策略,无新 capability -->

### Modified Capabilities
<!-- 无 spec 级行为变化:lap-timing-engine 的 R4(全事件记录)是 in-memory 契约,不涉持久化;持久化语义(accepted 真相源)不变 -->

## Impact

- **代码**:`TestSessionViewModel.kt`(写入循环 filter)+ 纯函数 + 单测。
- **不碰**:CrossingEventDao/schema、引擎、消费方。
- **行为变化**:新 session 的 crossing_events 行数从 ~25 行/秒降到 ~每圈个位数;旧数据不清理(查询语义兼容)。
