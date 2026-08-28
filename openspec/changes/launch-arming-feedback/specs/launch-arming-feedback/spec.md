# launch-arming-feedback

0-100 起步武装的静止判定口径、状态暴露与就绪播报。

## ADDED Requirements

### Requirement: 静止武装判定 MUST 与成绩起步锚点同口径

加速测试武装(isStartReady)SHALL 要求滤波后速度 < 1.0 km/h(= MOTION_THRESHOLD_KMH)持续 25 帧(1 秒);缓动速度(1.0-3.0 km/h)MUST NOT 通过静止确认。

#### Scenario: 缓动不武装(路测回归锁)
- **GIVEN** 车辆以 2.7 km/h 缓动(2026-06-04 22:36 路测形态)
- **WHEN** 逐帧检查武装
- **THEN** isStartReady 保持 false,任何加速 MUST NOT 触发 startTest——armed 必经真静止,起步数据必含上穿 1.0 锚点

#### Scenario: 真停稳 1 秒后武装
- **GIVEN** 滤波后速度 < 1.0 持续 ≥25 帧
- **WHEN** 第 25 帧到达
- **THEN** isStartReady=true 且 launchArmed flow 发射 true

#### Scenario: 停稳中途蠕动重置计数
- **GIVEN** 静止 15 帧后一帧速度 1.5(蠕动)
- **WHEN** 该帧处理
- **THEN** 计数归零,需重新累计 25 帧

### Requirement: 武装就绪 MUST 即时播报且仅一次

launchArmed 上升沿 SHALL 触发语音"条件就绪,随时可以起步"(叮+TTS);同一次武装 MUST NOT 重复播报;enterSmartLaunch/cancelTest SHALL 复位 armed 状态(下次进入重新武装可再播)。

#### Scenario: 停稳即播报
- **GIVEN** 用户停稳满 1 秒
- **WHEN** armed 翻 true
- **THEN** 播报一次"条件就绪,随时可以起步";继续静止不重复播

#### Scenario: 重进页面不误播
- **GIVEN** 上次测试后重新 enterSmartLaunch
- **WHEN** 进入 Preparing
- **THEN** armed 已复位 false,无播报,直至重新停稳确认

### Requirement: Preparing 态视觉 MUST 区分未就绪/已就绪

countdown 结束后:未 armed SHALL 显示停稳引导(BRING CAR TO A STOP);armed SHALL 显示就绪态(READY TO LAUNCH);MUST NOT 在未 armed 时显示可起步文案。

#### Scenario: 行驶中打开页面看到停稳引导
- **GIVEN** 30 km/h 行驶中进入页面,countdown 走完
- **WHEN** Banner 渲染
- **THEN** 显示停稳引导而非就绪/计时文案
