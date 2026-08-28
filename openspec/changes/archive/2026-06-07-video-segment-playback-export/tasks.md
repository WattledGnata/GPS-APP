# Tasks: video-segment-playback-export

> worktree 内实施;gradle 8.9 `--offline`;road-test-first(无 Codex/Opus);新增 .kt 首行 `// @IgnoreFormatCheck`。

## 1. 锚点 verify

- [x] 1.1 `grep -n 'videoStartedAtWallClock ?: return null\|videoFilePath == null' feature/test/.../export/LapPlaybackLoader.kt` 命中 :77-78 两 guard。done:loader 改造点锚定。
- [x] 1.2 `grep -n 'setMediaItem' feature/test/.../ui/tracktech/LapVideoPlaybackScreen.kt` 命中 :176 单 item 调用;`grep -n 'currentPosition' 同文件` 列出 playhead 映射点。done:回放屏改造点清单。
- [x] 1.3 `grep -n 'videoStartedAtWallClock' feature/test/.../export/VideoExportPipeline.kt feature/test/.../export/VideoExportService.kt` 列导出映射基准消费点。done:导出改造点清单。
- [x] 1.4 `grep -rln 'VideoSegmentDao' core/ feature/ --include='*.kt' | grep -v .worktrees | grep test` 列全 fake 连锁面(②a 后预期 11 处:9 文件 FakeVideoSegmentDao + VideoSegmentAttachCascadeTest + PerftestOrphanCleanupTest 内)。done:连锁清单。

## 2. domain + data 层

- [x] 2.1 `core/domain/.../model/TelemetryModels.kt` 加 `VideoSegment` data class(id/sessionId/segmentIndex/filePath/startWallClock/endWallClock?/durationMs?/playable?;lapIndex 两字段 domain 暂不暴露——②b 才有生产者)。done:domain model 存在。
- [x] 2.2 `core/domain/.../usecase/VideoSegmentSelector.kt` 新建纯函数(design Decision 1 verbatim)。done:函数 + KDoc。
- [x] 2.3 `VideoSegmentDao` 加 `@Query("UPDATE video_segments SET playable = :playable WHERE id = :id") suspend fun updatePlayable(id: Long, playable: Boolean)`。done:方法存在。
- [x] 2.4 `TelemetryRepository` 加 `getVideoSegments(sessionId): List<VideoSegment>`(queryBySessionId + toDomain map,对齐 :467 模式)+ `updateSegmentPlayable(id, playable)` wrapper(`Log.d("VideoSegment")`)。done:两方法 + map。

## 3. fake 连锁(§1.4 清单驱动)

- [x] 3.1 全部 fake `VideoSegmentDao` 补 `override suspend fun updatePlayable(id: Long, playable: Boolean)`(默认实现:in-memory list 同步更新或 no-op,按测试需要)。done:`:core:data:compileDebugUnitTestKotlin` 过。

## 4. 消费侧切换

- [x] 4.1 `LapPlaybackLoader.load`:`:77-78` 两 guard 改为 `repo.getVideoSegments(sessionId)` → 空时 fallback 旧字段合成单段(防御,理论不触发)→ `selectForWindow(segments, lapStart-3s, lapEnd+3s)`(lead-in/out 常量沿用现状值,grep `3_000` 或等价确认)→ 空选段 return null;`LapPlaybackContext` 加 `segments: List<VideoSegment>`,旧 `videoStartedAtWallClock` = 选中首段 start。FileLogger 选段锚点(design Decision 6)。done:loader 编译过 + 行为兼容。
- [x] 4.2 `LapVideoPlaybackScreen`:`:175-176` 改 `setMediaItems(ctx.segments.map { MediaItem.fromUri(it.filePath) })`;playhead wallClock 映射源改 `ctx.segments[player.currentMediaItemIndex].startWallClock + player.currentPosition`(越界防御 getOrNull fallback 首段);段切换 FileLogger。done:多段播放编译过。
- [x] 4.3 同屏加 `Player.Listener`:`onRenderedFirstFrame` → 当前段 playable==null 则 IO 协程 `updateSegmentPlayable(id, true)`;`onPlayerError` → 当前段写 false;listener 在 DisposableEffect 注销。done:回写路径 + 幂等条件。
- [x] 4.4 导出端(`VideoExportPipeline`/`VideoExportService` 按 §1.3 清单):输入从 session 旧字段改 ctx.segments 选段——单段直接用;多段取交集最长段 + `FileLogger.e("VideoExport", "cross-segment lap export degraded: ...")`。done:导出编译过 + 降级日志。

## 5. 测试

- [x] 5.1 新建 `core/domain/src/test/.../usecase/VideoSegmentSelectorTest.kt`:spec Req1 四 scenario(单段内/跨两段/null endWallClock 保守入选反例/无覆盖空)+ 边界(窗口端点==段端点)。done:5+ cases 绿。
- [x] 5.2 新建或扩展 loader 测试(feature/test,若现有 LapPlaybackLoader 无测试则建 contract 级:grep 源码断言 selectForWindow 调用存在 + 旧 guard 已removed)。done:loader 契约锁。
- [x] 5.3 回放映射纯逻辑抽函数可测则补 cases(`segmentWallClock(segments, index, position)`);若内联 Compose 状态难抽,grep contract 锁映射表达式 + 透明声明真机验证。done:映射有锁。
- [x] 5.4 既有全量:core/data + core/domain + feature/test,除已知 case G 红外 0 fail。done:全绿。

## 6. 真机验证(攒批)

- [ ] 6.1 攒批追加场景:②a 场景延伸——录一段→停→再录后,按圈回放**能看到第一段画面**(选段命中);跨段圈(录制横跨停录点)playlist 自动衔接;救援段首播后 sqlite3 查 playable 已回写 1。

## 7. memo 回标 + 归档

- [x] 7.1 `video-segmentation-data-model-deferred.md` 头部状态块更新:②c 已消化(选段/回放/导出降级/playable 回写),剩 ②b + 跨段拼裁 follow-up。done:memo 同步。
- [x] 7.2 看板 §5 登记 + ff-only 合回 + metrics.yaml + 归档(`--yes`)。
- [ ] 7.3 push 待 user 拍板(攒批)。

## 8. apply 期透明声明

> **声明 1(design Decision 3 修订)**:初稿"gap 剪辑跳过"与实际架构(playheadWallClock 圈时间轴主导 + 黑屏 ticker)不符,修订为段感知状态机延伸——`pauseAtEndOfMediaItems=true` 防自动续段,gap 交还既有 ticker,时间轴保真且零新机制。`segmentIndexAt` 用半开区间防段尾粘滞 + STATE_ENDED 强制离段(救援段无 end 兜底)。

> **声明 2(design Decision 4 修订)**:初稿"跨段降级最长段"实测不可达(`isLapFullyCovered` gate 必拦),改明确拒绝带文案;spec Req3 同步重写。

> **声明 3(scope 实测)**:VideoExportPipeline 零改动(loader 把 ctx.videoStartedAtWallClock 设为首选段 start,Pipeline 既有映射直接正确);fake 连锁实测 10 文件(②a 的 9+1)。

## 10. Follow-up backlog

- `video-export-cross-segment-concat`(跨段圈完整拼裁导出;v1 降级最长段)
- playable=false 段 UI 灰显("段已损坏"提示)
- 选段优化:首播回写积累 durationMs 后收紧 null endWallClock 的保守入选
- ②b `video-segment-recording-rotation`(N=3)
