# Tasks: unify-speed-judgement-source

## 1. 实现

- [x] 1.1 锚点:`grep -n "shouldEnd(filteredData.raw)" feature/test/.../TestSessionViewModel.kt`(line ~686);`grep -n "%.0f\".format(speed)" .../TrackTechTestExecutionScreen.kt`(BigSpeedDisplay)。
- [x] 1.2 判停:`shouldEnd(filteredData.raw.copy(speed = filteredData.speed))`(Decision 1)。
- [x] 1.3 VM 加 `filteredSpeedKmh: StateFlow<Double>`,gpsData collect 内 process 后赋值(Decision 2)。
- [x] 1.4 执行屏速度源切 `sessionViewModel.filteredSpeedKmh`;computeProgressState 的 speed 入参同步切换(进度条与仪表同源)。
- [x] 1.5 DNF UI:PhaseBanner Completed 分支按 totalTime<=0 分流文案(Decision 3);播报分支:totalTime>0 播成绩,否则 `announceTestNotCompleted()`(VoiceAnnouncer 新增,"叮"+"测试未完成")。
- [x] 1.6 编译 + `:feature:test:testDebugUnitTest` 全绿(computeProgressState 等既有单测若有 speed 语义断言则核对)。

## 2. 自审 gate(road-test-first)

- [x] 2.1 单遍自审 + #14/#16 自查(无 DAO/无共享字段,预期空命中);checkAccelerationTrigger 已 filtered 的核查记录。

## 10. Follow-up backlog

- `perftest-dnf-ui`(累积):历史成绩列表对 totalTime=0 行的呈现(标 DNF 而非 0.00s)。
- 仪表 25Hz StateFlow 若实测重组掉帧 → UI 端 sample 节流(LapLiveScreen 同款)。
