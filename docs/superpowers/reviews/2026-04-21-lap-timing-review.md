# 圈速测试功能 Review（2026-04-21）

> 分支：`feature_ctg_20260405_laptime_mainline`
>
> 目的：把当前圈速链路的设计意图、数据模型、核心算法、集成路径、UI、测试覆盖和已知裂缝系统梳理一次，作为后续评审与修复决策的依据。所有陈述都附代码证据。

---

## 一、设计定位与边界

- **归属**：圈速所有代码位于 `feature/test` 模块下，独立分层 `model/laptiming`、`model/track`、`repository`、`usecase`，不侵入主流程。符合"避免污染现有代码"的边界。
- **数据源边界**：圈速只消费 GPS（`GpsSample`），**不碰 BLE 协议字段**。`GpsData → GpsSample` 的转换只在 ViewModel 发生一次：
  - `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:354-360`
- **赛道来源**：第一阶段锁定单一预置赛道 `preset-tfic-lpcc`（TFIC LPCC）。回放资源可把它"几何对齐"成 `Generated` 来源，但不扩展到多赛道。

已固化的停靠点文档：

| 文档 | 内容 |
|---|---|
| `docs/superpowers/plans/2026-03-26-track-based-lap-timing-architecture.md` | 主架构 |
| `docs/superpowers/specs/2026-04-03-track-laptiming-page-first-stage-design.md` | 首阶段页面 |
| `docs/superpowers/plans/2026-04-03-replay-lap-timing-closure-plan.md` | 回放闭环计划 |
| `docs/superpowers/plans/2026-04-05-tfic-rcz-track-geometry-alignment-plan.md` + `specs/2026-04-05-tfic-rcz-track-geometry-alignment-design.md` | 几何对齐 |
| `docs/superpowers/plans/2026-04-04-lap-debug-timing-card-reset-implementation.md` + `specs/2026-04-04-lap-debug-timing-card-reset-design.md` | 起终点计时卡片 reset |

---

## 二、数据模型（全部不可变 `data class` / `enum class`）

### 轨道侧（`model/track/`）

| 文件 | 关键字段 |
|---|---|
| `Track.kt:3-11` | `id, name, layoutName, source, referencePath, startFinishGate, sectorGates` |
| `TimingGate.kt:3-11` | `id, name, type, line, passDirection, sequenceIndex, minDirectionalSpeedMps` |
| `GeoLine.kt` + `GeoPoint.kt` + `GeoVector.kt` | 纯几何值对象 |
| `TrackSource.kt` | `Preset / Remote / Generated` |
| `TimingGateType.kt` | `StartFinish / Sector` |
| `TrackPath.kt:3-6` | `points: List<GeoPoint>, closed: Boolean = true` |

### 圈速侧（`model/laptiming/`）

| 文件 | 关键点 |
|---|---|
| `LapSession.kt:3-14` | 会话聚合根：`status, samples, currentLapIndex, nextExpectedGateIndex, crossingEvents, completedLaps, activeLap` |
| `ActiveLap.kt:3-9` | 进行中的圈：`lapIndex, startedAtMillis, passedGateIds, sectorEntries, sampleStartIndex` |
| `LapRecord.kt:3-15` | 已完成圈：`durationMillis, sectorTimes, trajectory, crossingEvents, qualityFlags` |
| `CrossingEvent.kt:5-14` | 每次穿线事件（无论是否 accepted 都记录） |
| `CrossingReason.kt:3-10` | `Accepted / WrongDirection / UnexpectedGateOrder / TooSlow / Cooldown / NoIntersection` |
| `LapQualityFlag.kt:3-8` | `LowAccuracy / SparseSamples / SuspectedJitter / IncompleteSectors` |
| `LapSessionStatus.kt:3-9` | `Idle / Ready / Recording / Finished / Cancelled` |
| `GpsSample.kt:3-11` | 圈速内部 GPS 快照（与 `core.domain.model.GpsData` 解耦） |

### UI/配置侧

- `model/LapRunConfig.kt`：`trackId + viewOptions`
- `model/LapViewOptions.kt`：`showReferencePath / showTimingGates / showTrajectory / showCrossingDebug`

---

## 三、核心算法：`GateCrossingDetector`

文件：`feature/test/src/main/java/com/blazepush/feature/test/usecase/GateCrossingDetector.kt`

**三步过滤**（`GateCrossingDetector.kt:20-73`）：

1. **线段相交**：`segmentsIntersect` 用 2D 叉积参数化（`GateCrossingDetector.kt:75-99`）判 `(prev→cur)` 与 `gate.line` 是否相交。
   - ⚠️ **坐标直接用经纬度做欧氏相交**，未做墨卡托投影或球面修正。TFIC 纬度（~30.49°N）尺度误差可忽略，多赛道/高纬度迁移时需升级。
2. **方向校验**：`directionScore = movement · passDirection`（点积）。≤ 0 → `WrongDirection`。（`GateCrossingDetector.kt:41-53`）
3. **速度下限**：`directionalSpeedMps = directionScore / dt`；小于 `gate.minDirectionalSpeedMps` → `TooSlow`。（`GateCrossingDetector.kt:55-65`）

> 当前 TFIC preset 所有门 `minDirectionalSpeedMps = null`（`PresetTracks.kt:43,56,68`），速度下限门槛在生产里尚未启用。
>
> `CrossingReason.Cooldown` 已定义（`CrossingReason.kt:8`），但 `GateCrossingDetector` 和 `LapTimingEngine` 内**没有任何生产者**。属于预留项。

---

## 四、状态机：`LapTimingEngine`

文件：`feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt`

`processSample(session, track, previousSample, currentSample) → LapSession` 是纯函数，每样本产生新 session。

### 主分支

`LapTimingEngine.kt:22-54`

1. **优先检测起终点穿线**。起终点一旦 accepted，不再检查扇区门——避免圈尾跨越起终点前被 sector 误触发。
2. 若起终点未穿线，查 `expectedGate(track, nextExpectedGateIndex)`（`LapTimingEngine.kt:200-201`），按 `sequenceIndex` 升序取下一个期待扇区门。

### 起终点穿线（`LapTimingEngine.kt:56-126`）

- **首次 accepted**：建立 `ActiveLap(lapIndex=1)`、`nextExpectedGateIndex=1`，状态升 `Recording`。
- **再次 accepted**：关闭当前圈生成 `LapRecord`，开新 `ActiveLap`。
- **缺扇区也强制闭圈**，但打 `IncompleteSectors` 质量标记。
  - 规则：`qualityFlags = if (activeLap.sectorEntries.size == track.sectorGates.size) empty else [IncompleteSectors]`（`LapTimingEngine.kt:93-97`）
  - 由 2026-04-04 `lap-timing-start-finish-closure-fix` 固化，测试锁定于 `LapTimingEngineTest.processSample_missingSectorStillCompletesLapWithIncompleteFlag` 与 `...outOfOrderSectorIsIgnoredAndLapStillClosesOnNextStartFinish`。
  - **⚠️ 勘误（2026-04-22 对抗 review 3.4）**：以上两条测试覆盖的是"部分穿扇区"和"乱序穿扇区"路径。"两次起终点中间完全不穿任何 sector"的纯路径只由 `processSample_secondStartFinishCrossing_completesLapEvenWithoutAllSectors` 覆盖，而**该测试未断言 `qualityFlags`**。补断言前不能把"完全无扇区闭圈 → IncompleteSectors"视为已锁定不变量。修复详见对抗 review 1.8。

### 扇区穿线（`LapTimingEngine.kt:128-198`）

- 先扫"非期待门是否先被穿"——若是，记一条 `accepted=false, reason=UnexpectedGateOrder` 的事件，**不推进 `nextExpectedGateIndex`**（`LapTimingEngine.kt:139-161`）。
- 再对期待门 detect，accepted 才推进并写 `SectorEntry`（`LapTimingEngine.kt:186-197`）。
- 扇区时长用 `SectorEntry.crossedAtMillis` 相邻差值，首段起点是 `ActiveLap.startedAtMillis`（`LapTimingEngine.kt:203-210`）。

### 关键不变量（由测试锁定）

- 一圈 = 两次 accepted 起终点穿线之间（`LapTimingEngineTest.kt:54-75`）
- 扇区必须按 `sequenceIndex` 升序穿线，否则 `UnexpectedGateOrder`（`LapTimingEngineTest.kt:173-195`）
- `trajectory = updatedSamples.drop(activeLap.sampleStartIndex)`（`LapTimingEngine.kt:107`）——每圈轨迹切片，不共享

---

## 五、集成链路：ViewModel bridge

文件：`feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`

入口：`bridgeGpsToLapTiming(gpsData)`，每条上游 GPS 样本调用一次（`TestSessionViewModel.kt:120, 301-335`）。

### 触发条件（`TestSessionViewModel.kt:302-305`）

- `lapRunConfig != null`
- `currentMode == LapDebug`
- `isLapRecording == true`
- `_lapSession != null`

### 首样本守卫（`TestSessionViewModel.kt:315-319`）

`previousSample == null` 或 `timestampMillis <= 0L` 时只刷新内部 `lastLapGpsSample` 引用，不喂引擎——避免首帧无前驱的伪穿线。

### 会话生命周期

| 入口 | 行为 | 代码位置 |
|---|---|---|
| `selectLapDebugMode(config)` | `createLapSession(Ready)`，新 `sessionId` | `TestSessionViewModel.kt:142-153, 337-343` |
| `stopLapDebugSession()` | 状态置 `Finished`，**保留 samples/records** | `TestSessionViewModel.kt:155-159` |
| `exitLapDebugMode()` | 清零 `lapSession`、`lapRunConfig`、`latestLapRecords` | `TestSessionViewModel.kt:161-168` |
| 再次 `selectLapDebugMode` | 生成新 `sessionId`，状态回到 `Ready` | 由 `TestSessionViewModelTrackLapTest.lapDebugMode_reentryCreatesFreshReadySessionWithoutPreviousSamplesOrCrossings` 锁死 |

---

## 六、赛道来源：`TrackCatalog`

### 接口

`feature/test/src/main/java/com/blazepush/feature/test/repository/TrackCatalog.kt`：`getAllTracks()` / `getTrack(id)`。

### 实现

- **`PresetTrackCatalog`**（`PresetTracks.kt:74-78`）：硬编码 TFIC LPCC。
  - `PresetTracks.kt:11-72` 把 13 个 referencePath 点 + 3 道 gate 的坐标/方向向量全部写死，并由 `TrackCatalogTest.getTrack_locksTficLpccCoordinateContractWithReplayAlignedPresetConstants` 逐点契约锁定。
- **`ReplayAlignedTrackCatalog`**（`ReplayAlignedTrackCatalog.kt:29-91`）：
  - 用 `feature/test/src/main/assets/replay/tianfu_track_replay_5hz.json` 的采样重建 `referencePath`。
  - Gate 几何**仍然复用 preset**（`ReplayAlignedTrackCatalog.kt:71-72`），不从 replay 推断。
  - 失败时 `runCatching{}.getOrNull()` fallback 到 preset（`ReplayAlignedTrackCatalog.kt:34-41, 45, 51`）。
- **DI**：`feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt` 中
  ```
  single<TrackCatalog> { ReplayAlignedTrackCatalog(get(), PresetTrackCatalog()) }
  ```

---

## 七、UI 三页

### 1. `LapDebugConfigScreen.kt`

- 选赛道 + 三开关（参考线/门/轨迹），最终 `onConfirm(LapRunConfig)`。
- ⚠️ `showCrossingDebug` 在 UI 上**无开关**，只从 `initialConfig` 继承（`LapDebugConfigScreen.kt:154`）。

### 2. `LapDebugExecutionScreen.kt`

- Track 概览卡 + 实时遥测 + **起终点计时卡片** + `LapDebugMapPlaceholder` + Stop 按钮。
- **本次工作区未提交修改**：把外层 Column 改为 `verticalScroll(rememberScrollState())`，移除占位 `Spacer(weight=1f)`，以便小屏下所有卡片可滚出（`LapDebugExecutionScreen.kt:46-52`）。
- `StartFinishTimingCard` / `rememberStartFinishTimingCardState`（`LapDebugExecutionScreen.kt:194-224`）：
  - 只看 `gateType == StartFinish && accepted` 的事件。
  - 上一圈时长 = 最近两次 accepted 起终点 ts 差值。
  - **当前圈时长 = 最新 sample ts − 最近 accepted 起终点 ts**。
  - 当前圈路程用 haversine 累积（`LapDebugExecutionScreen.kt:226-261`），**不经过 LapTimingEngine**。

### 3. `LapDebugResultScreen.kt`

- 列出 `LapRecord` + 最近 crossing 摘要。
- 仅展示 `durationMillis` 和 `sectorTimes`，**未使用** `qualityFlags` 和 `crossingEvents`。

---

## 八、测试矩阵

**总览**：本次实际跑了 39 个用例，1 skipped（@Ignore），1 failed（DI）。

| 测试文件 | 用例数 | 覆盖点 |
|---|---|---|
| `usecase/LapTimingEngineTest.kt` | 7 | 起终点首穿、第二次闭圈、有序扇区完整圈、缺扇区 `IncompleteSectors`、乱序扇区拒绝但仍可闭圈、非期待门拒不推进、onJvm 日志不崩 |
| `usecase/GateCrossingDetectorTest.kt` | 5 | 正向接受、反向拒、无交点拒、门延长线外拒、段内接受 |
| `repository/TrackCatalogTest.kt` | 3 | 只暴露 TFIC、全 13 点位 + 3 道 gate 坐标契约锁、未知 id 返回 null |
| `repository/ReplayAlignedTrackCatalogTest.kt` | 7 | build 不抛、preset/generated 两路都能接受开圈样本、几何保持 TFIC RCZ 参数、fallback 行为 |
| `viewmodel/TestSessionViewModelTrackLapTest.kt` | 8 | 选赛道→LapSession、用 runtimeReplayCatalog、闭圈、stop 后保留、重进清理 |
| `ui/screen/LapDebugExecutionScreenStateTest.kt` | 5 | 计时卡片状态计算 |
| `model/LapTimingModelSmokeTest.kt` | 1 | data class 基线 |
| `di/DomainModuleKoinTest.kt` | 2 | `providesGpsDataFilter` ✅ / **`providesTrackCatalog` ❌** |
| `usecase/ReplayLapTimingIntegrationTest.kt` | — | 整文件 `@Ignore`（回放端到端闭环被排除在稳定基线外） |

---

## 九、已知裂缝（Review 重点）

### 9.1 `DomainModuleKoinTest.domainModule_providesTrackCatalog` 在 JVM 里失败

- **现象**：`MissingAndroidContextException`。
- **根因**：DI 绑到 `ReplayAlignedTrackCatalog`，它通过 `AssetReplayTrackSource` → `Context.assets` 读取 replay JSON/VBO；JVM 单测拿不到 Context。
- **影响**：稳定基线里有一条真红。任何依赖 DI 的集成/Robolectric 测试都会踩同一个坑。
- **备选修复**：
  - (a) 让 `domainModule` 在 JVM 环境 fallback 到 `PresetTrackCatalog`（按 `is JvmOnly` / Robolectric 条件分支）。
  - (b) 把 `DomainModuleKoinTest` 挪到 `androidTest` 或 Robolectric。

### 9.2 `ReplayLapTimingIntegrationTest` 整文件 `@Ignore`

- `ReplayLapTimingIntegrationTest.kt:6-8` 声称"依赖实验链路 `ReplayTemporaryGateBuilder`"。
- **缺口**：真实 5Hz 轨迹 → 圈速产出的端到端闭环没进回归网，是目前最大的测试空洞。
- **建议**：剥离 `ReplayTemporaryGateBuilder`，直接用 preset gate 套在 replay 样本上跑闭环断言圈时。

### 9.3 `CrossingReason.Cooldown` 是死枚举值

- `CrossingReason.kt:8` 定义但无任何生产/消费者。
- **决策**：要么接入去抖窗口（detector 层 or engine 层），要么删掉避免假装实现过。

### 9.4 欧氏相交在跨纬度时失真

- `GateCrossingDetector.kt:85-99` 原始经纬度做叉积。TFIC 纬度 OK，多赛道/高纬度迁移会漂。
- **建议**：写进"第二阶段几何升级"backlog。第一阶段单赛道可接受。

### 9.5 速度下限门槛未启用

- `PresetTracks.kt:43,56,68` 所有门 `minDirectionalSpeedMps = null`。
- **风险**：低速掉头经过起终点会被判为 accepted。
- **建议**：结合 [TFIC 起终点速度区间约束](记忆 `project_tfic_start_finish_speed_zone.md`，尾速 171 km/h 区段中 120 km/h 左右位置) 给一个保守下限，比如 50 km/h。
- **⚠️ 启用前必须先修量纲错位（2026-04-22 对抗 review 1.1 / 3.5）**：当前 detector 的 `directionalSpeedMps` 实际量纲是**度²/秒**而不是字段名暗示的 m/s（`movement` 单位是度、`passDirection` 未归一化单位也是度，点积 = 度²，除以秒仍是度²/秒）。直接按 m/s 填入下限（例如 13.9）会锁死整条链路——`directionalSpeedMps` 在 TFIC 尺度下约 `9e-8 度²/秒`，永远 `< 13.9`，所有穿线一律 `TooSlow`，无法开圈或闭圈。修复必须先把 detector 投影到米坐标系，再启用下限。

### 9.6 UI 路程与引擎几何不同源

- `LapDebugExecutionScreen.kt:244-261` 用 haversine 累积路程。
- `GateCrossingDetector.kt:75-99` 用欧氏叉积。
- **现象**：可能出现"UI 路程涨了但门没触发"或反之的微小偏差。不致命但要写进文档。

### 9.7 首样本不入 `samples`

- `TestSessionViewModel.kt:315-319` 首样本守卫：`previousSample == null` 时不喂引擎也不追加到 `session.samples`。
- **副作用**：`session.samples` 只包含"经引擎处理过"的样本，UI 的"当前圈路程"在真正接到第二条样本前恒显示 `0.0 m`。属于预期行为，但建议在 review 时落注释。

### 9.8 非期待门检测的复杂度

- `LapTimingEngine.kt:139-143` 每样本对所有"非期待扇区门"都调一遍 `detector.detect`。当前 TFIC 只 2 个 sector 可忽略，sector 数扩展后需注意。

---

## 十、Review 时要追问的问题

- [ ] `DomainModuleKoinTest.domainModule_providesTrackCatalog` 修 DI 绑定还是挪测试？
- [ ] 回放集成测试复活方案：剥离 `ReplayTemporaryGateBuilder` 后，闭环断言的圈时基准用哪条参考？
- [ ] `minDirectionalSpeedMps` 何时启用？默认值？（TFIC 起终点约 120 km/h）
- [ ] `Cooldown` 去抖窗口是否落在引擎层还是 detector 层？落地还是删除？
- [ ] UI 路程（haversine）与引擎几何（欧氏）的差异是否要在长期方案里统一？

---

## 十一、本次本地新增提交

- `a35164a fix(laptiming): 执行页支持纵向滚动` — 执行页外层 Column 改为 `verticalScroll(rememberScrollState())`，移除占位 `Spacer(weight=1f)`。6 行 diff。**已本地 commit，未 push。**
