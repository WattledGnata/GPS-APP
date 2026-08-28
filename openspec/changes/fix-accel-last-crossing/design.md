# Design: fix-accel-last-crossing

## Context

`CalculateResultUseCase`(core/domain/src/main/java/com/blazepush/core/domain/usecase/CalculateResultUseCase.kt)从 `TestSession.dataPoints`(25Hz、GpsDataFilter 滤波后、等间距 40ms)计算 `TestResult`。当前链路:

- `invoke`(line 21-66):SG 加速度(raw 点上)→ `correctTimingPoints` → totalTime/totalDistance/segments。
- `correctTimingPoints`(line 73-97):`findPrecisePoint(start)` + `findPrecisePoint(end)` 双双非 null 才裁剪;任一 null → `return dataPoints`(全程,line 86)。
- `findPrecisePoint`(line 102-133):**从头正向**扫第一次过线(first-crossing),0 阈值时条件恒假(速度非负)。

路测实锤(proposal Why):0-100 走 null 短路 → totalTime = session 全时长 53.32s;raw 判停叠加导致 99.0 km/h 未破百也"完成"。

数据形态约束:dataPoints[i].speed 已是滤波后 km/h;elapsedTime 单位秒(相对 session 起点);session 从触发(speed>1.0 连续 5 帧,TestSessionViewModel:701-725)开始累积,因此**数据头部天然包含起步前蠕动段**。

## Goals / Non-Goals

**Goals:**
- 0-100 成绩 = 最后一次有效冲刺的干净窗口时长(last-crossing 回溯)。
- 未真正过终点线 → DNF(空结果),杜绝假成绩。
- 100-0 对称修复。
- 全部窗口边界由单测反例锁死。

**Non-Goals:**
- `shouldEnd(raw)` 改 filtered 判停(DNF fallback 已兜恶果;触发余量调参独立观察,§10 backlog)。
- `TestResult` 加 DNF 显式字段 / UI DNF 标识(涉及 result 序列化与 UI,backlog)。
- 历史已存成绩回算。
- 自定义速度区间测试(60-160 等,见 memory `project_configurable_speed_interval_tests`,未立项)。

## Decisions

### Decision 1: 窗口提取算法 = 单次正向状态机取最后一个完整(起步→首次过线)候选段

算法(加速 0→100;刹车 Decision 1b 镜像):

1. 正向遍历 dataPoints,维护状态机:`DISARMED`(速度 ≥ 运动阈值起算前)/`ARMED`(速度 < 1.0,武装起步锚点)/`LAUNCHED`(上行过 1.0,记起步插值点)。
2. `LAUNCHED` 中上行过 `endSpeed`(prev<end ≤curr)→ 关闭候选段(起步插值点 → 过线插值点),回到等待状态;速度掉回 <1.0 → 重置为 `ARMED`(下次上穿开新候选,旧未完成锚点废弃)。
3. 取**最后一个完整候选段**为成绩窗口;零完整候选 → null(DNF,Decision 3)。

三场景一致性验证(本决策的选型依据,单测一一对应):

| 场景 | 状态机结果 | 正确性 |
|---|---|---|
| 多次蠕动后冲刺 | 蠕动回合掉回 <1.0 废弃锚点,冲刺段为唯一完整候选 | ✓ 剔冗余 |
| 回落再破百(0→102→95→103) | 一次起步,**首次**过 100(102)关闭候选;95→103 未掉回 1.0 不开新段 | ✓ 物理口径(Dragy 同:首次触线停表) |
| 过线后停车挪车 | 冲刺候选已完整;挪车起步无过线,不产新候选 | ✓ 成绩不被挪车毁掉 |

Alternatives:
- (a) **first-crossing(现状)**:路测证伪——蠕动冗余全计入;且 0 阈值 dead code。拒绝。
- (b) **终点 last-crossing + 起点反向回溯**(初稿方案):回落再破百场景把回落段计入(终点错取 103 那次,而 0-100 物理语义是首次触线即停表);若起点改用全局最后上穿,过线后挪车场景又误判 DNF——三场景不自洽,自审推演否决。拒绝。
- (c) **最长/最快段全局搜索**(所有完整段取最短):语义是"最好成绩"不是"最后一次尝试";与 (d) 相比无场景增益,实现面更大。拒绝。
- (d) **正向状态机取最后完整段(选)**:三场景全自洽;一次 O(n) 正向遍历;"最后一次有效尝试"与用户意图(剔起步前冗余)一致——用户说的"从最后过线回溯"指向的是剔蠕动,本方案达成该效果且不引入回落段误差。

### Decision 1b: 刹车窗口 = 数据首帧起点 + 首次刹停终点(实施期修订)

**实施期决策修订(2026-06-04,#17 条款)**:初稿"下行过 100 开段"的镜像状态机在真实数据形态下**恒 DNF**——刹车触发条件为"从准备状态减速低于 95"(TestModels.kt:77 注释),session dataPoints 从 ~95 km/h 开始,**不存在 >100 的帧**,下行过 100 的相邻对永不出现。apply 期 Test2 数据推演(首帧恰为 100.0,strict `>` 不成立)暴露此缺陷。

修订后语义:窗口起点 = **数据首帧**(即触发时刻,与旧版实际行为一致);终点 = 从头正向**首次**下行过停车阈值 1.0 的插值时刻;无终点 → DNF。

- 保留的修复价值:终点语义修复(旧版"下行过 0"恒假 → 全程 fallback;现在挪车不延长成绩、未刹停 DNF)。
- 放弃的部分(透明声明):成绩仍从触发点(~95)起算而非物理 100——这是**数据采集起点**问题(需 Ready 态预缓冲,触发后把 100→95 段缓冲并入),超出窗口算法能力,列 §10 backlog `braking-prebuffer-from-ready`。
- Alternatives:(a) 开段阈值降为 min(100, 数据最高速)——成绩口径随数据漂移,不可比,拒绝;(b) 保持初稿镜像状态机——恒 DNF,拒绝;(c) 首帧起点+首次刹停终点(选)——终点修复落地,起点不劣于旧版。

### Decision 2: 运动阈值 1.0 km/h,模板 0 哨兵仅在算法内替换

`effectiveStart = max(template.startSpeed, LAUNCH_THRESHOLD_KMH=1.0)`(加速);`effectiveEnd = max(template.endSpeed, 1.0)`(刹车)。`TestTemplate.startSpeed/endSpeed` 字段与 UI 显示零改动。

Alternatives:
- (a) **0.5 km/h**:更贴近物理 0,但落在 GPS 静止噪声带(0-2 km/h,见 TrackTechTestExecutionScreen.kt:55 注释)内部,蠕动噪声反复上穿会让"最近一次起步"锚点抖动。拒绝。
- (b) **3.0 km/h(复用 UI WAITING 阈值)**:躲开噪声最稳,但 0→3 km/h 真实起步段(~0.2-0.3s)被剔除,成绩系统性偏短,与 Dragy/RaceBox 等同类工具口径(≈0.5-1 km/h 起算)不可比。拒绝。
- (c) **1.0 km/h(选)**:噪声带上沿与起步口径的平衡;与触发判定 `speed > 1.0`(TestSessionViewModel:701-725)同阈,语义一致。
- 触发器协同:trigger 要求 speed>1.0 连续 5 帧才开 session,而窗口回溯锚点是**窗口内最后一次** 1.0 上穿,两者不冲突(trigger 决定数据从哪开始存在,窗口决定成绩从哪算)。

### Decision 3: 无完整窗口 → null → 空结果(DNF)

`correctTimingPoints` 签名改为返回 `List<GpsDataPoint>?`;null 时 `invoke` 走现有 `emptyResult` 形态(totalTime=0、segments 空、dataPoints 空;SG 三项 maxAcc/maxDec/avgAcc 保留——它们在 raw 点上计算,对"没完成但跑了"仍有参考价值)。

Alternatives:
- (a) **保持 return dataPoints(现状)**:53.32s 假成绩之源,路测证伪。拒绝。
- (b) **抛异常**:调用方 `finishTest`(TestSessionViewModel:1022-1042)无 catch,会崩 UI;且"未破百"是正常用户场景不是程序错误。拒绝。
- (c) **null → 空结果(选)**:复用 emptyResult 既有形态,Room `test_records` 零 schema 改动;totalTime=0.00 在 result 屏可辨识(显式 DNF UI 标识 backlog)。

### Decision 4: 分段计算零改动

`calculateSegments`/`calculateSegment`(line 191-261)继续在 `correctedPoints` 上 `indexOfFirst`:窗口已裁剪为单调冲刺段,first==last,语义自动正确。唯一注意:`isLastSegment=true` 的 90-100 段以窗口末点(=preciseEnd)收尾,与窗口终点一致。

Alternative(同步改为窗口感知的反向扫描):无收益——窗口内 first-crossing 即正确语义;改动面翻倍。拒绝。

### Decision 5: 日志锚点放调用方 VM 层

core/domain 是纯 Kotlin 模块(无 Android/FileLogger 依赖,本文件 import 列表零 Android 引用)。窗口摘要日志在 `TestSessionViewModel.finishTest`(line 1022-1042)落:`FileLogger.d(TAG, "perfResult window: total=Xs dist=Ym points=N/M dnf=bool")`,N/M=窗口内/全部点数——路测一眼看出剔除量。

Alternative(core/domain 加日志接口注入):为一条日志引入接口与 DI 改动,过度设计。拒绝。

## Risks / Trade-offs

- **窗口边界单点/压线**:恰好 `curr.speed == endSpeed` 的相邻对、窗口内不足 2 点等边界 → 单测反例锁(specs R2 反例 + tasks 边界用例);插值除零(prev.speed==curr.speed 时 ratio NaN)→ 该对跳过继续反向扫,单测覆盖。
- **行为变化用户感知**:旧的"假完成"测试在新版变 0.00s——这是修复目的本身;result 屏 totalTime=0.00 的呈现可读但不优雅,DNF UI 标识列 backlog,风险接受。
- **raw 判停残留**(Non-Goal):raw 噪声触发 shouldEnd 早停,若 filtered 已过线则窗口正常、成绩正确;若未过线则 DNF。两种结局都无假成绩。残留影响=用户可能偶遇"明明仪表破百却 DNF"(raw 与 filtered 分歧帧),概率低、日志可诊断(Decision 5 锚点),backlog 观察。
- **road-test-first 模式**:无 Codex/Opus review 网;以"算法纯函数 + 全场景单测 + VM 日志锚点"对冲,apply 期跑 #3/#14/#16 自查(本 round 无 DAO/无共享 entity 字段扩展,#14/#16 预期空命中)。
