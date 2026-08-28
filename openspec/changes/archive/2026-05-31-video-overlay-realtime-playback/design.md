## Context

Phase 2 视频管线第六刀。前序 round（1-4）已就绪：录制引擎 + 视频↔遥测时钟锚点（`videoStartedAtWallClock` 与 binary `absoluteTsMs` 同时钟域）+ `VideoTelemetrySync` 同步纯函数 + 视频元数据持久化（Room schema v6）。本 round 落地"手机内实时叠加播放"：app 播放赛道视频时画面四角实时叠加遥测 HUD overlay（速度 + 圈速计时与最佳圈差值 + G 值 + 赛道小地图位置点），随播放进度跳变。**纯播放渲染，不烧录、不导出文件**。

复杂度 **medium**（引入新第三方依赖 media3 → 强制升级 medium 判定；改公共 UI 详情屏 + NavHost；新增播放屏 + overlay 离线计算 use-case；不改 schema / 不改公共协议 / 不改 GPS 接收链路）。road-test-first 模式：FileLogger 埋点 + 真机攒批路测兜底，不调 Opus 子 agent。

技术 baseline 核实锚点（apply 期 #3 自查，grep 验证）：

- `feature/test/src/main/.../recording/VideoTelemetrySync.kt`：`frameWallClock(videoStartedAtWallClock, framePtsMs): Long` + `findNearestSampleIndex(frameWallClock: Long, sampleWallClocks: List<Long>): Int`（二分最近邻，空列表抛 IAE，边界 clamp 到 0/last）。
- `core/data/.../repository/TelemetryRepository.kt`：`getLapTelemetry(sessionId, lapIndex): LapTelemetry?`（L334）+ `getSession(sessionId): TelemetrySession?`（L196）+ `readLapSamples(filePath, lapStartTs, lapEndTs): List<TelemetrySample>`（L315）。
- `core/domain/.../model/LapTelemetry.kt`：`LapTelemetrySample(absoluteTsMs, elapsedMsInLap, lat, lon, speedKmh, bearingDeg, accelerationG=null, flags)`；`LapTelemetry(sessionId, lapIndex, lapStartWallClock, lapEndWallClock, lapDurationMs, samples, sectorBoundaries, trackId, trackNameSnapshot)`。
- `core/domain/.../model/TelemetryModels.kt:50-53`：`TelemetrySession.videoFilePath: String?` + `videoStartedAtWallClock: Long?` + `bestLapMs: Long?`（L47）。
- `core/domain/.../usecase/GpsDataFilter.kt:172`：`calculateAcceleration(currentTimestamp, currentOutputSpeed)` = `(Δspeed/3.6) / Δt` m/s²（G 差分公式参考）。
- `feature/test/.../usecase/RealtimeDeltaCalculator.kt:37`：`projectDelta(reference: ReferenceLapIndex, currentLapElapsedMs, currentX, currentY, failoverDistanceM=50f): DeltaProjection?`（投影 best 圈算 deltaMs，stateless O(n)）。
- `feature/test/.../model/track/Track.kt`：`Track(id, name, lengthKm, referencePath: TrackPath, startFinishGate, sectorGates)`；`TrackPath(points: List<GeoPoint>, closed)`；`GeoPoint(latitude, longitude)`。`TrackCatalog.getTrack(trackId): Track?`（详情屏 L116 已用）。
- `feature/test/.../ui/tracktech/LapSessionDetailScreen.kt`：`fun LapSessionDetailScreen` L69-240；`var session by remember{mutableStateOf(null)}` L77 + `LaunchedEffect(sessionId)` L82 异步加载；LazyColumn item 区 L173+（OverviewSection / CompareEntry / 圈列表）。
- `feature/test/.../ui/tracktech/TrackTechAppShell.kt:110`：`NavHost(startDestination="home")`；`composable(route="lap_detail/{sessionId}/{lapIndex}")` L188 范式（`navBackStackEntry.arguments?.getString("sessionId")` 取参）。
- `feature/test/.../ui/tracktech/LapLiveScreen.kt:78-86`：横屏锁 `DisposableEffect` + `SCREEN_ORIENTATION_LANDSCAPE` 范式（播放屏复用）。
- `gradle/libs.versions.toml:29-68`：`cameraX="1.3.4"` + version-ref + `androidx-camera-*` artifact 声明风格（media3 照此加）；`feature/test/build.gradle.kts:51-91` dependencies 块。
- `feature/test/.../ui/tracktech/TrackTechTypography.kt` / `TrackTechColors.kt`：`MechanicalHero*`（DSEG7）/ `ScoreHero*`（Score 斜体）/ 色板。

## Decisions

### Decision 1：渲染管线 = Compose Canvas overlay 叠在 media3 PlayerView 上（vs OpenGL / SurfaceView 自绘 / TextureView+离屏合成）

- **选**：ExoPlayer（`androidx.media3:media3-exoplayer`）+ `androidx.media3.ui.PlayerView`（经 `AndroidView` 嵌入），垫在 root `Box` 最底当背景；overlay 用 Compose（`Canvas` 画小地图 + `MetricNumber`/角标文本）浮上层。HUD 是 Compose 屏上合成，**不进任何文件**。
- **Alt A（拒绝）· OpenGL / GLSurfaceView 自绘 overlay**：GL 管线复杂度高（shader / 纹理 / EGL context），且**只有"导出烧录 mp4"才真正需要 GL**（把 overlay 烧进每帧像素）。本 round 是纯屏上播放，Compose 屏上叠完全够用，引入 GL 是过度工程。
- **Alt B（拒绝）· SurfaceView/TextureView + Canvas 直接 drawText**：要手写 player 进度监听 + 手动 invalidate + Paint 排版，丢掉 Compose 的声明式重组 + V2 字体组件（MetricNumber 等）复用，且与现有全 Compose UI 栈不一致。
- **rationale**：L0 已锁"一期 Compose/Canvas overlay 叠 ExoPlayer，GL 留导出环节"。Box 子元素绘制顺序天然层叠（PlayerView 先绘当背景、overlay Column 后绘浮上，无需 zIndex），与 round 2 LapLiveScreen「PreviewView 垫底 + HUD 浮上」架构一致。media3 是 ExoPlayer 官方现行包（com.google.android.exoplayer2 已停更并入 androidx.media3），1.3.1 与 compileSdk 34 / minSdk 28 兼容。**未来 GL 升级路径**：导出烧录 round 改用 GL 离屏渲染（MediaCodec encoder + GLES 把 overlay 纹理合进每帧），本 round 的 `VideoOverlayTelemetry` 离线计算 use-case（帧→overlay 数据）可原样复用（GL 只换"画"的那一层，"算什么"复用）。

### Decision 2：overlay 数据刷新机制 = 随 ExoPlayer position 轮询（vs Player.Listener 事件驱动 / 帧回调）

- **选**：用 Compose `LaunchedEffect` + 循环 `while(isActive){ position = player.currentPosition; delay(33ms≈30fps); }` 轮询读 `player.currentPosition`，每 tick 算 `frameWallClock = videoStartedAtWallClock + currentPosition` → `findNearestSampleIndex` → 更新 overlay state（`mutableStateOf`），触发 overlay 重组。播放中轮询、暂停时 `player.isPlaying==false` 仍轮询一拍（保证 seek 后暂停态 overlay 也更新）。
- **Alt A（拒绝）· `Player.Listener.onEvents` / `onPositionDiscontinuity` 事件驱动**：listener 只在 seek/状态切换时回调，**正常播放中 position 连续推进不触发任何事件** → overlay 不会随播放跳变（只在 seek 时跳一次），不满足"实时跟随"。
- **Alt B（拒绝）· `PlayerView` 帧渲染回调 / `VideoFrameMetadataListener`**：能拿到每帧精确 PTS，但回调在渲染线程、频率=视频帧率（可能 30/60fps）、要跨线程 post 到 Compose → 复杂且易卡 UI 线程；overlay 25Hz 遥测源精度下 30fps 轮询已绰绰有余（遥测样本本身 ≤25Hz）。
- **rationale**：遥测样本源 ≤25Hz，overlay 30fps（33ms）轮询的视觉精度远超数据精度，人眼无感差异。轮询用 Compose 协程（`LaunchedEffect(player)`）天然随 Composable 生命周期取消（离屏停轮询）。`player.currentPosition` 是廉价主线程读。seek/暂停由"每 tick 无条件重算"覆盖（不依赖 isPlaying）。**精度 caveat**：`frameWallClock` 用 position（相对视频起点）+ `videoStartedAtWallClock`（绝对锚点）；`findNearestSampleIndex` 二分到最近样本，最坏误差 = 半个采样间隔（25Hz→20ms），低于人眼 overlay 跳变感知阈值。FileLogger 埋点记 position/frameWallClock/matchedIdx 供路测核对同步精度。

### Decision 3：赛道小地图一期做（vs 拆 follow-up round）—— 重点评估结论

- **选**：**一期做小地图**（第四角标），但 tasks 把小地图列为**独立可拆阶段**（阶段 6），若 apply 期投影/画布精度踩坑可降级为 follow-up，不阻塞前三角标（速度/圈速/G 值）先上。
- **可行性评估**：(1) **几何就绪** —— `Track.referencePath.points: List<GeoPoint>` 是现成赛道轮廓 polyline，`TrackCatalog.getTrack(trackId)` 详情屏已在用。(2) **投影简单** —— lat/lon → 画布 x/y 用等距矩形投影（小范围赛道 < 几 km，`x = (lon - lon0) * cos(lat0) * R`，`y = (lat - lat0) * R`，再线性归一化到 canvas bounds + padding），无需地图库 / 瓦片 / 联网；与 `RealtimeDeltaCalculator` 已有的"本地米投影"同思路。(3) **当前位置点** —— overlay 当前帧已有 `lat/lon`（最近邻样本），同投影公式映射到 canvas 画一个高亮点。(4) **当前圈轨迹** —— 一期画"静态赛道轮廓 polyline + 当前位置高亮点"，不画动态拖尾（拖尾留 follow-up）。
- **Alt A（拒绝整体拆 follow-up）**：几何 + 投影都就绪且简单，整体拆走会让一期 overlay 缺一角（用户最想要的"位置点在赛道哪"），体验残缺。
- **Alt B（拒绝用地图库如 Google Maps/osmdroid）**：赛道是固定 polyline，不需要底图瓦片 / 缩放 / 联网；引地图库是重依赖 + 联网 + 权限污染，与"纯本地播放"目标背离。
- **rationale**：自绘 Canvas polyline + 投影点是轻量纯 Compose 方案，几何数据齐、公式简单（< 100 行）。**风险兜底**：tasks 阶段 6 独立，apply 期若投影边界 / 朝向（赛道 polyline 可能需旋转对齐）/ 画布比例踩坑超预算，CC 主会话判定降级为 follow-up round `video-overlay-track-minimap`（留 §10 backlog memo），三角标版本仍可路测。**需 user 拍板点**：是否接受"一期小地图只画静态轮廓 + 当前点（无动态拖尾 / 无朝向箭头）"。

### Decision 4：遥测样本读取策略 = 进屏一次性全读进内存（vs 按需窗口查 / 分页流式）

- **选**：进播放屏 `LaunchedEffect` 一次性读整个 session 的全部样本进内存（`List<LapTelemetrySample>` + 预算好 `absoluteTsMs` 升序 `LongArray` 给二分），overlay 轮询时纯内存二分查最近邻，不碰 IO。读取走 `getLapTelemetry` 逐圈拼接，或新增 repository 方法 `readAllLapSamples(sessionId)` 读整 session 窗口（`readLapSamples(filePath, session.startTs, session.endTs)`）。
- **Alt A（拒绝）· 按播放 position 按需窗口查 binary**：每 tick（33ms）查一次 binary IO → 30 次/秒磁盘读，卡顿 + 电量 + GC 压力；且 binary 无 position 索引，每次仍要扫文件头。
- **Alt B（拒绝）· 分页 / 流式加载**：session 样本量级估算 —— 25Hz × 单圈 ~90s × 多圈，假设 10 圈 ≈ 25×900 = 22500 条；每条 `LapTelemetrySample` ≈ 8(ts)+8(elapsed)+8+8(latlon)+8(speed)+~16(nullable Double bearing/accelG)+4(flags) ≈ 60-70 byte → 22500 × 70 ≈ **1.5MB**，远低于移动端内存压力线。分页是过度工程。
- **rationale**：全读进内存换 O(log n) 纯内存二分，内存占用 < 2MB（量级安全），消除播放期所有 IO。**内存上界 caveat**：若极端长 session（如 1 小时连续录 = 25×3600 = 90000 条 ≈ 6MB）仍安全，但 tasks 阶段标注"读取时 FileLogger 记样本数 + 估算内存"供路测监控；若路测发现超大 session，follow-up 加降采样（如读时每 N 条取 1，overlay 不需要 25Hz 全精度）。读取在 `Dispatchers.IO`（同 `getLapTelemetry` 范式），读完置 state 触发 overlay 就绪；读取中显示 loading（if/else 分支，禁 early-return，避坑 M2）。

### Decision 5：角标四字段布局位置（vs 单边竖排 / 顶部横条 / 自定义）

- **选**：**四角标 + 中间留空**（极简角标，L0 已锁）：
  - **左上**：SPEED 速度（km/h）— DSEG7 七段字体（`MechanicalHero/Medium`），数字大、最显眼（驾驶最关注）。
  - **左下**：LAP TIME 当前圈计时 + DELTA 与最佳圈差值 — Score 斜体（`ScoreHero/Medium`），时间字符串 `1:32.457` + 差值 `-0.42s`（差值颜色：负=绿/快、正=红/慢）。
  - **右上**：G-FORCE 横向 / 纵向 G — 纯数字走 DSEG7（`MechanicalHero/Small`），`LAT 0.8` / `LON 0.3`（label 文字用 UiTextLabel，数字 Mechanical）。
  - **右下**：MINI-MAP 赛道小地图 + 当前位置点（若一期做；否则该角留空或放 SATELLITES/HDOP 占位 — 一期建议留空，不硬塞）。
- **Alt A（拒绝）· 顶部 / 底部横条聚合**：横条遮挡画面上下边，违背"中间留视频画面"极简角标定位；横屏下横条更易挡构图主体。
- **Alt B（拒绝）· 单边竖排堆叠**：四字段堆一边，另一边空，视觉不平衡 + 单边过密。
- **rationale**：四角分散最大化中间视频可见区，符合 L0「极简角标，中间留视频画面」。SPEED 放左上（西方阅读视线起点 + 最大字号）；圈速放左下与速度同侧成"驾驶仪表区"；G 值放右上（次要参考）；小地图放右下（空间需求最大的方块状内容放角）。**视觉精致度**：每角标用半透明深色圆角底板（`TrackTechColors` 带 alpha）提升可读性（视频画面亮时数字不糊），底板 + 数字 + label 间距严格按 V2。

### Decision 6：G 值离线重算口径（纵向差分 + 横向估算）—— baseline gap 补偿

- **选**：binary 无 G 字段（`accelerationG` 全 null），overlay 的 G 值 MUST 由样本**离线重算**：
  - **纵向 G（加减速）**：相邻样本 `(speedKmh[i] - speedKmh[i-1]) / 3.6 / Δt(秒) / 9.8`（公式同 `GpsDataFilter.calculateAcceleration`，再 /9.8 转 G）。`Δt` 由 `absoluteTsMs` 差分。
  - **横向 G（过弯）**：由 bearing 变化率估算 `lateralG = (speedMps × Δbearing_rad / Δt) / 9.8`（向心加速度 a = v·ω，ω = 角速度）。bearing 为 null（GPS 静止哨兵）时 lateral G = 0。
- **Alt A（拒绝）· 显示"G 值不可用 / --"**：用户明确要 G 值角标，binary 缺字段不该让功能缺失；speed/bearing 已有，物理上可重算，无需新 schema。
- **Alt B（拒绝）· 给 binary 加 G 字段 + schema migration 重录**：要改公共 binary 格式（A56 边界）+ schema bump + 历史 session 无法补 G，代价大且历史视频仍无 G。离线重算对历史 session 也生效。
- **rationale**：纵向 G 是成熟差分（已有生产公式）；横向 G 是 GPS-only 设备的标准估算法（无 IMU 时业界通用 v·Δheading/Δt）。**精度 caveat**：GPS 噪声会让单帧 G 抖动，overlay MUST 对 G 做**轻量滑动平均**（如最近 3-5 样本窗口）平滑显示，避免数字乱跳；平滑窗口在 `VideoOverlayTelemetry` 离线算时做。spec 反例锁"binary 无 G 字段时 overlay 仍显示重算 G 而非 null/崩溃"。

### Decision 7：圈速计时 + delta 离线重算（vs 实时引擎状态）

- **选**：当前帧的"当前圈 elapsed" = `frameWallClock - 当前圈 lapStartWallClock`（由 `getLapTelemetry` 各圈的 `lapStartWallClock` 判定 frameWallClock 落哪一圈窗口）；"与最佳圈差值" = `projectDelta`（把当前帧投影点投到 best 圈 polyline 算 deltaMs，复用 `RealtimeDeltaCalculator`）。best 圈由 session `bestLapMs` 对应的圈（`getLapTelemetry(bestLapIndex)`）提供 `ReferenceLapIndex`。
- **Alt A（拒绝）· 跑实时 LapTimingEngine 重放**：engine 是 stateful 实时流处理（消费 live GPS 帧 + crossing 检测），回放场景把 engine 重新喂一遍样本复杂且易引入计时分歧；overlay 只需"当前帧在哪圈、用了多久、比 best 快慢"，离线判定更直接。
- **Alt B（拒绝）· 只显当前圈计时不显 delta**：L0 明确要"与最佳圈差值"，delta 是核心信息（用户看回放最想知道这一刻比 best 快还是慢）。
- **rationale**：`projectDelta` 已 stateless O(n) 重设计（round `redesign-realtime-delta-projection-search`），离线逐帧投影正合适。当前圈窗口判定用 crossing wallClock（与 `getLapTelemetry` 同源）。**caveat**：frameWallClock 落在两圈之间（pit / 出入场，非有效圈窗口）时，elapsed/delta 显示 `--`（不强算）；spec 反例锁此降级。best 圈不存在（session 无有效圈）时 delta 显示 `--`，仅显当前圈 elapsed。

## 视觉设计（Track Tech V2 严守 · 角标布局）

横屏 16:9 视频画面，overlay 四角标 + 中间留空。ASCII 草图（横屏）：

```
┌──────────────────────────────────────────────────────────┐
│ ┌─────────────┐                          ┌──────────────┐ │
│ │ SPEED       │                          │ G-FORCE      │ │
│ │  132        │      （视频画面区，       │ LAT  0.8     │ │
│ │  km/h       │        overlay 中间       │ LON  0.3     │ │
│ └─────────────┘        不遮挡）           └──────────────┘ │
│                                                            │
│                                                            │
│ ┌─────────────┐                          ┌──────────────┐ │
│ │ LAP 2  1:32.457                         │ ╱‾‾╲  小地图  │ │
│ │ Δ -0.42s    │                          │ │ ● │  当前点 │ │
│ └─────────────┘                          │ ╲__╱         │ │
└──────────────────────────────────────────────────────────┘
   左下：圈速+delta                            右下：mini-map
```

**字体 / 色板映射（严守 CLAUDE.md V2 约束）**：

| 角标 | 字段 | 内容 | 字体（MUST） | 颜色 |
|---|---|---|---|---|
| 左上 | SPEED label | `SPEED` | `UiTextLabel` | `TextSecondary` |
| 左上 | 速度数字 | `132` | **`MechanicalHero/Medium`（DSEG7）** | `TextPrimary` / 高速 Cyan |
| 左上 | 单位 | `km/h` | `UiTextSmall`（拆到 unit，不混进 DSEG7） | `TextMuted` |
| 左下 | LAP label | `LAP 2` | `UiTextLabel` | `Cyan` |
| 左下 | 圈速时间 | `1:32.457` | **`ScoreHero/Medium`（Score 斜体，MUST NOT DSEG7）** | `TextPrimary` |
| 左下 | delta | `-0.42s` | `ScoreHero/Small`（时间字符串走 Score） | 负=`Green` / 正=`Red` |
| 右上 | G label | `G-FORCE` / `LAT` / `LON` | `UiTextLabel` | `TextSecondary` |
| 右上 | G 数字 | `0.8` / `0.3` | **`MechanicalHero/Small`（DSEG7，纯数字）** | `TextPrimary` |
| 右下 | mini-map | polyline + 点 | Canvas（赛道线 `BorderAlpha60`，当前点 `Cyan`/`Green`） | — |

- **MUST**：所有 overlay `Text` 加 `maxLines=1 + overflow=TextOverflow.Ellipsis`（V2 强制）。
- **MUST**：DSEG7（Mechanical）只吃纯数字 —— `km/h` / `s` 后缀拆到独立 unit Text 走非 Mechanical 字体（CLAUDE.md「含字母后缀的数字若走 Mechanical MUST 把字母拆到 unit」）。
- **MUST NOT**：圈速时间 `1:32.457` / delta `-0.42s` 用 DSEG7（时间字符串走 Score 斜体，CLAUDE.md 明列）。
- **底板**：每角标半透明深色圆角底板（`TrackTechColors.Surface` 带 alpha ~0.55，或 Background 半透明）提升视频亮场下数字可读性；角标贴边 padding ~12dp。
- **精致度**：speed 数字字号最大（驾驶主关注）；G 值次之；label 小字 uppercase；delta 带 +/- 号 + 颜色编码（绿快红慢，呼应详情屏 `formatDiff` 配色惯例）。

## Risks

- **播放卡顿 / overlay 拖累渲染**：30fps 轮询 + 每 tick 二分 + 重组若过重 → 掉帧。**mitigation**：二分纯内存 O(log n) + overlay state 只在 matchedIdx 变化时才更新（去抖：相同 idx 不触发重组）；overlay 数据离线预算（G 滑动平均、投影点）在读取期一次性算好存数组，轮询只查表不算。FileLogger 记轮询 tick 耗时供路测。
- **overlay 与视频帧同步精度**：`videoStartedAtWallClock` 锚点误差 + 编码首帧延迟可能让 overlay 早/晚于画面。**mitigation**：锚点是 round 3 录制首帧回调取的 wallClock（已设计为与样本同时钟域）；最近邻误差 ≤ 半采样间隔（20ms@25Hz）；真机路测对比"画面过弯瞬间"与"G 值峰值"是否对齐，偏差大则 follow-up 加可调偏移补偿。spec 反例锁"frameWallClock 计算口径 = videoStartedAtWallClock + currentPosition"。
- **大样本内存**：见 Decision 4，10 圈 ~1.5MB / 1 小时 ~6MB 均安全。**mitigation**：读取期 FileLogger 记样本数 + 估算内存；超大 session follow-up 加降采样。
- **小地图投影 / 朝向**：赛道 polyline 投影到 canvas 可能朝向不对（正北朝上 vs 赛道主轴）/ 比例失真。**mitigation**：一期等距矩形投影 + 等比缩放保比例（不拉伸）；朝向一期固定正北朝上（不旋转对齐主轴）；若路测觉得别扭，follow-up 加主轴旋转。Decision 3 已留"小地图可降级 follow-up"兜底。
- **media3 联网下载失败**：首次构建需经代理下 media3 artifact。**mitigation**：代理已配 `~/.gradle/gradle.properties`（round 1 CameraX 已验证经代理可下）；若下载失败，apply 期 FileLogger / 构建日志记录，user 介入确认代理可达。**需 user 知情风险点**。
- **公共协议 / 圈速链路边界**：本 round MUST NOT 改 GPS 接收链路 / binary 格式 / crossing / LapTimingEngine / schema。**mitigation**：纯消费 reader API（getLapTelemetry / getSession / readLapSamples）+ G 离线重算 + projectDelta 复用；apply 期 #16 自查 verify 0 触碰 binary writer / schema；新增方法（若加 `readAllLapSamples`）只读不写。
- **横屏锁与播放器交互**：播放屏强制横屏（同 LapLiveScreen）；ExoPlayer release 时机。**mitigation**：横屏锁复用 LapLiveScreen `DisposableEffect` 范式；ExoPlayer 在 `DisposableEffect.onDispose` `release()`（避免泄漏 + 占用解码器）；轮询协程随 Composable 取消。spec 反例锁"离屏 MUST release player + 停轮询"。
