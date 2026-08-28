# Proposal: launch-arming-feedback

## Why

2026-06-04 夜路测(用户原述):行驶中打开 0-100 页面,"速度第一次为零就开始读秒",进度条 50-60 km/h 才动,全程不知道系统何时就绪——期望**第一次达到静止条件时播报一次"条件就绪,随时可以起步"**。

日志实锤(22:36:32 `startTest: firstPoint speed=2.7`):测试在车速 2.7 km/h 缓动时就触发 Running。代码级根因(TestSessionViewModel):

1. `STANDSTILL_SPEED_THRESHOLD = 3.0`(line 134):2.7 缓动被判"静止",armed(`isStartReady`)直接通过——**与成绩窗口算法的起步阈值 1.0(MOTION_THRESHOLD_KMH,fix-accel-last-crossing Decision 2)不对齐**:armed 时车没真停,数据里无上穿 1.0 的起步锚点 → 该次测试结构性 DNF(22:37 DNF 的第二根因,叠加滤波口径问题)。
2. `STANDSTILL_CONFIRMATION_COUNT = 3`(120ms):蠕动间隙即可确认,形同虚设。
3. **armed 翻 true 瞬间零反馈**:`isStartReady` 是私有 var,UI/语音都不知道——用户不知道"要先停稳"、更不知道"已就绪可起步"。

## What Changes

- **静止判定对齐成绩口径**:`STANDSTILL_SPEED_THRESHOLD` 3.0 → 1.0(= MOTION_THRESHOLD_KMH);`STANDSTILL_CONFIRMATION_COUNT` 3 → 25(1 秒 @25Hz,真停稳)。armed ⇒ 数据必然产生上穿 1.0 的起步锚点 ⇒ "armed 后起步必有成绩窗口"链条闭合。
- **armed 状态暴露**:VM 新增 `launchArmed: StateFlow<Boolean>`(isStartReady 翻转同步);`enterSmartLaunch`/`cancelTest` 复位。
- **armed 播报**:UI 层 `LaunchedEffect(launchArmed)` 翻 true 时播"条件就绪,随时可以起步"(VoiceAnnouncer 新增 `announceLaunchReady`,叮+语音;每次 armed 仅一次)。
- **Preparing 态 Banner 文案分流**:countdown>0 → 原 COUNTDOWN;countdown==0 且未 armed → "BRING CAR TO A STOP"(引导停稳);armed → "READY TO LAUNCH"(随时起步)。

非目标:armed 后挪车的解除武装(armed 后任何起步都进 Running,成绩窗口算法取最后完整起步段天然兜底,误触发 Running 无害);countdown 机制本身(进页面 5 秒准备期保留);刹车测试触发(95-105 巡航判定,无静止概念,不动)。

## Capabilities

### New Capabilities
- `launch-arming-feedback`: 0-100 起步武装的静止判定口径、状态暴露与就绪播报。

### Modified Capabilities
<!-- 无:perftest-timing-window 不变(本 round 让武装与其起步锚点对齐);smart-launch 无既有 spec -->

## Impact

- **代码**:`TestSessionViewModel.kt`(2 常量 + launchArmed flow);`TrackTechTestExecutionScreen.kt`(Banner 分流 + armed 播报);`VoiceAnnouncer.kt`(announceLaunchReady)。
- **行为变化**:缓动(1.0-3.0 km/h)不再被当静止——用户必须真停稳 1 秒才 armed(播报告知);armed 前任何动作不触发测试(防 2.7 误触发);停稳到起步的用户动线由语音引导,无需看屏。
