# Tasks: launch-arming-feedback

## 1. 实现

- [x] 1.1 锚点:`grep -n "STANDSTILL_SPEED_THRESHOLD" TestSessionViewModel.kt`(line ~134 定义 + checkAccelerationTrigger 使用)。
- [x] 1.2 常量:STANDSTILL_SPEED_THRESHOLD = MOTION_THRESHOLD_KMH(import core/domain 顶层常量,Decision 1);STANDSTILL_CONFIRMATION_COUNT 3 → 25。
- [x] 1.3 VM:`launchArmed: StateFlow<Boolean>`;isStartReady=true 处同步置 true;enterSmartLaunch/cancelTest 复位 false(Decision 2)。
- [x] 1.4 VoiceAnnouncer:`announceLaunchReady()`("叮"+"条件就绪,随时可以起步")。
- [x] 1.5 执行屏:`LaunchedEffect(launchArmed)` 上升沿播报;PhaseBanner 加 launchArmed 入参,Preparing/countdown==0 分流三态文案(Decision 3)。
- [x] 1.6 编译 + `:feature:test:testDebugUnitTest` 全绿;触发链路若有既有单测断言 3.0/3 帧则同步修订(commit body 说明)。

## 2. 自审 gate(road-test-first)

- [x] 2.1 单遍自审 + #14/#16 自查(预期空命中);armed 后挪车误入 Running 的窗口算法兜底逻辑核查记录。

## 10. Follow-up backlog

- 刹车测试(95-105 巡航武装)的对称就绪播报("速度区间就绪,可以刹车")——交互文案待用户定。
