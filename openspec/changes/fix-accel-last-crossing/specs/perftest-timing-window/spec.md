# perftest-timing-window

0-100/100-0 性能测试成绩计时窗口提取:last-crossing 回溯、运动阈值插值、无完整窗口的 DNF 语义。

## ADDED Requirements

### Requirement: 加速测试计时窗口 MUST 取最后一个完整(起步→首次过线)候选段

`CalculateResultUseCase` 对 `Acceleration0To100` SHALL 以单次正向状态机提取候选段:速度从 <1.0 km/h 上穿(运动阈值插值)开启候选,**首次**上行过 `endSpeed`(prev.speed < end ≤ curr.speed,插值)关闭候选,速度掉回 <1.0 废弃未完成锚点并重新武装;成绩窗口 SHALL 为**最后一个完整候选段**;`totalTime` SHALL 等于窗口首尾 elapsedTime 差,窗口外样本 MUST NOT 计入 totalTime/totalDistance/segments。

#### Scenario: 多次蠕动起步只算最后一轮冲刺
- **GIVEN** dataPoints 含两段蠕动(0→5→0 km/h、0→8→0 km/h,合计 30s)后接一段干净冲刺(0→100,8s)
- **WHEN** 计算 TestResult
- **THEN** totalTime ≈ 8s(蠕动回合掉回 <1.0 废弃锚点,冲刺段为唯一完整候选),蠕动 30s 不计入

#### Scenario: 单次干净起步成绩与窗口一致
- **GIVEN** dataPoints 为单调 0→100 加速(无蠕动)
- **WHEN** 计算 TestResult
- **THEN** totalTime = 1.0 km/h 上穿插值时刻 → 100 km/h 上穿插值时刻

#### Scenario: 回落再破百以首次触线停表(物理口径)
- **GIVEN** dataPoints 速度轨迹 0→102→95→103(两次上行过 100,中途未掉回 1.0 以下)
- **WHEN** 计算 TestResult
- **THEN** 窗口终点为**第一次**过 100(102 段)的插值时刻——0-100 成绩首次触线即停表(Dragy 同口径);95→103 回落再冲段 MUST NOT 计入 totalTime

#### Scenario: 过线后停车挪车不丢成绩(终点 last-crossing 语义反例)
- **GIVEN** dataPoints 为干净冲刺 0→105 后,降速停车再低速挪车(0→6→0,未过 100)
- **WHEN** 计算 TestResult
- **THEN** 成绩窗口仍为冲刺段(挪车起步无过线,不产生新完整候选);**MUST NOT** 判 DNF——此断言失败说明实现退化为"全局最后一次起步"语义

### Requirement: 无完整窗口 MUST 产出 DNF 空结果

数据中不存在上行过 `endSpeed` 的相邻对,或终点反向回溯找不到运动阈值上穿时,SHALL 产出空结果(totalTime=0.0、totalDistance=0.0、segments 空、dataPoints 空);MUST NOT 退回"返回全程数据"的旧行为。

#### Scenario: 未破百不得产出正成绩(路测回归反例)
- **GIVEN** dataPoints 最高速度 99.0 km/h(2026-06-03 路测 session a9c271b7 形态),session 总时长 53.32s
- **WHEN** 计算 TestResult
- **THEN** totalTime == 0.0 且 segments 为空;**MUST NOT** 输出 53.32s——此断言失败即旧 bug 复发

#### Scenario: 全程静止数据 DNF
- **GIVEN** dataPoints 全部 speed < 1.0 km/h
- **WHEN** 计算 TestResult
- **THEN** totalTime == 0.0,无异常抛出

#### Scenario: SG 统计量在 DNF 时仍可用
- **GIVEN** 未破百的 dataPoints(有真实加减速)
- **WHEN** 计算 TestResult
- **THEN** maxAcceleration/maxDeceleration 仍按 raw 全程计算(非零),仅计时类字段归零

### Requirement: 刹车测试窗口 MUST 为首帧起点至首次刹停终点

`Braking100To0` SHALL 以数据首帧为窗口起点(触发时刻;数据形态从 ~95 km/h 开始,见 design Decision 1b 实施期修订),从头正向**首次**下行过停车阈值 1.0 km/h(prev > 1.0 ≥ curr,插值)为窗口终点;无终点过线 SHALL 判 DNF。

#### Scenario: 刹停后蠕动挪车不计入刹车成绩
- **GIVEN** dataPoints 含 95→0 刹停(3s)后接低速挪车(0→6→0)
- **WHEN** 计算 TestResult
- **THEN** 窗口终点为**首次**下行过 1.0(刹停时刻),totalTime ≈ 3s;挪车段第二次下行过 1.0 MUST NOT 延长成绩

#### Scenario: 未刹停(最低 8 km/h)DNF
- **GIVEN** dataPoints 速度 95→8→巡航
- **WHEN** 计算 TestResult
- **THEN** totalTime == 0.0(无下行过 1.0;旧版此场景走 null 短路返回全程假成绩,本反例锁死回归)

### Requirement: 插值除零与窗口退化 MUST 安全

相邻对 prev.speed == curr.speed(插值 ratio 分母为零)SHALL 跳过该对继续扫描;窗口内样本不足以构成有效窗口(起点时刻 ≥ 终点时刻)SHALL 判 DNF;全程 MUST NOT 抛异常。

#### Scenario: 平台速度段不致崩溃
- **GIVEN** dataPoints 在 100.0 km/h 精确持平数帧后回落
- **WHEN** 计算 TestResult
- **THEN** 无异常;若存在合法过线对则正常出窗口,否则 DNF

#### Scenario: 起点终点倒置判 DNF
- **GIVEN** 构造数据使最后一次 1.0 上穿发生在最后一次 100 上穿之后(理论畸形序列)
- **WHEN** 计算 TestResult
- **THEN** totalTime == 0.0,不产出负值
