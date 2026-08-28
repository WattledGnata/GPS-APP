# video-overlay-playback Specification

## Purpose
TBD - created by archiving change video-overlay-realtime-playback. Update Purpose after archive.
## Requirements
### Requirement: Session 详情屏为有视频的 session 提供"播放带数据视频"入口

`LapSessionDetailScreen` SHALL 仅当 `session?.videoFilePath != null` 时显示一个"播放带数据视频"入口，点击导航到 `lap_video/{sessionId}` 路由；无视频的 session MUST NOT 显示该入口。

实现 MUST 满足：

1. **条件显示**：入口 `item` MUST 仅在 `session != null && session.videoFilePath != null` 时渲染；`videoFilePath == null`（无视频 session / 历史 session）MUST NOT 显示入口、MUST NOT 导航。
2. **导航**：点击 MUST 调 `navController.navigate("lap_video/$sessionId")`；`TrackTechAppShell` NavHost MUST 注册 `composable(route = "lap_video/{sessionId}")` 解析 sessionId 参数并渲染 `LapVideoPlaybackScreen`。
3. **避坑 M2**：详情屏 `session` 异步加载（`var session by remember{mutableStateOf(null)}` + `LaunchedEffect`）的 null→content 分支 MUST 用 if/else，MUST NOT 用 `return@Column` / `return@Box` 等 early-return（M2 真机首点即崩教训，fix 65d6ada）。
4. **V2 视觉**：入口文本 MUST `maxLines=1 + overflow=Ellipsis`；按钮风格复用 `CutCornerPanel` / `TrackTechColors` 惯例。

#### Scenario: 有视频的 session 显示入口并导航
- **GIVEN** 用户进入一个 `videoFilePath != null` 的 LAP_SESSION 详情屏
- **WHEN** 详情屏 `session` 加载完成
- **THEN** "播放带数据视频"入口 MUST 显示
- **AND** 点击该入口 MUST `navController.navigate("lap_video/$sessionId")` 进入播放屏

#### Scenario: 反例——无视频 session 不显示入口
- **GIVEN** 用户进入一个 `videoFilePath == null` 的 session（如纯圈速无录制 / 历史 session）详情屏
- **WHEN** 详情屏加载完成
- **THEN** "播放带数据视频"入口 MUST NOT 显示
- **AND** MUST NOT 提供任何进入 `lap_video` 路由的路径
- **AND** 若实现无条件显示入口导致点击进入空 videoFilePath 播放屏崩溃，该 scenario fail

#### Scenario: 反例——async 加载分支禁 early-return
- **GIVEN** 详情屏 `session` 初值为 null，`LaunchedEffect` 异步加载
- **WHEN** 检查入口渲染所在的 Composable 作用域代码
- **THEN** null→content 分支 MUST 用 if/else 结构
- **AND** MUST NOT 出现 `return@Column` / `return@Box` 等 scope early-return
- **AND** 若实现用 early-return 导致重组时 Stack.pop 崩溃，该 scenario fail（违反 M2 约束）

### Requirement: 视频实时叠加遥测 HUD overlay（纯播放渲染，不烧录不导出）

`LapVideoPlaybackScreen` SHALL 用 media3 ExoPlayer 播放 `session.videoFilePath` 原始视频，并在画面四角实时叠加遥测 HUD overlay（速度 + 圈速计时与最佳圈差值 + G 值 + 赛道小地图当前位置点），overlay 随播放进度（含 seek / 暂停）跳变。本 round MUST NOT 烧录进视频、MUST NOT 导出新文件、MUST NOT 产生任何 mp4 输出。

实现 MUST 满足：

1. **播放器**：MUST 用 `androidx.media3.exoplayer.ExoPlayer` + `androidx.media3.ui.PlayerView`（经 `AndroidView`），`setMediaItem(MediaItem.fromUri(videoFilePath))`；PlayerView 垫在 root `Box` 最底当背景，overlay 浮其上（Compose 屏上合成）。
2. **同步口径**：每刷新 tick MUST 计算 `frameWallClock = videoStartedAtWallClock + player.currentPosition`，经 `VideoTelemetrySync.findNearestSampleIndex(frameWallClock, sampleWallClocks)` 取最近邻样本 index，overlay 数据取该 index 的样本。MUST NOT 用其他时钟口径（如 System.currentTimeMillis() 当前墙钟）。
3. **刷新机制**：MUST 用 Compose 协程轮询 `player.currentPosition`（~30fps / 33ms），每 tick 无条件重算 overlay（覆盖 seek / 暂停态）；轮询协程 MUST 随 Composable 生命周期取消（离屏停轮询）。MUST NOT 仅靠 `Player.Listener` 事件驱动（正常播放 position 推进不触发事件 → overlay 不跟随）。
4. **样本读取**：进屏 MUST 在 `Dispatchers.IO` 一次性读整 session 样本进内存（升序 `absoluteTsMs` 供二分），轮询期 MUST NOT 做 binary IO（每 tick 查内存）；读取中 MUST 显示 loading 态（if/else 分支，禁 early-return）。
5. **G 值离线重算**：binary 无加速度字段（`accelerationG` 全 null），overlay G 值 MUST 由样本离线重算——纵向 G = `(ΔspeedKmh/3.6)/Δt/9.8`，横向 G = `(speedMps × Δbearing_rad/Δt)/9.8`（bearing null 时横向 G=0），并 MUST 做轻量滑动平均平滑（避免 GPS 噪声乱跳）。MUST NOT 因 binary 缺 G 字段而显示 null/崩溃。
6. **圈速 + delta**：当前圈 elapsed MUST = `frameWallClock - 当前圈 lapStartWallClock`（frameWallClock 落哪圈窗口由 crossing wallClock 判定，与 `getLapTelemetry` 同源）；与最佳圈差值 MUST 用 `RealtimeDeltaCalculator.projectDelta` 投影 best 圈算 deltaMs；frameWallClock 落两圈之间（pit/出入场）或无 best 圈时对应字段 MUST 显示降级占位（`--`），MUST NOT 强算错值。
7. **V2 视觉**：速度数字 MUST 用 `MechanicalHero/Medium`（DSEG7）；圈速时间 / delta MUST 用 `ScoreHero`（Score 斜体，MUST NOT DSEG7）；G 值纯数字 MUST 用 `MechanicalHero/Small`（DSEG7）；单位 `km/h` / `s` MUST 拆到独立 unit Text 走非 Mechanical 字体；所有 overlay `Text` MUST `maxLines=1 + overflow=TextOverflow.Ellipsis`；配色 MUST 用 `TrackTechColors` 色板。
8. **生命周期**：离开播放屏 / Composable dispose 时 MUST 在 `DisposableEffect.onDispose` 调 `player.release()` 释放解码器 + 停轮询协程；横屏锁复用 LapLiveScreen `DisposableEffect` 范式。
9. **公共协议边界**：MUST NOT 改 GPS 接收链路 / binary 格式 / crossing / `LapTimingEngine` / Room schema；overlay 数据全经 reader public API（`getLapTelemetry` / `getSession` / `readLapSamples`）+ 离线重算获取。

#### Scenario: 播放视频四角实时叠加 overlay 随进度跳变
- **GIVEN** 用户从有视频 session 详情屏进入 `LapVideoPlaybackScreen`，样本加载完成
- **WHEN** 视频播放，`player.currentPosition` 持续推进
- **THEN** 轮询每 tick 计算 `frameWallClock = videoStartedAtWallClock + currentPosition` 取最近邻样本
- **AND** 左上 SPEED（DSEG7）、左下 LAP 计时+delta（Score 斜体）、右上 G 值（DSEG7）、右下小地图当前点 MUST 随播放实时跳变
- **AND** 中间视频画面区不被 overlay 遮挡

#### Scenario: seek / 暂停后 overlay 同步跟随
- **GIVEN** 视频正在播放且 overlay 显示中
- **WHEN** 用户 seek 到新位置或暂停播放
- **THEN** overlay MUST 立即更新到 seek 后位置对应的样本数据（暂停态也更新一次）
- **AND** MUST NOT 因暂停（isPlaying==false）而停止更新 overlay 到正确帧数据
- **AND** 若实现仅靠 Player.Listener 事件驱动导致正常播放中 overlay 不跟随，该 scenario fail

#### Scenario: 反例——binary 无 G 字段时 overlay 仍显示重算 G 而非 null/崩溃
- **GIVEN** session binary 样本 `accelerationG` 全为 null（binary 格式无加速度字段）
- **WHEN** 播放到某帧，overlay 计算 G 值
- **THEN** 右上 G 角标 MUST 显示由相邻样本 speed/bearing 离线重算的纵向/横向 G 数值
- **AND** MUST NOT 显示 null / `--` / 崩溃
- **AND** 若实现直接读 `sample.accelerationG`（恒 null）导致 G 角标空白或 NPE，该 scenario fail

#### Scenario: 反例——离开播放屏 MUST release player + 停轮询
- **GIVEN** 播放屏正在播放，ExoPlayer 占用解码器，轮询协程运行
- **WHEN** 用户 popBackStack 离开播放屏（Composable dispose）
- **THEN** `DisposableEffect.onDispose` MUST 调 `player.release()`
- **AND** 轮询协程 MUST 随 Composable 取消（停止读 currentPosition）
- **AND** 若实现未 release 导致解码器泄漏 / 轮询协程游离持续耗电，该 scenario fail

#### Scenario: 反例——本 round 无 mp4 输出（overlay 不烧录）
- **GIVEN** 播放屏 overlay 显示中
- **WHEN** 检查本 round 全部代码路径
- **THEN** MUST NOT 存在任何视频编码 / 帧写入 / mp4 输出 / 文件写入路径
- **AND** overlay 浮在视频上仅是 Compose 屏上合成，不进任何文件
- **AND** 若实现引入了 MediaCodec encoder / 帧合成写文件（提前触碰未来 GL 烧录 round），该 scenario fail

### Requirement: 赛道小地图 overlay 由预置 Track 几何投影绘制当前位置点

播放屏的赛道小地图 SHALL 由 `Track.referencePath.points`（预置赛道轮廓 polyline）经等距矩形投影绘制到 Compose Canvas，并按当前帧样本 lat/lon 用同投影标出当前位置高亮点。小地图 MUST NOT 依赖任何地图库 / 底图瓦片 / 联网。

实现 MUST 满足：

1. **几何来源**：MUST 用 `TrackCatalog.getTrack(session.trackId)?.referencePath.points`（`List<GeoPoint>`）；trackId 解析不到 Track / referencePath 点不足 2 个时小地图 MUST 降级（隐藏小地图角标或显示占位），MUST NOT 崩溃。
2. **投影**：lat/lon → canvas x/y MUST 用等距矩形投影（小范围赛道近似）+ 等比缩放保持比例（MUST NOT 拉伸失真）；一期固定正北朝上（不旋转对齐主轴）。
3. **当前点**：当前帧最近邻样本的 lat/lon MUST 用同一投影映射到 canvas，画一个高亮点（`Cyan`/`Green`），随播放跳变。
4. **纯本地**：MUST NOT 引入 Google Maps / osmdroid 等地图库；MUST NOT 联网请求瓦片。
5. **一期范围**：一期画静态赛道轮廓 polyline + 当前位置高亮点即可；动态拖尾 / 朝向箭头 / 主轴旋转留 follow-up（不阻塞本 round）。

#### Scenario: 小地图绘制赛道轮廓 + 当前位置点
- **GIVEN** session 有有效 trackId，`TrackCatalog.getTrack(trackId).referencePath.points` ≥ 2 个点
- **WHEN** 播放到某帧
- **THEN** 右下小地图 MUST 显示赛道轮廓 polyline（等比投影不失真）
- **AND** 当前帧样本 lat/lon 对应的高亮点 MUST 标在 polyline 上并随播放跳变

#### Scenario: 反例——trackId 解析不到 / 几何点不足时降级不崩溃
- **GIVEN** session 的 trackId 为 null 或 `TrackCatalog.getTrack` 返回 null 或 referencePath 点 < 2
- **WHEN** 播放屏渲染小地图角标
- **THEN** 小地图 MUST 降级（隐藏角标或显示"无赛道图"占位）
- **AND** 其余三角标（速度/圈速/G）MUST 正常显示
- **AND** 若实现未做几何 null/空 gate 直接投影导致 IndexOutOfBounds / 除零崩溃，该 scenario fail

#### Scenario: 反例——小地图 MUST NOT 联网 / 引地图库
- **GIVEN** 播放屏小地图显示中
- **WHEN** 检查小地图实现代码路径
- **THEN** MUST NOT import Google Maps / osmdroid 等地图库
- **AND** MUST NOT 发起任何网络请求拉底图瓦片
- **AND** 若实现引入地图库或联网瓦片（违背纯本地播放目标），该 scenario fail

### Requirement: 全屏 LapVideoPlaybackScreen 提供中央播放按钮 + 双击切换的用户暂停控件

`LapVideoPlaybackScreen`（`feature/test/.../ui/tracktech/LapVideoPlaybackScreen.kt`）SHALL 提供两种切换播放/暂停的入口：(a) 中央叠加 IconButton 仅在暂停态可见，(b) 屏幕任意位置双击。两者共同操作同一 `userPaused` state，state 由圈时间轴主导状态机识别并暂停 / 恢复推进。

实现 MUST 满足：

1. **`userPaused` state hoisting**：MUST 在 Composable 顶层加 `var userPaused by remember { mutableStateOf(false) }`。
2. **状态机集成**：圈时间轴主导状态机循环（`while (isActive)` 内）MUST 在循环开头判 `if (userPaused)` 分支：
   - 调 `exoPlayer.pause()`（若 isPlaying）
   - `playheadUiWallClock = playheadWallClock`（state 镜像保持，不推进）
   - `delay(PLAYHEAD_TICK_MS)` 然后 `continue`，跳过覆盖段内/外/段切换/atEnd 逻辑
   - 不推进 `playheadWallClock`（覆盖段外黑屏 ticker advance 也跳过）
   - 不更新 overlay（保持 lastIdx 缓存值）
3. **中央按钮可见性**：MUST 仅在 `userPaused == true` 时渲染中央 `IconButton`：
   - `Modifier.align(Alignment.Center)`
   - `Icons.Filled.PlayArrow` 图标
   - 半透明圆形背景（如 `Color.Black.copy(alpha = 0.4f)` + `CircleShape`）便于在黑屏 / 亮帧上都识别
   - `onClick { userPaused = false }`
4. **双击切换**：MUST 在 Box 顶层（视频垫底之上、IconButton 之下）加 `Modifier.pointerInput(Unit) { detectTapGestures(onDoubleTap = { userPaused = !userPaused }) }`。
5. **进度条不受 userPaused 影响**：底部 Slider 的 onValueChange 写 `seekRequestWallClock` 路径与状态机循环解耦；`userPaused == true` 时 Slider 仍可拖动，恢复播放（userPaused=false）后状态机下一 tick 消费积压的 seek。
6. **进入屏 / 切换段不重置 userPaused**：`userPaused` 状态 hoisted 在屏 Composable 顶层，进圈、加载 segments、段切换都不重置（用户暂停后切段恢复仍是暂停态）。
7. **FileLogger 埋点**：`userPaused` 由 false 翻 true 或 true 翻 false 时 MUST 各埋一条 `FileLogger.d(TAG, "userPaused -> $userPaused via <doubleTap|centerButton>")`。
8. **V2 视觉约束**：中央 IconButton size MUST 显式给定（推荐 `size(80.dp)` + Icon `size(56.dp)`）；图标 tint `Color.White`；不引入 autoSize / 字号自适应。

#### Scenario: 双击屏幕切换播放→暂停，中央按钮出现

- **GIVEN** 用户进入 `LapVideoPlaybackScreen`，视频正在播放（`userPaused == false`，覆盖段内 ExoPlayer 推进 playhead）
- **WHEN** 用户双击屏幕中央或任意非按钮区域
- **THEN** `detectTapGestures(onDoubleTap)` 触发 → `userPaused = true`
- **AND** 状态机循环下一 tick 进入 `if (userPaused)` 分支 → `exoPlayer.pause()`
- **AND** 中央 `IconButton`（PlayArrow 图标）渲染到画面中心
- **AND** `playheadWallClock` 不再推进，画面冻结在暂停瞬间的帧
- **AND** `FileLogger.d` 记录 `userPaused -> true via doubleTap`

#### Scenario: 中央按钮点击切换暂停→播放，按钮消失

- **GIVEN** 用户在 `LapVideoPlaybackScreen` 已暂停状态（`userPaused == true`，中央按钮可见）
- **WHEN** 用户点击中央 `IconButton`
- **THEN** `onClick` 触发 → `userPaused = false`
- **AND** 状态机循环下一 tick 不再进入 `if (userPaused)` 分支 → 走既有覆盖段内/外逻辑
- **AND** 中央 `IconButton` 不再渲染（条件 `userPaused == true` 失败）
- **AND** 视频从暂停瞬间的 `playheadWallClock` 继续播放
- **AND** `FileLogger.d` 记录 `userPaused -> false via centerButton`

#### Scenario: 暂停态下拖 Slider 仍生效，恢复播放从拖到位置继续

- **GIVEN** 用户在 `LapVideoPlaybackScreen` 已暂停（`userPaused == true`），当前 playhead=PH1
- **WHEN** 用户拖底部 Slider 到目标 playhead=PH2
- **THEN** `Slider.onValueChange` 写 `seekRequestWallClock = PH2.toLong()`（状态机循环判 userPaused 提前 continue，不消费 seek）
- **AND** UI 不立即更新画面（仍冻结在 PH1，因为暂停态状态机不消费 seek）
- **AND** 用户双击 / 点中央按钮 → `userPaused = false`
- **AND** 状态机下一 tick 进入 line 361 `seekRequestWallClock?.let` 分支 → 跨段 seek 到 PH2 → 视频从 PH2 播放

#### Scenario: 反例——MUST NOT 让 userPaused 影响段切换 / atEnd 既有语义

- **GIVEN** 状态机循环既有逻辑（覆盖段内/外 / 段切换 / atEnd 停驻）
- **WHEN** contract test 扫描 `LapVideoPlaybackScreen.kt` 状态机循环代码
- **THEN** `if (userPaused) { ...; continue }` MUST 出现在 `while (isActive)` 循环开头（首句之一，在 seekRequest 消费分支之前或之后皆可，但必须在覆盖段判定 / atEnd 判定之前）
- **AND** 既有 `withinCoverage` / `wasWithinCoverage` / `lastTickRealtimeMs` / `tickCounter` 等状态变量 MUST NOT 被新 `if (userPaused)` 分支改写
- **AND** 若实现把 userPaused 判定漏到 line 388 之后，会导致暂停态画面继续推进 playhead（覆盖段外 ticker advance 仍跑），contract test fail

#### Scenario: 反例——MUST NOT 移除 useController=false 切换为 ExoPlayer 原生控制条

- **GIVEN** `LapVideoPlaybackScreen.kt:478` 既有 `PlayerView` 配置 `useController = false`
- **WHEN** 实现新增中央按钮 + 双击手势
- **THEN** `useController = false` MUST 保留（不暴露 ExoPlayer 原生底部控制条，避免与现有底部 Slider / 顶部 ExportButton / 圈时间轴主导状态机冲突）
- **AND** 若实现把 `useController = true`，contract test fail，锁死状态机主导播放语义

