# Design: launch-arming-feedback

## Context

smart launch 链路(TestSessionViewModel):`enterSmartLaunch`(482,重置 isStartReady/standstillCount + Preparing + 5s countdown)→ `processFilteredData` Preparing 分支(countdown==0 后逐帧 `checkTriggerCondition`)→ `checkAccelerationTrigger`(704-736):未 armed 时静止确认(speed < 3.0 连续 3 帧 → isStartReady=true),armed 后 `accel>0 || speed>1.0` 连续 5 帧 → startTest。isStartReady 为私有 var,无任何对外暴露。播报全在 UI 层(V2 执行屏 LaunchedEffect,本日接线)。

成绩窗口算法(fix-accel-last-crossing)起步锚点 = 上穿 MOTION_THRESHOLD_KMH(1.0,filtered)。

## Goals / Non-Goals

**Goals:** 静止判定与成绩起步锚点同口径;armed 即时语音+视觉反馈;"armed 后起步 ⇒ 有成绩窗口"不变式。
**Non-Goals:** armed 解除(挪车再停,窗口算法取最后完整段天然兜底);countdown 机制;刹车触发(无静止概念)。

## Decisions

### Decision 1: 静止阈值 1.0 km/h + 确认按数据时间窗 1000ms

**实施期修订(2026-06-04 夜,#17)**:初稿"25 帧确认"隐含 25Hz 帧率假设——5Hz 模拟器回放下 25 帧 = 5 秒,用户拖 0 一两秒永不武装,模拟器验证路径被锁死(用户实测反馈)。改为 `filteredData.timestamp` 时间窗判定(持续 <1.0 满 1000ms),任意帧率语义一致;中途 ≥1.0 重置窗口起点。

Alternatives:
- (a) 维持 3.0/3 帧:2.7 缓动 armed → 起步锚点缺失 → 结构性 DNF(路测实锤)。拒绝。
- (b) 0.5 km/h:低于 GPS 静止噪声下沿,部分设备静止读数 0.6-0.8 永不 armed。拒绝。
- (c) 1.0 / 25 帧(选):与 MOTION_THRESHOLD_KMH 单一口径(armed ⇒ filtered <1.0 持续 1s ⇒ 起步必产生上穿 1.0 锚点);1 秒确认排除蠕动间隙(120ms 旧值形同虚设);Dragy 类产品同级体感。
- 常量引用方式:直接改字面值并注释引用 MOTION_THRESHOLD_KMH(core/domain 顶层常量,feature 可 import——直接 `= MOTION_THRESHOLD_KMH` 引用,单一真理源)。

### Decision 2: armed 暴露为 `launchArmed: StateFlow<Boolean>`,播报在 UI 层

VM:`_launchArmed.value = true` 与 isStartReady=true 同步;enterSmartLaunch/cancelTest 复位 false。UI:`LaunchedEffect(launchArmed)` 上升沿播 `announceLaunchReady()`。

Alternatives:
- (a) VM 内直接调 VoiceAnnouncer:VM 需注入 announcer(构造变更),且播报职责现全在 UI 层(本日接线格局),分裂两层。拒绝。
- (b) UI 层监听 StateFlow 上升沿(选):零构造变更;enterSmartLaunch 每次复位 false ⇒ 重进页面不误播;Compose LaunchedEffect(Boolean) 天然只在值变化时重跑。

### Decision 3: Preparing Banner 三态文案

`countdown > 0` → "PREPARING / COUNTDOWN n"(原);`countdown == 0 && !armed` → "STOP / BRING CAR TO A STOP / Hold still for 1 second"(引导);`armed` → "ARMED / READY TO LAUNCH / Floor it when ready"。PhaseBanner 加 launchArmed 参数。

Alternative(只播报不改视觉):驾驶位看一眼屏幕仍无状态区分,弱听觉环境(开窗/音响)缺兜底。拒绝。

## Risks / Trade-offs

- **1 秒确认的体感延迟**:停稳后需等 1s 才播就绪——比旧 120ms"慢",但旧值是假武装;1s 是真实可感知的"系统确认你停稳了",符合用户预期(其原话"第一次达到类似静止条件的时候播报一次")。
- **GPS 静止漂移 >1.0**:恶劣信号下静止读数偶尔 >1.0 会重置计数 → armed 延迟;filtered(9 点 median)已显著抑制,残余风险接受(信号差时成绩本身也不可信)。
- **armed 后挪车误入 Running**:误触发后窗口算法取最后完整起步段,成绩正确;唯计时显示从挪车起跑(视觉小瑕疵),透明声明接受。
