# Proposal: video-segment-playback-export

## Why

②a `video-segment-schema`(commit `20e0ec4`,archive 2026-06-06)落地了多段数据模型:救援段/重录段全部入 `video_segments` 表、文件不再被删——但**回放/导出仍读 session 旧字段(=最新段)**,旧段"存而不可见"。2026-06-03 路测事故的体验侧仍未闭环:session `e7ee771d` 圈 1-2 画面在 1.15GB 救援段里,按圈回放仍只对到 5 秒尾段、无画面。本 round(②c,user 2026-06-07 拍板顺序对调先于 ②b)把消费侧切到子表:按圈窗口选段回放/导出,救援段画面真正能看。

消费方现状(2026-06-07 全部 grep 核实):`LapPlaybackLoader.load`(`feature/test/.../export/LapPlaybackLoader.kt:76-78`)读 `session.videoFilePath/videoStartedAtWallClock` 单段;`LapVideoPlaybackScreen:175-176` 单 `MediaItem.fromUri`(media3 ExoPlayer),播放循环已有"覆盖段外 pause+黑遮罩"机制(`:95-97` KDoc);`VideoExportPipeline:93,189` 吃 `ctx.videoStartedAtWallClock` 做 PTS↔wallClock 映射。

## What Changes

- **domain model + reader**:`core/domain` 新增 `VideoSegment` data class(3-class 架构:Entity 已有,domain 层补齐);`TelemetryRepository` 加 `getVideoSegments(sessionId): List<VideoSegment>`(entity toDomain map,对齐 `:467` 既有模式)
- **选段纯函数**:`VideoSegmentSelector.selectForWindow(segments, windowStartMs, windowEndMs)` —— 段 wallClock 区间(`endWallClock` null 视为开区间至无穷,救援段时长未知必须保守入选)与窗口重叠判定,返回按 segmentIndex 升序覆盖段
- **`LapPlaybackLoader` 切子表**:读 segments → 按目标圈窗口(±3s lead-in/out 既有语义)选段;`LapPlaybackContext` 加 `segments: List<VideoSegment>` 字段(旧 `videoStartedAtWallClock` 保留=首选段 start,渐进兼容);全无覆盖段 → 沿用现状 return null("该圈无录像")
- **回放屏多段 playlist**:`setMediaItems(选中段列表)`;playhead wallClock 映射改按段——`segments[player.currentMediaItemIndex].startWallClock + player.currentPosition`;段间 gap 由 ExoPlayer item 切换自然跳过(剪辑语义);既有"覆盖段外遮罩"机制语义不变
- **导出选段**:目标圈窗口选段后,**单段覆盖(常见路径)**直接以该段为输入 + 该段 startWallClock 映射;**跨段圈 v1 降级**——取覆盖时长最长的单段导出 + `FileLogger.e` 警告水印日志,完整跨段拼裁(MediaMuxer concat)推 follow-up `video-export-cross-segment-concat`(占比小、复杂度高,不阻塞主价值)
- **playable 首播回写**:回放首帧渲染成功(`Player.Listener.onRenderedFirstFrame`)→ `updateSegmentPlayable(id, true)`;`PlaybackException` → `false`(DAO 加 `updatePlayable`);UI 对 `playable == false` 段灰显提示"段已损坏"留 follow-up(本 round 仅回写)
- **不做**:UI 入口"有无视频"判断(`LapDetailScreen:184` / `LapSessionDetailScreen:240`)继续读旧字段——双写期间=最新段,语义正确,切换无收益;旧字段双写**不停**(入口依赖 + 回滚安全);②b 按圈轮换;跨段拼裁

## Capabilities

### New Capabilities

(无)

### Modified Capabilities

- `video-segment-model`: 消费侧 requirement 新增——按窗口选段契约(selectForWindow 语义含 endWallClock null 保守入选)、playable 首播回写、多段回放 wallClock 按段映射、跨段导出降级语义。

## Impact

- `core/domain/.../model/TelemetryModels.kt` — 加 `VideoSegment` data class
- `core/domain/.../usecase/VideoSegmentSelector.kt` — 新建纯函数
- `core/data/.../repository/TelemetryRepository.kt` — `getVideoSegments` + `updateSegmentPlayable` + entity map
- `core/data/.../dao/VideoSegmentDao.kt` — 加 `updatePlayable` @Query
- `feature/test/.../export/LapPlaybackLoader.kt` — 切子表选段
- `feature/test/.../ui/tracktech/LapVideoPlaybackScreen.kt` — 多段 playlist + 按段映射 + 首播回写
- `feature/test/.../export/VideoExportPipeline.kt` / `VideoExportService.kt` — 选段输入 + 跨段降级
- 测试:选段纯函数 cases + loader 多段 cases + DAO fake 连锁(②a 的 9+2 文件 fake 加 `updatePlayable` stub)

### 协议兼容性

不涉及公共协议;纯接收端消费侧。

### 复杂度与 review 模式

**medium**(无 schema migration;跨 core/domain + core/data + feature/test 三 module 但消费侧单向)——road-test-first;真机攒批追加:救援段画面可见性验证(②a 攒批场景的延伸:录两段后按圈回放应能看到第一段画面)。
