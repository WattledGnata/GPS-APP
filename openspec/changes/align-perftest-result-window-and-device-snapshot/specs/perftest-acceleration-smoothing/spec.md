## MODIFIED Requirements

### Requirement: 离线 G 值统计与 UI 曲线 MUST 共用 AccelerationSmoother

`CalculateResultUseCase` 的离线 G 值统计和 `GForceChart` 的曲线序列 MUST 调用 `AccelerationSmoother.compute`。两者 MUST 接收同一个最终成绩窗口 dataPoints；不得保留独立相邻差分，也不得让任一方消费完整原始会话或另一套裁剪范围。

#### Scenario: 统计与曲线逐点对齐

- **GIVEN** 同一份最终窗口 `List<GpsDataPoint>`
- **WHEN** 计算持久化 G 摘要和 UI G 曲线
- **THEN** 两者使用的加速度序列逐点一致
- **AND** 窗口外样本不参与聚合或绘制

#### Scenario: 旧相邻一阶差分代码已移除

- **GIVEN** 实施完成后的结果计算与曲线源码
- **WHEN** 静态检查
- **THEN** 除 `AccelerationSmoother` 内部外不存在直接相邻差分的 G 计算

### Requirement: TestResult / TestRecordEntity 区分 maxAcceleration 与 maxDeceleration

`TestResult` 和 `TestRecordEntity` MUST 同时持有 `maxAcceleration` 与 `maxDeceleration`：前者为最终成绩窗口内正向加速度最大 G，后者为窗口内负向加速度最小值的绝对 G。`avgAcceleration` 也 MUST 仅聚合该窗口。`CalculateResultUseCase` MUST NOT 在统一序列生成阶段对全部加速度做绝对值。

#### Scenario: 完成后的刹车不污染 0–100

- **GIVEN** 一次 0–100 完成后车辆继续采集并出现明显制动
- **WHEN** 计算结果
- **THEN** 0–100 的平均 G 和峰值加速 G 只来自 0–100 窗口
- **AND** 完成后制动不计入该成绩 G 摘要

#### Scenario: 起步前 GPS 尖峰不污染成绩

- **GIVEN** 最终窗口前存在一次异常 G 尖峰
- **WHEN** 计算结果
- **THEN** 尖峰保留在诊断 binary
- **AND** 峰值 G 不包含该尖峰

#### Scenario: 100–0 使用制动方向

- **GIVEN** 一份完整 100–0 最终窗口
- **WHEN** 计算结果
- **THEN** `maxDeceleration` 为窗口内最负加速度的绝对 G
- **AND** UI 不以 `maxAcceleration` 替代制动峰值

