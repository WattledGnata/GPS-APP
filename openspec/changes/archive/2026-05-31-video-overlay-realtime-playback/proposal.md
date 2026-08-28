## Why

Phase 2 视频管线第六刀（接 round 1 `camera-module-and-permission` → round 2 `camera-preview-in-laplivescreen` → round 3 `camera-recording-and-gps-sync` → round 4 `session-video-metadata-persist`，均已归档 archive/2026-05-30）。前序 round 已把"录制 + 视频↔遥测时钟锚定 + 视频元数据持久化"地基铺平：用户横屏跑圈时录了一段赛道视频，视频文件路径 + 首帧 wallClock 锚点已落 Room。**但录完的视频在 app 内只能裸播，看不到任何遥测信息** —— 用户最想要的"看回放时画面上叠着当时的速度/圈速/G值/位置"还没有。本 round 落地**手机内实时叠加播放**：app 播放赛道视频时画面四角实时叠加遥测 HUD overlay，随播放进度跳变。**不烧进视频、不导出新文件，纯播放时 Compose 渲染**（导出烧录 mp4 是未来 GL 管线的事）。

**当前 baseline（核实）**：

- **时钟锚点就绪**：`TelemetrySession.videoFilePath: String?` + `videoStartedAtWallClock: Long?`（`core/domain/.../TelemetryModels.kt:50-53`，Room schema v6 已含两字段，`AppDatabase.kt:227` migration 落地）。`videoStartedAtWallClock` 与 binary 样本 `absoluteTsMs` 同时钟域（均 `System.currentTimeMillis()`）。
- **视频↔遥测同步纯函数就绪**：`VideoTelemetrySync`（`feature/test/src/main/.../recording/VideoTelemetrySync.kt`）：`frameWallClock(videoStartedAtWallClock, framePtsMs)` + `findNearestSampleIndex(frameWallClock, sampleWallClocks)`（二分最近邻，边界 clamp，已单测）。本 round 直接消费。
- **遥测样本 reader 就绪**：`TelemetryRepository.getLapTelemetry(sessionId, lapIndex)` 返回 `LapTelemetry`（`samples: List<LapTelemetrySample>`，每条含 `absoluteTsMs / speedKmh / lat / lon / bearingDeg`，按 absoluteTsMs 升序）。整 session 样本可经 `readLapSamples(filePath, startTs, endTs)`（窗口过滤）拿到。
- **G 值无 binary 持久化（关键 gap）**：binary 17-byte sample（`GpsBinaryFormat.kt:62`）只存 tsDelta/lat/lon/speed/bearing/flags，**无加速度字段**；所有 reader 路径 `accelerationG` 写死 `null`（`TelemetryRepository.kt:356` 等）。G 值 MUST **离线由相邻样本 speed 差分重算**（公式同 `GpsDataFilter.calculateAcceleration`，`GpsDataFilter.kt:172`：纵向 G = Δ(speed)/3.6/Δt/9.8）；横向 G 由 bearing 变化率 × 速度估算。
- **赛道几何就绪（小地图可行性）**：预置 `Track.referencePath: TrackPath`（`feature/test/.../model/track/TrackPath.kt`）= `points: List<GeoPoint>`（lat/lon 轮廓 polyline）+ `startFinishGate.line`。`TrackCatalog.getTrack(trackId)` 解析（详情屏已用）。投影 lat/lon→画布可参考 `RealtimeDeltaCalculator` 的本地米投影思路。
- **圈速数据源就绪**：当前圈 + 最佳圈差值可经 `getLapTelemetry` 的 `lapStartWallClock` / `lapDurationMs` + session `bestLapMs`（`TelemetrySession.bestLapMs`）+ `RealtimeDeltaCalculator.projectDelta`（投影到 best 圈 polyline 算实时秒差，已 stateless O(n)）离线重算。
- **session 详情屏就绪**：`LapSessionDetailScreen.kt`（`feature/test/.../ui/tracktech/`，M2 屏）用 `var session by remember{mutableStateOf(null)}` + `LaunchedEffect` 异步加载；导航宿主 `TrackTechAppShell.kt:110` NavHost（路由如 `lap_detail/{sessionId}/{lapIndex}`）。
- **media3/ExoPlayer 缺失**：当前 gradle（`feature/test/build.gradle.kts` + `gradle/libs.versions.toml`）**无 media3/ExoPlayer 任何 artifact**（grep 0 命中）；需新增。

**用户场景**：用户进 session 详情屏，看到一个录了视频的 session 显示"播放带数据的视频"按钮 → 点击进入横屏播放屏 → ExoPlayer 播放原始视频，画面四角实时叠加：左上速度（DSEG7 七段字体）、左下当前圈计时 + 与最佳圈差值（Score 斜体）、右上 G 值（横向/纵向）、右下赛道小地图当前位置点（若一期做）。overlay 随播放进度（含 seek / 暂停）实时跳变，中间留出视频画面。退出播放释放 player。**全程不产生任何新文件**。

## What Changes

- **新增 media3/ExoPlayer 依赖**：`gradle/libs.versions.toml` 加 `media3 = "1.3.1"`（与 compileSdk 34 兼容的稳定版）+ `androidx-media3-exoplayer` / `androidx-media3-ui` 两 artifact；`feature/test/build.gradle.kts` 加两条 `implementation(libs.androidx.media3.*)`。**首次构建需经代理下载**（代理已配 `~/.gradle/gradle.properties`，round 1 为 CameraX 已验证可下）。
- **新增 overlay 遥测离线计算 use-case**（纯函数，`feature/test/.../usecase/VideoOverlayTelemetry.kt`）：输入 `LapTelemetry` 全样本 + 当前 `frameWallClock`，输出当前帧 overlay 数据（speed / 当前圈 elapsed + delta / 纵横向 G / 当前位置 lat-lon-投影点）。G 值离线差分重算；delta 复用 `projectDelta`。可单测。
- **新增播放屏 Composable**（`feature/test/.../ui/tracktech/LapVideoPlaybackScreen.kt`）：`AndroidView` 包 media3 `PlayerView`（垫底）+ Compose Canvas/角标 overlay（浮上层）；强制横屏锁（同 LapLiveScreen 范式）；`DisposableEffect` 管 ExoPlayer 生命周期（创建 / `setMediaItem(videoFilePath)` / `release`）；**轮询机制**随 `player.currentPosition` 推进刷新 overlay 状态（详见 design Decision 2）。
- **session 详情屏加播放入口**：`LapSessionDetailScreen.kt` 仅当 `session?.videoFilePath != null` 时显示"播放带数据视频"入口（新 `item`），点击 `navController.navigate("lap_video/$sessionId")`。M2 教训：async 加载分支用 if/else，**禁 early-return**。
- **NavHost 加路由**：`TrackTechAppShell.kt` NavHost 加 `composable(route = "lap_video/{sessionId}")` → `LapVideoPlaybackScreen`。
- **角标 overlay 视觉**（Track Tech V2 严守）：速度走 DSEG7（`MechanicalHero/Medium`）、圈速时间走 Score 斜体（`ScoreHero/Medium`）、配色 `TrackTechColors`、所有 Text `maxLines=1 + Ellipsis`；四角布局（详见 design 视觉段）。

**一期取舍（小地图）**：见 design Decision 3 —— **建议小地图一期做**（几何就绪 + 投影简单），但若 apply 期投影/画布精度踩坑，tasks 标注小地图为可拆 follow-up 的独立阶段（不阻塞速度+圈速+G值三角标先上）。

**不做（§10 backlog）**：烧录导出 mp4（GL 管线，未来 round `video-overlay-export-burn-in`）；多圈视频拼接 / 章节跳圈；overlay 自定义布局 / 字段开关（一期固定四角）；非 LAP_SESSION（加减速测试）视频 overlay。

## Impact

- 改 `gradle/libs.versions.toml`（加 media3 版本 + 2 artifact）+ `feature/test/build.gradle.kts`（2 条 media3 依赖）+ `LapSessionDetailScreen.kt`（播放入口 item）+ `TrackTechAppShell.kt`（NavHost 路由）；新增 `LapVideoPlaybackScreen.kt` + `VideoOverlayTelemetry.kt`（+ 小地图若一期做 `TrackMiniMap.kt`）。
- **公共协议 0 改动**；GPS 接收链路 / binary writer / crossing / 圈速 LapTimingEngine **0 改动**（纯消费已有 reader API）；**Room schema 0 改动**（v6 已含 video 字段，纯读）。
- **新增第三方依赖**（media3）：复杂度判定因此**强制升级 medium 流程**判定（"引入新 module / 新依赖"例外场景）；但 road-test-first 模式下仅意味更谨慎的 CC 自审 + FileLogger 埋点，不调 Opus 子 agent（user 已授权该批走 road-test-first）。
- **真机依赖**：ExoPlayer 横屏播放 + Compose overlay 与视频帧同步精度 + seek/暂停 overlay 跟随 + 大样本（几千条）内存 + 小地图投影必须真机验（模拟器播放/性能不可靠）。road-test-first 攒批路测；本 round 是 V2 视觉相关 → 小屏（vivo V2405A）gate 必走。
