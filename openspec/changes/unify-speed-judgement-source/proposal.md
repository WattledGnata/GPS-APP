# Proposal: unify-speed-judgement-source

## Why

2026-06-04 22:37 路测(fix-accel-last-crossing 修复后首验):0-100 测试**仪表显示已过 100 km/h、页面显示 done,但成绩 0.00s、session 最高尾速 99**。日志实锤:`perfResult window: total=0.00s points=0/694 dnf=true`——滤波后数据确实无 ≥100 帧,DNF 判定本身正确,**错在三处速度口径分裂**(fix-accel-last-crossing design Risks 已预言的 backlog 场景,本次病发):

| 环节 | 当前速度源 | 后果 |
|---|---|---|
| 仪表显示(TrackTechTestExecutionScreen BigSpeedDisplay) | **raw**(`gpsViewModel.gpsData.speed`) | 用户看到 100+ |
| 测试判停(TestSessionViewModel.kt:686 `shouldEnd(filteredData.raw)`) | **raw** | raw 瞬时 ≥100 → 立即"done"并停止采集 |
| 成绩计算(CalculateResultUseCase,dataPoints) | **filtered**(GpsDataFilter 9 点 median) | 滤波滞后(半窗 ~160ms)+短峰削顶 → 最高 99 → DNF |

机理:raw 触发判停的瞬间立即截断采集,filtered 的 median 滞后还没爬到 100 就没有后续数据了——**filtered 永远追不上**,"done 却 DNF"是结构性必然而非偶发。

附加体验问题:DNF 时页面仍显示 "ACCELERATION DONE" + DONE 按钮 + 成绩 0.00s,语音(本日同捆修复接线后)会播"零点零零秒"——用户无从知道"未完成"。

## What Changes

- **判停统一 filtered**:`shouldEnd(filteredData.raw)` → `shouldEnd(filteredData.raw.copy(speed = filteredData.speed))`(单行;domain 签名不动)。滤波滞后使判停晚 ~160ms,采集多收几帧,窗口算法(同 filtered 源)必然取到 ≥100 帧——"done 必有成绩"不变式成立。
- **仪表显示统一 filtered**:VM 暴露 `filteredSpeedKmh: StateFlow<Double>`,执行屏 BigSpeedDisplay 改用——所见即所得(看到 100 = 成绩用的就是 100;真实短峰被滤波削顶时仪表也只显示 99,用户预期一致)。
- **DNF 显式 UI**:Completed 且 `result.totalTime <= 0` → PhaseBanner 显示 "NOT COMPLETED / DNF"(替代 "ACCELERATION DONE");语音播 "测试未完成"(VoiceAnnouncer 新增 announceTestNotCompleted)而非 "零点零零秒"。
- 触发判定(checkAccelerationTrigger)已用 filtered,零改动(核查记录)。

非目标:成绩链路改 raw(GPS 噪声尖刺会产生假成绩,比削顶更糟);GpsDataFilter 参数调整(9 点 median 是抗离群既定设计);历史 result 列表 0.00s 呈现(backlog perftest-dnf-ui 累积)。

## Capabilities

### New Capabilities
- `speed-judgement-source-unification`: 测试链路速度口径统一(显示/判停/成绩同源 filtered)与 DNF 显式表达。

### Modified Capabilities
<!-- 无:perftest-timing-window 的窗口语义不变(它一直用 filtered,本 round 是让上游判停与显示对齐它) -->

## Impact

- **代码**:`TestSessionViewModel.kt`(shouldEnd 调用处 1 行 + filteredSpeedKmh flow);`TrackTechTestExecutionScreen.kt`(速度源 + DNF Banner + DNF 播报分支);`VoiceAnnouncer.kt`(announceTestNotCompleted)。
- **不碰**:TestTemplate.shouldEnd 签名、GpsDataFilter、CalculateResultUseCase、Room。
- **行为变化**:仪表读数比 raw 滞后 ~160ms(视觉无感);raw 噪声尖峰不再触发假 done;真实短峰(<半窗)过线在新口径下显示与成绩一致为"未到 100"(以滤波后为准是稳健选择,GPS raw 噪声 ±1 km/h 下 raw 100.3 可能真实 99.5)。
