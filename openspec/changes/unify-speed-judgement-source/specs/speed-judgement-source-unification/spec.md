# speed-judgement-source-unification

测试链路速度口径统一(显示/判停/成绩同源 filtered)与 DNF 显式表达。

## ADDED Requirements

### Requirement: 测试判停 MUST 使用滤波后速度

`TestSessionViewModel` 调用 `template.shouldEnd` 时 SHALL 传入以 `FilteredGpsData.speed` 替换 speed 字段的 GpsData;raw 瞬时速度 MUST NOT 单独触发测试完成。

#### Scenario: raw 尖峰不触发假 done(路测回归锁)
- **GIVEN** raw 速度单帧尖到 100.3 而滤波后速度 99.1(2026-06-04 22:37 路测形态)
- **WHEN** 该帧进入判停
- **THEN** shouldEnd 收到 99.1,测试继续采集——MUST NOT 出现"done 却 DNF"

#### Scenario: filtered 过线正常判停且成绩有效
- **GIVEN** 滤波后速度持续爬升过 100
- **WHEN** 判停触发(filtered ≥ 100)
- **THEN** 采集数据必含 ≥100 帧 → 窗口算法产出正成绩("done ⇒ 有成绩"不变式)

#### Scenario: 触发判定已同源(核查记录)
- **GIVEN** checkAccelerationTrigger 现状
- **WHEN** 核查其速度源
- **THEN** 已使用 FilteredGpsData(零改动,记录于此防回归)

### Requirement: 执行屏仪表 MUST 显示滤波后速度

`TrackTechTestExecutionScreen` 的速度仪表 SHALL 订阅 VM 暴露的滤波后速度流;显示值与判停/成绩 MUST 同源——用户所见即成绩所算。

#### Scenario: 所见即所得
- **GIVEN** 滤波后 99.1 / raw 100.3 的分歧帧
- **WHEN** 仪表渲染
- **THEN** 显示 99(用户看到未过线,与最终成绩/判停一致,无认知撕裂)

### Requirement: DNF MUST 显式可感知

测试 Completed 且 `result.totalTime <= 0.0` 时:页面 SHALL 显示未完成状态(MUST NOT 显示 "DONE" 成功文案);语音 SHALL 播报"测试未完成"(MUST NOT 播报"零点零零秒")。

#### Scenario: DNF 视觉态
- **GIVEN** 未过线测试结束(totalTime=0)
- **WHEN** Completed 渲染
- **THEN** PhaseBanner 显示 DNF/NOT COMPLETED 文案

#### Scenario: DNF 语音态
- **GIVEN** 同上
- **WHEN** Completed 播报触发
- **THEN** 播"测试未完成";正成绩照常播报数值
