# Design: unify-speed-judgement-source

## Context

数据流:`gpsDataViewModel.gpsData`(raw 25Hz)→ VM collect(TestSessionViewModel.kt:348-356)→ `gpsDataFilter.process` → `FilteredGpsData{speed/lat/lon/...(滤波后), raw: GpsData}` → `processFilteredData`(写遥测/判停/喂窗口算法)。判停 `template.shouldEnd(filteredData.raw)`(line 686)收 GpsData 整体,只读 `.speed`(TestModels.kt:69-71 加速 `>= 100.0`;刹车对应低速判)。UI 仪表(TrackTechTestExecutionScreen:~105 `gpsData.speed`)直接订阅 raw 流。

## Goals / Non-Goals

**Goals:** 显示/判停/成绩三处同源 filtered;"done ⇒ 有成绩"不变式;DNF 显式可感知(视觉+语音)。
**Non-Goals:** shouldEnd 签名改造;成绩链路改 raw;滤波器调参;历史成绩列表呈现。

## Decisions

### Decision 1: 判停传 `raw.copy(speed = filtered.speed)`,不改 shouldEnd 签名

Alternatives:
- (a) `shouldEnd` 签名改 `speedKmh: Double`:domain 公共模板抽象方法,两个实现 + 调用方 + 既有测试连锁改;收益仅是"更纯"。拒绝。
- (b) copy 替换 speed 字段(选):单行;GpsData 其余字段(shouldEnd 不读)保持 raw 无副作用;与 wire-laptime-to-gps-filter round 的 `cleaned = gpsData.copy(latitude=..., speed=...)`(VM:362-365)同一惯用法。

### Decision 2: VM 暴露 `filteredSpeedKmh: StateFlow<Double>`,collect 链路更新

在 gpsData collect 中 `gpsDataFilter.process` 之后赋值(每帧 25Hz StateFlow 更新——与现有 lapLiveState 同量级,UI 端 Compose collectAsState 自带重组节流,执行屏仅一个 Text 消费,无性能新增项)。

Alternatives:
- (a) UI 自己再调 GpsDataFilter:filter 有状态(median 窗口),双实例双状态漂移。拒绝。
- (b) 复用 lapLiveState:那是圈速屏聚合态,执行屏(性能测试)不订阅它,语义不符。拒绝。
- (c) 独立 StateFlow(选):一条赋值,显示与判停严格同帧同源。

### Decision 3: DNF 判定口径 = `Completed && result.totalTime <= 0.0`

DNF 表征沿 fix-accel-last-crossing Decision 3(空结果 totalTime=0;TestResult 无显式 isDnf 字段,加字段涉 result 序列化/Room,strict-schema 例外)。UI/语音用 totalTime<=0 判:
- PhaseBanner Completed 分支:totalTime<=0 → ("DNF", "NOT COMPLETED", "Target speed not reached");否则原 "ACCELERATION/BRAKING DONE"。
- 播报:totalTime>0 → 原成绩播报;<=0 → `announceTestNotCompleted()`("叮"+"测试未完成")。
- Alternative(TestResult 加 isDnf 字段):Room schema/序列化连锁,本 round 非必需,backlog 与 perftest-dnf-ui 合并考虑。拒绝。

## Risks / Trade-offs

- **仪表滞后 ~160ms**(median 半窗):行车读数视觉无感(Dragy 等也有滤波);换来的所见即所得收益远大。
- **真实瞬时过线被滤波削顶**(峰宽 <180ms):新口径下仪表/成绩一致显示"未到 100"——以滤波后为准是稳健选择(raw ±1 km/h 噪声下"100.3"可能真实 99.5);用户语义自洽,无认知撕裂。透明声明:这意味着压线一瞬即松油的开法可能判 DNF,需保持 100+ 约 0.2s。
- **25Hz StateFlow 更新**:Compose 智能重组只刷一个 Text;若实测掉帧,UI 端可加 sample 节流(LapLiveScreen 已有同款模式)。
