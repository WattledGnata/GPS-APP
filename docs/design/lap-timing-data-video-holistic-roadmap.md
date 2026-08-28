> 由 workflow lap-timing-data-video-roadmap 于 Phase 1 收尾期生成；圈速/数据分析/视频三大功能整体规划，复原并提速 3 周前停滞的旧规划。视频 scope 限 Phase 2 录制+同步，叠加导出列 future。

# GPS App 三大功能整体分析 + 分期路线图（提速终版）

> 文档定位：整体分析 + 分期路线图，**不碰代码、不生成 round 工件**。视频录制只规划 Phase 2「录制 + GPS 时间轴同步」，Phase 3「叠加导出 / HUD」仅占位。
> 生成日期：2026-05-29 | 主分支：`feature/track-tech-v2`（本地领先远端 **19 commit**，已核实 `git rev-list --count origin/feature/track-tech-v2..HEAD = 19`）
> 本终版已吸收对抗式 review 的 5 大类 22 条改进点（见附录 §8）。

---

## 0. 旧规划复原 + 提速诊断（开篇 · user 核心诉求）

### 0.1 三周前那版「旧规划」是什么

旧规划是一条**三阶段串行视频叠加路线图**，记录在 `docs/implementation-design/parallel-change-collab.md §7 Phase 治理表`，最后活跃于 2026-05-05：

| Phase | 主题 | Round 数 / 估时 | 依赖 |
|---|---|---|---|
| **Phase 0** | 数据层闭合（binary / crossing / perftest 时钟域） | 3 round / ~4 天实际 | — |
| **Phase 1** | 单圈数据图表 + 多圈比较 | 5 round + B round / ~9.5 天 | Phase 0 exit |
| **Phase 2** | Session 内置摄像头（CameraX 录制 + Room 视频 metadata） | 5 round / ~6.5 天 | Phase 1 exit + L0 |
| **Phase 3** | 视频叠加导出（MediaCodec 提帧 + overlay widget + 离线渲染） | 5 round / ~10 天 | Phase 1 + Phase 2 双 exit |

**Phase 1 旧 round 列表（user 最需要回忆的部分）**：
1. `lap-data-readers`（W1，reader 双 API）— ✅ 已归档 archive/2026-05-04
2. `chart-and-map-components`（W2，4 个 chart/map 组件）— ✅ 已归档 archive/2026-05-04
3. `lap-detail-screen-with-cursor`（Tier2 单圈详情屏 + 游标）— ⏳ **从未启动**
4. `lap-comparison-time-align`（W3，多圈对齐算法）— ✅ 已归档 archive/2026-05-04
5. `lap-comparison-screen-with-cursor`（Tier2 多圈比较屏 + 游标）— ⏳ **从未启动**
6. B round `wire-laptime-to-gps-filter`（W4，接 GpsDataFilter）— ✅ 已归档 archive/2026-05-05

### 0.2 当前实际停泊点

**旧规划停在「Phase 1 半程」**——数据层 + 算法 + 组件底座（W1/W2/W3/W4 + phase1-hardening + unify-perftest-anchor）全部 land 并归档，但**两个用户可见的 UI 屏（round 3 / 5）一个都没启动**。Phase 1 Exit Review 因此从未跑，Phase 2/3 全部按兵未动。

已确认（git 核对后修正）：
- **Phase 0 exit commit 已实际落地**：`e2a42a1 chore(phase): Phase 0 exit review`。**但 `git show --stat` 证实它是「无文件改动的 message-only 治理标记 commit」，不携带 metrics.yaml**——见 §5.5 治理留痕缺陷，不能当「已完美闭合」掩盖。
- 本地领先远端 **19 commit**（不是早先草案口误的 20），全部 **⏳ 待 push**（user 拍板顺序）。
- 两个未归档 change 工件齐全但未实现：
  - `openspec/changes/redesign-realtime-delta-projection-search/`（proposal/design/specs/tasks 齐）—— **但已核实未登记看板 §5/§7**（grep 仅命中无关的 G round `redesign-performance-result-screen`），开 round 前必须先补登记（§5.4）。
  - `openspec/changes/improve-test-execution-progress-bar/`（代码已合回主区 + 13 单测绿，停在真机 gate，**属「存量待归档 round」非新立项**，见 §5.6）。

### 0.3 为什么旧规划太慢（一句诊断 + 6 根因）

**一句诊断：Tier2 两个用户可见 UI 屏纯串行排在队列尾段 + 底座工作（reader/chart/align）优先级倒置吃光节奏，3.5 周一个 UI 屏都没启动。**

| 根因 | 描述 | 提速对策 |
|---|---|---|
| **根因1 · Tier2 UI 屏纯串行** | round 3/5 排在 Tier2 串行尾段，依赖 round 4，两屏文件边界几乎不交叉本可 worktree 并行，实际 3.5 周一个没启动 | round 3→5 仍有依赖（5 复用 3 的 chart 组屏），但可与 redesign-delta / 进度条 round **跨主题并行** |
| **根因2 · 底座优先级倒置** | W1-W4 + hardening + unify 全是底座，离用户可见 UI 越来越远 | 底座已 land，**新版第一批直接做 UI 屏**，不再加底座 round |
| **根因3 · mimo 跳 L2 派生 hardening 整轮** | W1-W4 mimo 全跳 L2，事后补跑暴露 5 P0 + 多 P1，再派生 phase1-hardening 整轮消化；**W4 至今无 L2 trail** | 新版严守 v3 不走 mimo；W4 补 L2 列为 Phase 1 exit 硬 gate（§5.7） |
| **根因4 · follow-up round 碎片化** | memo + W1 §10 多项 + 派生 round，每个走全套 ff/L1/apply/L2/真机/归档固定开销 | **加速通道**清碎 round（2026-05-29 授权）+ 同批并行摊薄固定开销 |
| **根因5 · 真机 gate 串行 + 授权阻塞** | 进度条 round 代码全绿卡真机授权；真机串行（一次一个 apk） | 真机 gate **批量攒一起**，UI 屏实施期不阻塞 |
| **根因6 · push 全批悬置** | 19 commit 待 push，kt-format-checker 逐条验证使 user 倾向攒批，攒批又让远端长期落后 | push 顺序路线图**预排**（§5.8），减少 user 决策负担 |

### 0.4 新版提速策略（核心）

1. **底座不再加 round**：reader + chart + align 全部就绪，节奏投到「组屏 + 接线 + 导航」和「修真机已现 bug」，不再盖地基。
2. **充分用 Small/Trivial 加速通道**（2026-05-29 授权）：碎 follow-up round 走加速通道（0 子 agent + Codex 单线），无需 user 显式授权。
3. **worktree 三线并行第一批**：3 个独立 worktree 同时推三条文件零交叉的线（§4），但并行性须先经看板 §6 函数级实测确认（吸收 review，见 §4 caveat）。
4. **真机 gate 攒批**：UI 屏实施期不卡真机，攒到一批就绪后一次性串行验证（一次 adb install 一个 apk 的硬约束不可破，但压缩验证次数）。
5. **push 顺序预排**：§5.8 已按 commit 时序 + kt-format-checker 依赖排好，user 一次拍板即可。

---

## 1. 三大功能现状盘点

### 1.1 功能一：圈速测试 lap timing

**done（质量高）**：端到端实时链路（选赛道 → START LAP SESSION → 横屏 `LapLiveScreen` 2x2 仪表 + abnormal banner + HOLD TO END → 过线判定 + 圈时插值 + binary/crossing 双写 Room → session 详情屏读圈列表）。判定核心经多轮时钟域/精度对抗 review 加固：`LapTimingEngine.kt` / `GateCrossingDetector.kt` / `LapLiveStateDeriver.kt` / `TestSessionViewModel.kt`（双时钟域共存）。

**partial**：
- `RealtimeDeltaCalculator.kt`：实时秒差投影，真机已复现 **DELTA -125.20s 灰值 + 5s step 跳变**。修复工件齐全（`redesign-realtime-delta-projection-search`）但未实现。
- `LapSessionDetailScreen.kt`：仅文字圈列表 + Overview，**无图表/地图/游标**，圈行不可点。

**gap**：实时 DELTA bug 未修；`LapTelemetrySample.flags` 在 binary writer 端永久默认 0（deferred Phase 2，信号源不在 RaceChrono BLE 协议）；sector 链不完整时 lap 是否闭环未拍板（memo #9，W4 阈值 3→1 让 baseline fail）；**赛道仅预置（TFIC/博裕 loop），无服务端下发/轨迹生成 —— 这是影响功能一可用范围的核心扩展 gap，见 §7 future 占位**。

### 1.2 功能二：圈速数据分析 / 可视化

**done（底座齐全但零消费）**：
- reader：`TelemetryRepository.getLapTelemetry`（L272-309）+ `TestResultRepository.getDataPointsForResult`（L143-177，含 sentinel guard）。
- 算法：`LapAlignment.kt`（`alignByDistance` 等距重采样 / `gridIndexFor` L30 / `distanceAtGridIndex` L35 跨圈映射）、`AccelerationSmoother.kt`（5 点 SG 平滑）。
- 组件库（4 个，全有 contract test）：`SpeedTimeChart.kt` / `AccelTimeChart.kt` / `SectorBar.kt` / `TrackPolylineMap.kt`。

**最大事实（决定路线图重心）**：**这套 reader + chart + align 至今零生产消费**（grep 证实 4 组件只互相引用，无生产 screen import）。从用户视角「单圈详情含游标 + 多圈比较」**0% 可用** —— 缺的是组屏 + 接线 + 导航，不是底层算法。

**核实到的三个组屏前必拍硬数据（吸收 review，全部 grep 坐实）**：
- **accelerationG 硬编码 null**：`TelemetryRepository.kt:294` + `TestResultRepository.kt:167` 都写 `accelerationG = null` → `AccelTimeChart` 永远「NO ACCEL DATA」。组屏须在 reader 或 UI 层用 `AccelerationSmoother` 从 `speedKmh` 反算灌回（决策点见 §3 detail 屏 R1）。
- **sectorBoundaries 单元素**：`TelemetryRepository.kt:305` 写死 `sectorBoundaries = listOf(lapStartWallClock)` → `SectorBar` 实际只画 1 段（决策点见 §3 detail 屏 R2 + sector 派生排序，§4 已修正）。
- **cursor 精确相等匹配（架构断层，非接线活）**：4 组件 cursor 联动全靠精确相等：`SpeedTimeChart.kt:121/168` / `AccelTimeChart.kt:50/93` / `TrackPolylineMap.kt:84` 的 `samples.find/indexOfFirst { it.absoluteTsMs == cursorAbsoluteTs }`；`SectorBar.kt:51` 用 `(cursorAbsoluteTs - lapStartWallClock)/lapDuration` 时间分数。**单圈内多图共享同一 `LapTelemetry.samples` 可命中；但多圈比较屏跨圈时不同圈 `absoluteTsMs` 完全不同，精确相等永远 miss** —— 必须改组件 API 引入 `gridIndex` 入参走 `LapAlignment.gridIndexFor/distanceAtGridIndex` 距离映射。这是组件签名级返工，见 §3 比较屏被升级为 large。

**gap**：单圈详情屏 / 多圈比较屏从未启动（`TrackTechAppShell.kt` L167-187 无 `lap_detail` / `lap_comparison` route）；chart 真实数据零验证（断言走 test-only `FakeLapTelemetry`，`wire-mock-telemetry-to-w1-real-classes` 未做）；`PerformanceResultScreen.kt`（已由归档 G round `redesign-performance-result-screen` 重建，给 `SpeedChart/GForceChart` 加 `wrapInCard` 参数 + SpeedCurveSection 真实化）仍走 legacy chart，未接入 W1 `getDataPointsForResult`（deferred memo #5 UI 侧未收尾 —— **scope 注意：替换 legacy chart 会推翻 G round 刚签收的接线，见 §3 该 round 升级 + §8**）。

### 1.3 功能三：圈速视频录制（Phase 2 范围）

**现状：完全绿场**（已 grep 核实）。全仓 CameraX/MediaRecorder/Camera2/MediaCodec/MediaMuxer/VideoCapture/SurfaceTexture/RECORD_AUDIO/CAMERA 权限在生产源码 **0 命中**；manifest 仅 BLE/Location 权限；现有运行时权限流仅 `MainActivity.kt:93` 一个 launcher 处理 BLE/Location；build.gradle / `libs.versions.toml` 无 camera 依赖。

**最关键的好消息——干净统一的本地壁钟 anchor（done）**：
- `TelemetryRepository.startSession()` L69 `startTs=System.currentTimeMillis()`，L90 `activeSessionStartTs=startTs`；reader L288 `absoluteTsMs=entity.startTs+sample.tsDeltaMs`。
- crossing wallClock（`TestSessionViewModel.kt` L925）注释明确「与 binary samples absoluteTs 同时钟域」。
- **视频只需在录制起点同样取 `System.currentTimeMillis()` 锚定**，帧 PTS 即可线性映射到圈速位置。

**gap（全链路从 0 搭，已按 review 重估体量，见 §3 Phase 2 与 §8）**：CAMERA 权限 + uses-feature + 运行时权限流；CameraX 依赖；新建 `core:camera` 模块（独立决策）；`TelemetrySessionEntity` 加 `videoFilePath/videoStartedAtWallClock` + v5→v6 strict migration（注意现有 `version=5` 且有 `fallbackToDestructiveMigration` 债）；视频文件存储 `filesDir/video/$sessionId.mp4`（deleteSession 白名单当前仅 `/telemetry/`，`TelemetryRepository.kt:245`）；**录制起点 wallClock 必须在首帧落地回调取值**（相机冷启动延迟数百 ms 会让 Phase 3 失效）；`LapLiveScreen`（唯一 LANDSCAPE 页，强制 `SCREEN_ORIENTATION_LANDSCAPE` L80，keepScreenOn）是 preview 天然宿主 —— **但横屏 vs 竖屏录制是 L0 必答需求边界，路线图不替用户假定，见 §3 Phase 2 L0 + §7 featureGap**。

---

## 2. 集成点地图（三 track 如何交织）

```
                  ┌─────────────────────────────────────────────────────────┐
                  │   共享时间轴锚点：System.currentTimeMillis() 接收侧壁钟       │
                  │   activeSessionStartTs / absoluteTsMs / crossingWallClock  │
                  └─────────────────────────────────────────────────────────┘
                            ▲                    ▲                     ▲
          ┌─────────────────┘                    │                     └──────────────────┐
          │                                       │                                        │
  ┌───────┴────────┐               ┌──────────────┴───────────────┐          ┌─────────────┴──────────────┐
  │ 功能一 圈速测试    │  binary 写   │ 功能二 数据分析/可视化            │  消费     │ 功能三 视频录制(P2)+叠加(P3)   │
  │ LapTimingEngine │ ───────────▶ │ getLapTelemetry → LapTelemetry│ ◀──────  │ videoStartedAtWallClock     │
  │ crossing 双写    │              │  .samples[absoluteTsMs]       │          │  = currentTimeMillis(首帧)   │
  │ RealtimeDelta   │              │ SpeedTimeChart/AccelTimeChart  │          │ P3: 按 absoluteTsMs 取帧叠加  │
  └─────────────────┘              │ SectorBar/TrackPolylineMap     │          └────────────────────────────┘
                                   │ LapAlignment.alignByDistance   │
                                   └────────────────────────────────┘
```

**五条关键交织线（前三条原有，后两条吸收 review 新增）**：

1. **GPS 本地壁钟 anchor 是把视频帧映射到圈速数据的共享时间轴**（最核心）：
   - binary samples 的 `absoluteTsMs = entity.startTs + tsDeltaMs`，与 `crossingWallClockTimestampMs` 同时钟域。
   - Phase 2 的 `videoStartedAtWallClock` MUST 对齐到同一壁钟域。Phase 3 `video-frame-extractor` 按 wallClock 取帧、overlay widget 从 `absoluteTsMs` 索引同步叠加。
   - **纪律**：视频对齐 MUST 用 wallClock，不能用 `crossingTimestampMs`（GPS 协议时间）—— 双时钟域不可混用。

2. **数据分析屏消费圈速 reader**：单圈详情屏 `LaunchedEffect` 调 `getLapTelemetry(sessionId, lapIndex)`；光标 scrub 跨速度/加速度/轨迹图共享同一 `absoluteTsMs` 锚点。
   - **lapIndex 语义对齐风险**：`getLapTelemetry` 用 accepted StartFinish crossing wallClock 取 `[i, i+1]`，与圈列表 `zipWithNext` durations 必须**同一套圈编号**（`unify-lap-count-pairing-semantics` 待收敛），否则点「Lap 3」打开错圈。

3. **视频叠加（Phase 3 future）消费数据分析的 telemetry**：Phase 3 overlay-widgets 复用功能二的 `LapTelemetry` 数据契约 + chart 投影 + `LapAlignment` delta；session 与视频通过共享 `sessionId` 关联（`$sessionId.mp4` 与 `$sessionId.bin` 配对）。

4. **【review 新增 · 帧率/PTS 离散度 vs 25Hz 采样的映射契约】（Phase 2/3 二级风险，比首帧锚定更隐蔽）**：
   - 视频 30fps（33ms/帧）、binary telemetry 25Hz（40ms/帧），**两者帧周期不互质**。Phase 3 按 `absoluteTsMs` 取 telemetry sample 时「最近邻 vs 插值」策略必须拍板（沿用 W3 `LapAlignment.interpolate` 已确立的最近邻 + clamp 策略保持一致）。
   - **video PTS 单调时钟 vs wallClock 漂移**：长视频里 `currentTimeMillis()` 可能被 NTP/手动调时跳变，而 video PTS 是录制器单调时钟 → 「anchor 写对了但取帧仍错位」。**MUST 在 Phase 2 `camera-recording-and-gps-sync` 的单测里同时锁死 (a) 首帧 wallClock 取值时机 (b) PTS→absoluteTsMs 映射公式 (c) 长录制 PTS-wallClock 漂移容忍策略**，不能推到 Phase 3 才发现。

5. **【review 新增 · 实时 DELTA 投影与离线 LapAlignment 的坐标收敛】**：
   - 实时 DELTA 的 reference 是 in-memory `LapRecord.trajectory`（`redesign-delta` 改的是 in-memory 投影）；离线分析的 reference 是 binary `getLapTelemetry`。两处**都在做「当前点投影到参考圈 polyline + 进度插值」**。
   - 路线图让它们各自演进会埋「第二次重复实现/再收敛」债。**MUST 在 `redesign-realtime-delta-projection-search` 的 design 期记一条决策**：明确 stateless O(n) 投影算法与功能二 `LapAlignment.alignByDistance` 是否共享同一套距离投影坐标，或显式声明「两套各自独立 + 理由」（避免静默分叉）。

---

## 3. 分期路线图

> 复杂度判定依据 CLAUDE.md `Round 复杂度分级`；强制升级 medium 的 5 个例外场景：(1) 公共协议改 (2) 跨 capability ripple (3) Room schema migration (4) 引入新 module/capability (5) 派生 follow-up round。

### Phase 1 收尾（Tier2 两屏 + 圈速 follow-up）

| Round (kebab-case) | scope | 复杂度 | 依赖 | 强制升级 medium? | review 流程 | 同批可并行 |
|---|---|---|---|---|---|---|
| `redesign-realtime-delta-projection-search` | 实现已写好工件：projectDelta 改 stateless 全量 O(n)（Alt B），去 prevMatchedIdx/forwardWindow 跨帧 cache，加 4 边界反例 scenario，修真机 -125s 灰值 + 5s step。**design 期 MUST 加交织线 5 决策**（与 LapAlignment 距离投影是否收敛）。仅缺 apply+真机+archive | medium | — | 否（已 medium，派生 follow-up） | **v3 标准** | ✅ 改 `RealtimeDeltaCalculator/RealtimeDeltaState/LapLiveScreen` DELTA tile；⚠️ 与 Phase 2 抢 `LapLiveScreen`，须看板 §6 登记（§5.4） |
| `wire-mock-telemetry-to-w1-real-classes` | 按 W2 注释把 chart contract test 的 FakeLapTelemetry 切到正式 `LapTelemetry`，验证 chart 直接消费 `getLapTelemetry` 输出。纯测试侧 + 可能微调 chart 签名 | small | — | 否 | **加速通道** | ✅ 仅 `feature/test/src/test/.../components/`，独占 |
| `unify-lap-count-pairing-semantics` | W1 §10.6：收敛 lapCount 双语义（endSession zipWithNext vs Snackbar in-memory 过滤），确保单圈详情屏 lapIndex 与圈列表圈编号严格同源 | small | — | 否 | **加速通道** | ⚠️ 改 `TelemetryRepository.endSession` + ViewModel 圈编号路径，**与线 A redesign-delta 都碰 ViewModel/session lifecycle，并行性须 §6 函数级实测**（吸收 review，不再宣称零交叉） |
| `fix-lap-debug-mode-sector-chain-test-after-min-count-1` | memo #9：拍板 sector 链不完整时 lap 是否仍闭环（**user business decision**），修 `TestSessionViewModelTrackLapTest` baseline fail 的 expected + 补 invalidation banner 状态断言 | small | 需 user 拍 business decision | 否 | **加速通道** | ✅ 仅 `TestSessionViewModelTrackLapTest.kt`，独占 |
| `lap-detail-screen-with-cursor` | **Phase 1 核心交付物 1**：组装 4 chart/map + 共享 cursorAbsoluteTs state hoisting；加 route `lap_detail/{sessionId}/{lapIndex}` + 圈行 onClick；`LaunchedEffect` 调 `getLapTelemetry`。**design 期 MUST 拍板：R1 accelerationG 派生位置（reader vs UI 接 AccelerationSmoother）；R2 §10.13 SectorBar R7。硬 gate：若选 reader 侧填 accelerationG/sectorBoundaries → 修改 LapTelemetry 公共数据契约填充语义，命中 #16 共享字段语义扩展 + W2/W3 已合回消费契约 → MUST 触发 #16 drift mini-review 并强制升级 medium 流程，禁止 apply 期静默偏离（F1 #17）** | medium（reader 侧填则升级） | `wire-mock-telemetry-to-w1-real-classes` + `future-sector-derivation-round`（见排序修正） | 见左 R1/R2 硬 gate | **v3 标准** | round 5 依赖它；可与 `wire-perfresult` 跨主题并行 |
| `future-sector-derivation-round` | W1 §10.7：`getLapTelemetry` 用 sector gate crossing 真实派生多元素 `sectorBoundaries`（当前只放 lapStartWallClock）喂 SectorBar 画多段。**排序修正（吸收 review）：必须前置到 detail 屏之前或与之合并，否则 detail 屏只能先做 1 段废 SectorBar，sector 派生回头返工 detail 屏 SectorBar 接线** | medium | — | **是**（#16 共享契约填充语义扩展 + 派生 follow-up） | **v3 标准** | ⚠️ 改 reader 填充语义，与 detail 屏 R2 决策耦合，宜先于或并入 detail 屏 |
| `lap-comparison-screen-with-cursor` | **Phase 1 核心交付物 2**：多圈比较屏，圈选择 + 参考圈 + `LapAlignment.alignByDistance` 重采样 + cursor 经 `gridIndexFor` 同标多圈位置 + delta。**升级理由（吸收 review）：4 个 chart 组件 cursor 入参只吃 `absoluteTsMs` 精确相等（已核实 L121/L50/L93/L84），跨圈永远 miss → 必须改组件 API 引入 `gridIndex` 入参走距离映射，是组件签名级返工。design 期 MUST 拍板比较屏 X 轴语义：`elapsedMsInLap`（时间轴）vs `alignByDistance`（距离轴）—— 两者直接冲突，旧草案自相矛盾未拍板** | **large**（组件 API 签名级改 + X 轴语义未决） | `lap-detail-screen-with-cursor` | 是（改 4 组件公共 API + 派生 follow-up） | **v3 标准（L1 3-5 轮）** | ⚠️ 复用 detail 屏组屏模式 + 改组件 API，必须串行其后 |
| `wire-perfresult-to-getdatapointsforresult` | deferred memo #5 收尾：`PerformanceResultScreen` 从裸 reader 切到 `getDataPointsForResult`（获 sentinel guard）。**scope 升级（吸收 review）：旧草案「评估用 W2 游标 chart 替换 legacy SpeedChart/GForceChart」会推翻已归档 G round `redesign-performance-result-screen` 刚落地的 wrapInCard 接线 + 视觉签收成果 → 拆成两段：(a) 本 round 只切 reader 数据源（保留 G round chart 接线）；(b) 显式新立 follow-up `retire-legacy-perf-charts` 真正删除 legacy chart 统一到 W2 游标 chart（不再用「评估」掩盖债务延续）** | medium | `wire-mock-telemetry-to-w1-real-classes` | 否 | **v3 标准** | ✅ 独占 `PerformanceResultScreen.kt`，与圈速屏零交叉 |
| `retire-legacy-perf-charts` | **新增（吸收 review featureGap5）**：执行 memo #5 最终目标，删除 legacy `SpeedChart/GForceChart`，PerformanceResult 统一到 W2 游标 chart，收敛两套曲线债务。**不是「评估」是「执行」** | medium | `wire-perfresult-to-getdatapointsforresult` + `lap-detail-screen-with-cursor`（确认 W2 chart 在生产屏稳定后） | 否 | **v3 标准** | ⚠️ 触碰 G round 视觉成果，须小屏真机重签收 |
| `improve-test-execution-progress-bar` | **存量待归档 round（非新立项，吸收 review governance2 修正）**：代码已合回 + 13 单测绿，**走「真机 gate + metrics.yaml 补写 + archive」收尾路径，不是「加速通道新 round」**，因此不跑 apply 期 #3/#14/#16 自查（那是新 round 流程）。独占 `TrackTechTestExecutionScreen.kt` | 收尾（存量） | — | 否 | **收尾路径**（真机+archive） | ✅ 独占，零交叉 |
| `restore-strict-migrations-pre-release` | **独立提前（吸收 review underScoped3）**：恢复 `AppDatabase` strict migration，消除 `fallbackToDestructiveMigration` 债（当前 `version=5`，债意味升级丢历史 session）。**上线前阻断级独立 round，禁止寄生进 Phase 2 video metadata round** | medium | — | **是**（Room schema 治理 + 上线阻断） | **v3 标准** | ✅ 独占 `AppDatabase.kt`，可任意时机插入；建议 Phase 2 schema 改之前完成 |
| `phase1-exit-review` | Phase 1 最后一个 round 闭环时跑强制 Phase Exit Review。**硬 gate（吸收 review governance4/5）：(1) W4 round 补跑 L2 或 user 显式豁免留痕（W4 是 Phase 1 唯一无 L2 trail 归档 round）；(2) Phase exit disposition 必须留可审计痕迹进 git（鉴于 e2a42a1 是 message-only + *.md 被 exclude，须确定 disposition 落点：commit body 全文 or 纳入 git 的非 .md 治理文件）**。逐个 disposition deferred memo（flags binary 推 Phase 2 / memo #10 automate-design-drift / memo #6 J round / memo #7 records-by-track-filter / W1 §10 残余） | small | `lap-comparison-screen-with-cursor` + 上述全部 archived | 否 | 治理 gate（非 round） | 必须最后串行 |

### Phase 2 视频录制 + GPS 同步（绿场 · 体量已按 review 重估）

> **治理警示**：Phase 2 每个 round 都命中「强制升级 medium」例外场景，**全部不能走加速通道**；L0 需求理解 review 必跑（camera 新模块 + 横竖屏 + 音频）。Entry 硬 gate = Phase 1 exit commit。**Phase 2 立项前 L0 必答 3 项：(a) 录制方向横屏 vs 竖屏（LapLiveScreen 强制 LANDSCAPE L80，竖屏录制会与 enforce-portrait 锁冲突）(b) 音频 RECORD_AUDIO 是否录（发动机声价值 vs 权限敏感 + 文件翻倍）(c) 预览有/无**。

| Round (kebab-case) | scope | 复杂度 | 依赖 | review 流程 | 并行性 |
|---|---|---|---|---|---|
| `camera-module-and-permission` | **体量升级（吸收 review underScoped1）**：新建 `core:camera` 模块（独立决策）+ 引入 5 个 CameraX 依赖 + `libs.versions.toml` 新条目 + CAMERA 运行时 dangerous permission 流（与现有 `MainActivity.kt:93` BLE/Location 流并存的拒绝/永久拒绝/跳设置降级）+ manifest CAMERA/uses-feature。纯打通权限+模块骨架 | **large**（新 module + 新权限流 + 多依赖集成真机踩坑） | Phase 1 exit + **L0** | **v3 标准（L1 3-5 轮）** | Phase 2 内串行链首环 |
| `camera-preview-in-laplivescreen` | PreviewView 嵌 Compose AndroidView + lifecycle 绑定，宿主 `LapLiveScreen`（据 L0 横竖屏结论定方向）。不录制、不持久化 | medium | `camera-module-and-permission` | **v3 标准** | 串行；⚠️ 改 `LapLiveScreen`，须与线 A redesign-delta 在看板 §6 协调跨 phase 占用 |
| `camera-recording-and-gps-sync` | **体量升级（吸收 review underScoped2）**：VideoCapture 启停 + 文件命名 `$sessionId.mp4` 落 `filesDir/video/` + **录制起点 `recordingStartedAtWallClock=currentTimeMillis()` 在首帧落地回调精确锚定**（同 binary header.startTs/crossing wallClock 时钟域）+ 挂接 LAP_SESSION 生命周期；单测锁死交织线 4 的三项（首帧 wallClock 时机 / PTS→absoluteTsMs 映射 / PTS-wallClock 漂移）。**录制状态机拆分理由：失败静默降级（权限拒/相机被占/录制中来电/锁屏/后台/存储满/kill-9 中断）每种都要兜底且不污染圈速链路 → 录制状态机本身可独立成 round** | **architectural**（新 capability + 跨子系统集成 + 完整录制状态机；若单 round 体量爆炸则拆 `camera-recording-core` + `camera-recording-resilience`） | `camera-preview-in-laplivescreen` | **v3 标准（L1 5-7 轮 或 拆分后各 3-5 轮）** | 串行 |
| `session-video-metadata-persist` | `TelemetrySessionEntity` 加 `videoFilePath:String?` + `videoStartedAtWallClock:Long?`；`AppDatabase` v5→v6 strict migration（沿用 migration3To4 ADD COLUMN nullable 范本）；endSession/finishActiveLapSession 写字段；deleteSession 白名单扩到 `/video/` cascade（当前仅 `/telemetry/` L245）；历史 session null fallback。**前置硬依赖（吸收 review underScoped3）：`restore-strict-migrations-pre-release` 必须先完成，本 round 不再寄生 strict migration 恢复** | medium | `camera-recording-and-gps-sync` + `restore-strict-migrations-pre-release` | **v3 标准** | 串行 |
| `recording-toggle-and-indicator` | **拆分（吸收 review underScoped4）：仅 UI**。`LapLiveScreen` 录视频开关（默认关 opt-in）+ 录制中 indicator（REC 红点/时长，**时长字符串走 Score 字体非 DSEG7，单行 Ellipsis**）。真机端到端验证录制时长==session 时长误差<200ms | medium | `session-video-metadata-persist` | **v3 标准** | 串行 |
| `recording-resource-safety`（视 `camera-recording-and-gps-sync` 是否已含 resilience 决定是否独立） | **拆分（吸收 review underScoped4）：系统资源安全工程**。存储满预检/电量低/温度过高/异常退出 mp4 完整性兜底（kill-9 后损坏检测 + session 一致性修复）/中断降级。**独立量级，不被 indicator UI 掩盖** | medium~large | `session-video-metadata-persist` | **v3 标准** | 串行 |

> **Phase 2 公共协议 MUST NOT 约束（吸收 review governance3）**：录制挂接 MUST 只读 session 生命周期事件（startSession 首帧懒启动 L842 / endActiveLapSession L514 / finishActiveLapSession L561），**绝不修改 `bridgeGpsToLapTiming` 的 `gpsData.timestamp` 处理、绝不修改 binary writer**（A56 + 公共协议不可改边界）。此约束写入 `camera-recording-and-gps-sync` design risks 段。

### Phase 3 视频叠加导出（FUTURE，仅占位）

> 不在本次细拆。整体属 **architectural**（capability 边界扩张 + 重 MediaCodec 渲染管线）。启动时再细拆 ~5 round，Entry = Phase 1 + Phase 2 双 exit。**Phase 3 启动时必须先消化交织线 4（PTS/帧率离散 + 时钟漂移）的取帧策略**。

| 占位 Round | scope 摘要 |
|---|---|
| `video-frame-extractor` | MediaCodec 按 wallClock 提帧 + PTS→absoluteTsMs 映射（消费 Phase 2 `videoStartedAtWallClock` + 交织线 4 的最近邻/插值策略 + 漂移容忍） |
| `overlay-widgets-system` | Gauge/LapTimer/Delta/Sector/Map widget（复用功能二 W2 chart 组件 + 功能一实时 delta 算法） |
| `overlay-realtime-preview` | 实时叠加预览 + 用户调布局 |
| `video-export-pipeline` | MediaMuxer 离线渲染 + 进度/取消/内存压力 |
| `video-export-ui` | 导出屏 + 分享 intent |

---

## 4. 推荐执行顺序 + rationale（提速优先）

### 第一批（worktree 并行，并行性须先经看板 §6 函数级实测确认）

> ⚠️ **吸收 review sequencing4**：旧草案宣称三线「文件零交叉」自相矛盾（unify-lap-count 改 ViewModel/session lifecycle，与 redesign-delta 同碰）。**第一批开工前 MUST 在看板 §6 实测函数级是否重叠，重叠则串行 / 不重叠才并行**。

| worktree 线 | round | 为什么放第一批 | review |
|---|---|---|---|
| **线 A** | `redesign-realtime-delta-projection-search` | 真机已现 -125s 灰值 + 5s step，**若 detail 屏先做、DELTA bug 仍在，用户横屏跑圈持续看错值，体验割裂**；工件齐全直接 apply。**开工前 MUST 补登记看板 §5/§7（当前未登记）+ §6 登记 LapLiveScreen 跨 phase 占用（Phase 2 camera preview 也改它）** | v3 标准 |
| **线 B**（加速通道串清） | `wire-mock-telemetry-to-w1-real-classes` → `fix-lap-debug-mode-sector-chain-test-after-min-count-1` → `unify-lap-count-pairing-semantics`（最后，因它与线 A 同碰 ViewModel，留到线 A 阶段性合回后再做以避冲突） | 三个 small follow-up 走加速通道无需 user 授权，是 detail 屏组屏前置 | 加速通道（Codex 单线，apply 期跑 #3/#14/#16 自查） |
| **线 C** | `improve-test-execution-progress-bar`（**存量待归档，收尾路径**） | 代码全绿，独占 `TrackTechTestExecutionScreen.kt`，趁第一批真机批量验证一起测掉、归档 | 收尾路径（真机+metrics 补写+archive，非加速通道自查） |

### 第二批（必须串行）

**排序修正（吸收 review sequencing2）**：`future-sector-derivation-round` **先于或并入** `lap-detail-screen-with-cursor`，否则 detail 屏先做 1 段废 SectorBar 必然二次返工。

`lap-detail-screen-with-cursor`（核心交付物 1，**关键路径节点**）—— 依赖第一批线 B `wire-mock-telemetry` + sector 派生。design 期必须拍板 R1（accelerationG 派生位置）/R2（SectorBar R7），且 reader 侧填则触发 #16 硬 gate（§3）。
可与独立主题 `wire-perfresult-to-getdatapointsforresult`（独占 `PerformanceResultScreen.kt`）并行。

### 第三批（detail 屏闭环后）

`lap-comparison-screen-with-cursor`（**large**，组件 API 签名级改 + X 轴语义拍板）+ `retire-legacy-perf-charts`（删 legacy chart）。比较屏复用 detail 屏组屏模式但要改 4 组件公共 API，必须串行其后。

### 第四批

`restore-strict-migrations-pre-release`（可更早任意时机插入，但 Phase 2 schema 改之前必须完成）→ `phase1-exit-review`（强制治理 gate，含 W4 补 L2 硬 gate）→ Phase 2 entry（先跑 L0）。

### 相比旧规划快在哪（量化）

| 维度 | 旧规划 | 新版 |
|---|---|---|
| Tier2 两屏 | 串行尾段排队，3.5 周 0 启动 | detail 屏关键路径前置，第一批并行清前置 |
| small follow-up | 每个走全套 ff/L1（2-3 轮 Opus 双线）/apply/L2/真机 | 加速通道（0 子 agent + Codex 单线），省掉每个 round 的 Opus 双线 review 期 |
| 第一批并行度 | 1 线串行 | 3 worktree 并行（须 §6 实测确认） |
| 真机验证 | 每 round 单独排队等授权 | 攒批一次性串行验证 |
| 进度条 round | 卡真机授权悬置 | 并入第一批真机批次一起测掉、归档 |

---

## 5. 治理对齐

### 5.1 review 流程标注

- **加速通道（0 子 agent + Codex 单线，无需 user 显式授权）**：`wire-mock-telemetry-to-w1-real-classes` / `unify-lap-count-pairing-semantics` / `fix-lap-debug-mode-sector-chain-test-after-min-count-1`。加速通道下 apply 启动前 MUST 跑 v3 盲点 **#3（grep pattern 对齐）/ #14（fake DAO 漏 abstract）/ #16（跨 round 共享字段 drift）** 三项自查。
- **收尾路径（非加速通道）**：`improve-test-execution-progress-bar` —— 存量待归档，走真机 gate + metrics.yaml 补写 + archive，不跑新 round 自查。
- **v3 标准**：`redesign-realtime-delta-projection-search` / `lap-detail-screen-with-cursor` / `future-sector-derivation-round` / `wire-perfresult-to-getdatapointsforresult` / `retire-legacy-perf-charts` / `restore-strict-migrations-pre-release` / **全部 Phase 2 round**。其中 `lap-comparison-screen-with-cursor`（large，L1 3-5 轮）/ `camera-module-and-permission`（large）/ `camera-recording-and-gps-sync`（architectural，L1 5-7 轮或拆分）。

### 5.2 真机验证 gate（串行硬约束）

- **同时刻只能 adb install 一个 apk**；准备真机验证时 session MUST 告知 user 当前 round/apk/场景，等 user 授权再 install。
- **默认设备**：接收端真机华为 `8KE0219522008434`；**V2 视觉相关 round MUST 在小屏 vivo V2405A 验证不换行**——detail/comparison 屏 + Phase 2 录制 indicator + retire-legacy-perf-charts（触碰 G round 视觉）都是视觉相关，**小屏 gate 必走**。
- **攒批策略**：第一批（DELTA 修复 + 进度条）攒一起验证；detail/comparison 屏各自闭环后单独验证（chart canvas 小屏 cursor 可点性必须真机验）；Phase 2 exit 要求录制时长==session 时长误差<200ms。

### 5.3 性能 / 资源风险认领（吸收 review underScoped5）

- **25Hz 高频数据无降采样/分层存储链路**（已核实 binary 按 25Hz 写、chart 全量 O(n) 渲染、reader 全量读）。长 session（数千 sample）真机滑动卡顿/OOM 风险**不能只列 risk 无人认领**：MUST 在 `lap-detail-screen-with-cursor` design 期评估是否需 downsample 兜底/虚拟化渲染，若推迟则显式立 future round `chart-downsample-virtualization` 并在 detail 屏 §10 backlog link，不留悬空 risk。

### 5.4 看板维护 + 登记缺口

- **redesign-delta 未登记看板 §5/§7（已核实）**：开 round 前 MUST 补登记独占路径，并在 §6 登记 `LapLiveScreen` 跨 phase 占用（Phase 2 camera preview 也改它，存在 rebase 冲突隐患）。
- 每启动新 round 前看 §5 登记表研判独占路径；开码期共享文件改在 §6 登记；合回后更新 §5 状态 + §7 Phase 治理表。
- Phase 2 启动时评估 memo #10 `automate-design-drift-detection`（F1 #17 governance root-cause）。

### 5.5 Phase exit 治理留痕缺陷（吸收 review governance5）

- `e2a42a1` 已核实为 **message-only 治理标记 commit（无文件改动，不携带 metrics.yaml）**；看板 + memo 受 `*.md` exclude 不进 git。
- 风险：若 Phase exit 合规标准要求 disposition 留痕进 git，则 `phase1-exit-review` 同样会变 message-only 无可审计痕迹。**`phase1-exit-review` 立项时 MUST 先确定 disposition 落点**：commit body 全文 or 纳入 git 的非 .md 治理文件（如 `openspec/phase-exit/phase1.yaml`）。

### 5.6 存量 round vs 加速通道 round 治理状态区分（吸收 review governance2）

- `improve-test-execution-progress-bar` = **存量待归档**（apply 已完成，tasks 仅剩真机+archive），不是「加速通道新立项 round」，治理状态走收尾路径。混淆两者会让它错跑 apply 期 #3/#14/#16 自查。

### 5.7 W4 无 L2 trail 硬 gate（吸收 review governance4）

- W4 `wire-laptime-to-gps-filter` 是 Phase 1 唯一 mimo 跳过 L2 的归档 round。**`phase1-exit-review` 硬 gate：W4 补跑 L2 或 user 显式豁免留痕**，否则带无 L2 trail 归档 round 跑 Phase exit 不合规。

### 5.8 push 顺序约束（预排，减少 user 决策负担）

- **当前本地领先远端 19 commit**（已核实，非 20），全部待 push。远端 kt-format-checker 逐条验证，顺序错或依赖倒置整批 reject，**push 顺序由 user 拍板**。
- **预排建议**：(1) 先 push 已归档 Phase 1 底座批：`e2a42a1`(Phase 0 exit) → W1/W2/W3/W4 归档链 → phase1-hardening 三 commit → unify-perftest-anchor 两 commit。(2) 第一批新 round 闭环后按主区 ff-only 合回时序追加 push。
- worktree 完成可独立编译里程碑立即 ff-only 合回 `feature/track-tech-v2`，主区 `git pull --rebase` 跟上；Codex review 只看主干 commit。

---

## 6. 里程碑（三 track 收敛关键节点）

| 里程碑 | 达成条件 | 涉及 round | 用户可见价值 |
|---|---|---|---|
| **M1 · 实时 DELTA 不再灰值** | redesign-delta apply + 真机 4 边界无 -125s/跳变 + 交织线 5 收敛决策落地 | `redesign-realtime-delta-projection-search` | 横屏跑圈实时秒差可信 |
| **M2 · 单圈详情屏能回放** | sector 派生先行 + detail 屏组装 4 chart/map + 游标 scrub 跨图联动 + 真机小屏验证 + accelerationG 接 smoother 不空 + SectorBar 多段 | `future-sector-derivation-round` → `lap-detail-screen-with-cursor`（+ 前置 wire-mock / unify-lap-count） | **功能一+功能二首次端到端可用**：点圈→看速度/加速度/轨迹曲线+游标 |
| **M3 · 多圈比较可用** | comparison 屏组件 API 改 gridIndex 入参 + X 轴语义拍板 + 多圈叠绘 + 共享光标 + delta + 真机验证 | `lap-comparison-screen-with-cursor`（large） | 圈与圈对比分析 |
| **M4 · Phase 1 exit** | 两屏 archived + 全部 deferred memo disposition + W4 补 L2 + 留痕落点确定 + exit commit | `phase1-exit-review` | Phase 1 合规闭环，解锁 Phase 2 |
| **M5 · 视频录制 + 同帧对齐** | L0 拍板横竖屏/音频 → camera 模块+权限 → preview → record + `videoStartedAtWallClock` 同时钟域 + 单测锁死首帧 wallClock/PTS 映射/漂移 + 录制时长==session<200ms + 资源安全兜底 | Phase 2 全 round | **功能三录制可用，且为 Phase 3 叠加铺好同帧对齐锚点** |
| **M6（future）· 叠加导出** | Phase 3 占位，Phase 1+2 双 exit 后细拆，先消化交织线 4 取帧策略 | — | HUD 烧录视频导出分享 |

---

## 7. 功能扩展 gap（future 占位，吸收 review featureGap）

| gap | 现状 | 处置 |
|---|---|---|
| **赛道扩展（服务端下发 / 轨迹生成编辑）** | 仅 TFIC/博裕 loop 两条预置（项目记忆 track_first_phase_preset_reusable）。**比多圈比较更影响功能一可用范围** | future 占位 `track-source-expansion`（Phase 1 后期或独立 phase 评估），旧草案彻底缺席 |
| **视频/binary 大文件存储配额 + 历史清理 UI** | deleteSession 白名单仅 `/telemetry/`（L245）；视频 100-200MB/分钟 | future 占位 `storage-quota-and-cleanup-ui`（Phase 2 后期）：存储总量监控 + 老 session 清理 + 用户可见占用，否则长期撑爆存储 |
| **音频录制 RECORD_AUDIO** | 路线图未拍板 | **Phase 2 L0 必答项**（§3 Phase 2），不悬空 |
| **录制方向（横屏 vs 竖屏）** | LapLiveScreen 强制 LANDSCAPE（L80） | **Phase 2 L0 必答项**，不替用户假定横屏 |

---

## 8. 附录：本路线图吸收的对抗式 review 改进点

> review verdict：「整体可执行但带多处需在 design 期补硬 gate 的隐患，不能直接照旧草案第一批开工。」诊断与提速策略方向获认可，底座盘点经核实属实。以下 22 条全部吸收。

**missingIntegrations（4 条）**：
1. 帧率/PTS 离散度 vs 25Hz 映射契约 → 集成点地图交织线 4（最近邻/插值 + PTS-wallClock 漂移，Phase 2 单测锁死）。
2. 跨圈共享光标数据契约断层 → comparison 屏升级 large，组件 API 引入 gridIndex 入参（§1.2/§3）。
3. 实时 DELTA reference vs 离线 LapAlignment 两套数据源未收敛 → 交织线 5（redesign-delta design 期记决策）。
4. 比较屏 X 轴 elapsedMsInLap（时间）vs alignByDistance（距离）冲突未拍板 → comparison 屏 design 必答（§3）。

**sequencingIssues（4 条）**：
5. redesign-delta 未登记看板 §5/§7 + 与 Phase 2 抢 LapLiveScreen → §5.4 开工前补登记 + §6 跨 phase 占用登记。
6. future-sector-derivation 排序倒置致 SectorBar 二次返工 → 排序修正前置/并入 detail 屏（§4 第二批）。
7. wire-perfresult 与归档 G round 文件交叉低估 → 拆成「切 reader」+「retire-legacy-perf-charts」两 round（§3）。
8. 第一批「文件零交叉」自相矛盾（unify-lap-count 与 redesign-delta 同碰 ViewModel）→ §4 改「须 §6 函数级实测确认」+ unify-lap-count 排线 B 末尾。

**governanceViolations（5 条）**：
9. detail 屏 reader 侧填 accelerationG/sector 触发 #16+F1#17 只标「视 design」→ 改写成 design 期硬 gate（§3）。
10. improve-test-execution-progress-bar 混淆存量 round vs 加速通道 → §5.6 改归收尾路径。
11. camera-recording 触碰 session lifecycle/公共协议边界缺 MUST NOT → Phase 2 公共协议约束段（§3 末）。
12. W4 无 L2 trail 未列 Phase 1 exit 硬 gate → §5.7 硬 gate。
13. e2a42a1 message-only 留痕缺陷被掩盖 → §5.5 phase1-exit-review 须先定 disposition 落点。

**underScopedRounds（5 条）**：
14. camera-permission-and-preview medium 低估 → 拆 `camera-module-and-permission`(large) + `camera-preview-in-laplivescreen`(medium)（§3）。
15. camera-recording-and-gps-sync large 低估 → 升 architectural + 可拆 recording-core/resilience（§3）。
16. restore-strict-migrations 寄生 video metadata → 独立提前为 `restore-strict-migrations-pre-release`（§3 Phase 1）。
17. recording UI 与资源安全合一 → 拆 `recording-toggle-and-indicator` + `recording-resource-safety`（§3）。
18. 25Hz 降采样无人认领 → §5.3 detail 屏 design 期评估或立 future round。

**featureGaps（5 条）**：
19. 音频录制未拍板 → Phase 2 L0 必答 + §7。
20. 录制横竖屏方向未 L0 锁 → Phase 2 L0 必答 + §7。
21. 赛道扩展彻底缺席 → §7 future 占位 `track-source-expansion`。
22. 大文件存储配额 + 清理 UI 缺失 → §7 future 占位 `storage-quota-and-cleanup-ui`；memo #5 legacy chart 删除从「评估」升级为「执行」round `retire-legacy-perf-charts`。

**次要事实漂移**：旧草案 §0.2 commit 数 20↔19 自相矛盾 → 全文统一 **19**（git rev-list 核实）。
