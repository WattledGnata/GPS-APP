# tasks · video-overlay-realtime-playback

> road-test-first 模式：FileLogger 埋点 + 真机攒批路测兜底。apply 启动前 MUST 跑 #3（grep 锚点对齐）/ #14（fake DAO，本 round 无 DAO 改可略）/ #16（跨 round 共享字段，本 round 纯读 video 字段无新增）三项自查。

## 阶段 1：media3/ExoPlayer 依赖接入

- [x] 1.1 `gradle/libs.versions.toml`：加 `media3 = "1.3.1"`（version 块，参 `cameraX="1.3.4"` 风格，L29 附近）+ `androidx-media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }` + `androidx-media3-ui = { group = "androidx.media3", name = "media3-ui", version.ref = "media3" }`（libraries 块，参 `androidx-camera-*` L64-68 风格）。**done**：toml 含两条 media3 library 声明 + version-ref。
- [x] 1.2 `feature/test/build.gradle.kts`：dependencies 块（L51-91）加 `implementation(libs.androidx.media3.exoplayer)` + `implementation(libs.androidx.media3.ui)`。**done**：grep `media3` 在 build.gradle.kts 命中 2 行。
- [x] 1.3 验证依赖经代理可下载：`gradle :feature:test:dependencies --offline` 失败则联网（代理已配 `~/.gradle/gradle.properties`）跑一次 sync 拉 media3。**done**：media3 artifact 进本地 gradle cache（构建不报 unresolved）；若下载失败 FileLogger / 构建日志记录并提醒 user 确认代理可达。

## 阶段 2：overlay 遥测离线计算 use-case（纯函数，可单测）

- [x] 2.1 新增 `feature/test/src/main/java/com/blazepush/feature/test/usecase/VideoOverlayTelemetry.kt`（首行 `// @IgnoreFormatCheck`）：定义 `data class OverlayFrame(speedKmh: Double, lapNumber: Int?, currentLapElapsedMs: Long?, deltaMs: Long?, latG: Double, lonG: Double, lat: Double, lon: Double)`。**done**：data class 编译通过，字段覆盖四角标所需数据。
- [x] 2.2 `VideoOverlayTelemetry`：实现 `fun buildFrames(lapTelemetrySamples: List<LapTelemetrySample>, smoothingWindow: Int = 5): List<OverlayFrame>` —— 离线预算每样本的纵向 G（`(ΔspeedKmh/3.6)/Δt/9.8`，Δt 由 absoluteTsMs 差分）+ 横向 G（`(speedMps × Δbearing_rad/Δt)/9.8`，bearing null → 0）+ 滑动平均平滑（窗口 smoothingWindow）。首样本 G=0。**done**：纵向/横向 G 公式与 design Decision 6 一致；单测验证恒速→G≈0、加速→纵向 G>0、bearing 变化→横向 G>0。
- [x] 2.3 `VideoOverlayTelemetry`：实现 `fun resolveCurrentLap(frameWallClock: Long, lapWindows: List<LapWindow>): LapResolution?` —— frameWallClock 落哪圈窗口（`LapWindow(lapNumber, lapStartWallClock, lapEndWallClock)`），返回 lapNumber + currentLapElapsedMs = frameWallClock - lapStartWallClock；落两圈之间返回 null。**done**：单测验证落圈窗口内返回正确 lapNumber+elapsed、落 pit 间返回 null。
- [x] 2.4 新增 `feature/test/src/test/java/com/blazepush/feature/test/usecase/VideoOverlayTelemetryTest.kt`（首行 `// @IgnoreFormatCheck`）：覆盖 2.2 G 重算（恒速/加速/过弯）+ 2.3 圈窗口判定（落圈内/落 pit 间）+ 空样本/单样本边界。**done**：测试通过；含 ≥1 反例（binary accelerationG=null 输入仍重算出非 null G）。

## 阶段 3：播放屏骨架（ExoPlayer + 横屏 + 生命周期）

- [x] 3.1 新增 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapVideoPlaybackScreen.kt`（首行 `// @IgnoreFormatCheck`）：`@Composable fun LapVideoPlaybackScreen(navController, sessionId, telemetryRepository=koinInject(), trackCatalog=koinInject())`。横屏锁复用 LapLiveScreen `DisposableEffect` + `SCREEN_ORIENTATION_LANDSCAPE`（参 LapLiveScreen.kt:78-86）。**done**：进屏强制横屏，离屏恢复。
- [x] 3.2 `LapVideoPlaybackScreen`：`remember { ExoPlayer.Builder(context).build() }` + `LaunchedEffect(session)` `setMediaItem(MediaItem.fromUri(videoFilePath))` + prepare + playWhenReady；`AndroidView(factory = { PlayerView(it).apply { player = exoPlayer } })` 垫 root Box 最底。`DisposableEffect` `onDispose { exoPlayer.release() }`。**done**：视频可播放；离屏 release（FileLogger 记 release 调用）。
- [x] 3.3 `LapVideoPlaybackScreen`：进屏 `LaunchedEffect(sessionId)` 在 `Dispatchers.IO` 读 `session = getSession(sessionId)` + 全 session 样本（逐圈 `getLapTelemetry` 拼接 或 `readLapSamples(filePath, startTs, endTs)`），置 state；读取中显示 loading（**if/else 分支禁 early-return**，避坑 M2）。FileLogger 记样本数 + 估算内存。**done**：样本读进内存升序 absoluteTsMs；loading→content 用 if/else。

## 阶段 4：overlay 轮询刷新 + 数据接入

- [x] 4.1 `LapVideoPlaybackScreen`：`LaunchedEffect(exoPlayer, framesReady)` 协程 `while(isActive){ position = exoPlayer.currentPosition; frameWallClock = videoStartedAtWallClock + position; idx = VideoTelemetrySync.findNearestSampleIndex(frameWallClock, sampleWallClocks); if(idx != lastIdx){ overlayState = frames[idx]; lastIdx = idx }; delay(33) }`。**done**：overlay 随播放跳变；idx 去抖（相同 idx 不重组）；协程随 Composable 取消。FileLogger 记 position/frameWallClock/idx 抽样。
- [x] 4.2 `LapVideoPlaybackScreen`：delta 接入——best 圈由 session `bestLapMs` 对应圈 `getLapTelemetry(bestLapIndex)` 建 `ReferenceLapIndex`（参 RealtimeDeltaCalculator 现有 reference 构造），轮询每帧 `projectDelta(reference, currentLapElapsedMs, projX, projY)` 算 deltaMs；无 best 圈 / 落两圈间 → delta=null 显 `--`。**done**：delta 复用 projectDelta；降级占位正确。
- [x] 4.3 验证同步口径：FileLogger 抽样记一组 (position, frameWallClock, matchedSample.absoluteTsMs, matchedSample.speedKmh)，真机路测 adb pull 核对 frameWallClock 与样本对齐误差 ≤ 半采样间隔。**done**：埋点就位（同步精度路测依据）。

## 阶段 5：角标 overlay 视觉（Track Tech V2 严守）

- [x] 5.1 `LapVideoPlaybackScreen`：左上 SPEED 角标——`MetricNumber`/`MechanicalHero/Medium`（DSEG7）显速度数字 + `km/h` unit 拆独立 Text（`UiTextSmall`）+ `SPEED` label（`UiTextLabel`）；半透明圆角底板（`TrackTechColors.Surface` alpha~0.55）；全 Text `maxLines=1+Ellipsis`。**done**：速度走 DSEG7、单位拆 unit、贴左上角 padding 12dp。
- [x] 5.2 左下 LAP 角标——`LAP {n}` label（`UiTextLabel/Cyan`）+ 圈速时间 `1:32.457`（`ScoreHero/Medium` **Score 斜体，MUST NOT DSEG7**）+ delta `-0.42s`（`ScoreHero/Small`，负=Green/正=Red 配色，参详情屏 formatDiff）；落两圈间显 `--`。全 Text `maxLines=1+Ellipsis`。**done**：时间/delta 走 Score、配色编码、降级占位。
- [x] 5.3 右上 G-FORCE 角标——`LAT`/`LON` label（`UiTextLabel`）+ G 数字（`MechanicalHero/Small` DSEG7 纯数字）；底板同 5.1。全 Text `maxLines=1+Ellipsis`。**done**：G 数字走 DSEG7、横纵向分行。
- [x] 5.4 V2 自检：grep 播放屏全部 `Text(` 确认每处带 `maxLines = 1` + `overflow = TextOverflow.Ellipsis`；grep 确认时间字符串无 Mechanical、速度/G 数字无 Score（字体映射对照 design 视觉段表）。**done**：grep 自检通过（V2 约束 0 违反）。

## 阶段 6：赛道小地图（一期做，独立可拆阶段）

> Decision 3：几何就绪 + 投影简单 → 一期做；若投影/朝向/比例踩坑超预算，CC 主会话判定降级为 follow-up round `video-overlay-track-minimap`（留 §10 backlog）。

- [x] 6.1 新增 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackMiniMap.kt`（首行 `// @IgnoreFormatCheck`）：`@Composable fun TrackMiniMap(points: List<GeoPoint>, currentLat: Double, currentLon: Double, modifier)` —— 等距矩形投影（`x=(lon-lon0)*cos(lat0)`，`y=(lat-lat0)`）+ 等比缩放归一化到 Canvas bounds + padding（保比例不拉伸，正北朝上）。**done**：投影纯函数可单测（边界点投影到 canvas 角）。
- [x] 6.2 `TrackMiniMap`：`Canvas` drawPath 画赛道轮廓 polyline（`BorderAlpha60`）+ drawCircle 画当前位置高亮点（`Cyan`/`Green`，由 currentLat/Lon 同投影）。**done**：轮廓 + 当前点绘制；当前点随播放跳变。
- [x] 6.3 `LapVideoPlaybackScreen` 右下接入小地图——`TrackCatalog.getTrack(session.trackId)?.referencePath?.points`；trackId 解析不到 / 点 < 2 → 隐藏小地图角标（或占位），**其余三角标正常**（几何 null/空 gate）。**done**：有几何画图、无几何降级不崩；其余角标不受影响。
- [x] 6.4 新增 `feature/test/src/test/java/com/blazepush/feature/test/ui/tracktech/TrackMiniMapProjectionTest.kt`（首行 `// @IgnoreFormatCheck`）：测投影等比保形 + 边界点 + 空/单点降级（返回不绘制信号，不抛）。**done**：投影测试通过；含反例（点 < 2 不崩）。

## 阶段 7：session 详情屏入口 + NavHost 路由

- [x] 7.1 `feature/test/.../ui/tracktech/LapSessionDetailScreen.kt`：LazyColumn item 区（L173+，建议 OverviewSection 之后 / CompareEntry 附近）加新 `item { if (session?.videoFilePath != null) { VideoPlaybackEntry(onClick = { navController.navigate("lap_video/$sessionId") }) } }`；新增 `@Composable private fun VideoPlaybackEntry`（复用 CompareEntry 风格，CutCornerPanel + UiTextLabel，`maxLines=1+Ellipsis`）。**done**：仅 videoFilePath!=null 显示入口；if/else 禁 early-return。
- [x] 7.2 `feature/test/.../ui/tracktech/TrackTechAppShell.kt`：NavHost（L110）加 `composable(route = "lap_video/{sessionId}") { entry -> LapVideoPlaybackScreen(navController, entry.arguments?.getString("sessionId").orEmpty()) }`（参 lap_detail L188 取参范式）。**done**：路由注册；navigate 可达播放屏。

## 阶段 8：road-test-first 收尾

- [x] 8.1 apply 期 #3 自查：grep 验证工件引用的所有路径/行号/API 形态与生产代码一致（VideoTelemetrySync / getLapTelemetry / projectDelta / TrackPath / TrackTechTypography 字体名 / LapSessionDetailScreen item 区 / TrackTechAppShell NavHost）。**done**：grep 锚点全对齐（偏移则修工件）。
- [x] 8.2 FileLogger 埋点盘点：列本 round 全部 log 锚点（样本读取数/内存、轮询 position/frameWallClock/idx、delta 投影成功/失效、player release、小地图几何 null 降级、media3 下载失败）。**done**：关键状态转移/降级路径埋点齐（road-test-first 唯一诊断手段）。
- [ ] 8.3 真机攒批路测（V2 视觉 → 小屏 vivo V2405A gate 必走）：横屏播放 + overlay 四角随进度跳变 + seek/暂停跟随 + G 值合理 + delta 绿红 + 小地图当前点 + 字体（DSEG7/Score）正确 + 单行不换行 + 退出 release。**HOLD**：等 user 真机授权（本 round 编译过 + apk 就绪即停，不真机）。
- [ ] 8.4 metrics.yaml（归档时）：`review_mode: "road-test-first"` + `review_rounds_l1/l2: 0` + `codex_l1/l2_findings: []`（注 road-test-first 去 Codex）+ `complexity: "medium"`（引入 media3 新依赖强制升级判定，但 road-test-first 不调 Opus 子 agent）+ FileLogger 埋点锚点摘要 + `design_decisions_diverged_during_apply: []`。**HOLD**：归档时写（本 round 不归档）。

## §10 Follow-up backlog

- **`video-overlay-export-burn-in`**（未来）：GL 离屏渲染把 overlay 烧进 mp4 导出新文件（本 round 的 `VideoOverlayTelemetry` 离线计算可复用，只换"画"的一层）。L0 已锁"GL 留导出环节"。
- **`video-overlay-track-minimap`**（条件触发）：仅当阶段 6 apply 期投影/朝向/比例踩坑超预算降级时启用——动态拖尾 + 朝向箭头 + 主轴旋转对齐 + 几何降级时的更优占位。
- **overlay 大 session 降采样**（条件触发）：路测发现超长 session（>1 小时 / >9 万样本）内存/卡顿时，读取期每 N 条取 1 降采样（overlay 不需 25Hz 全精度）。
- **overlay 同步偏移补偿**（条件触发）：路测发现 overlay 与画面同步偏差大（编码首帧延迟）时，加可调偏移参数补偿 frameWallClock。
