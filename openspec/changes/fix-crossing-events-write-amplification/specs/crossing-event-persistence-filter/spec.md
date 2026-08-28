# crossing-event-persistence-filter

crossing_events 持久化写入过滤:消除 25Hz NoIntersection 写放大,保留计时真相源。

## ADDED Requirements

### Requirement: NoIntersection 拒绝事件 MUST NOT 入库

过线事件持久化 SHALL 仅写入 `accepted == true` 或 `reason != NoIntersection` 的事件;25Hz 逐帧产生的 NoIntersection 拒绝 MUST NOT 写入 Room。

#### Scenario: accepted 事件全部入库(真相源回归锁)
- **GIVEN** 引擎产出 accepted=true 的 StartFinish/Sector 过线
- **WHEN** 写入循环处理
- **THEN** 全部 writeCrossing——lapCount/bestLapMs 派生语义不受任何影响

#### Scenario: NoIntersection 逐帧拒绝零入库(写放大回归锁)
- **GIVEN** 25Hz × 60s 常规行驶帧(1500 个 accepted=false reason=NoIntersection 事件)
- **WHEN** 写入循环处理
- **THEN** writeCrossing 调用 0 次——此断言失败即 1.8 万行写放大回归

#### Scenario: 有价值拒绝保留
- **GIVEN** accepted=false 且 reason ∈ {WrongDirection, UnexpectedGateOrder, TooSlow, Cooldown}
- **WHEN** 写入循环处理
- **THEN** 照常入库(真实过线被拒,诊断价值保留)
