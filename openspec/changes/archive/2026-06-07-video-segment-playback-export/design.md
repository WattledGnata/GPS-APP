# Design: video-segment-playback-export

## Context

②a 已交付数据层(`video_segments` 表 + append 双写),本 round 切消费侧。关键现状:

- `LapPlaybackLoader.load`(`:70-128`):返回 `Pair<TelemetrySession, LapPlaybackContext>`,`:77-78` 两个 null guard 读旧字段;context 携带单 `videoStartedAtWallClock`
- `LapVideoPlaybackScreen`(873 行):`:133` `ExoPlayer.Builder` remember + DisposableEffect release;`:175-176` `setMediaItem(MediaItem.fromUri(s.videoFilePath!!))`;播放循环 playhead 驱动 overlay,KDoc `:95-97`"playhead 落覆盖段内自然 play / 段外 pause+黑遮罩"
- `VideoExportPipeline`:`ctx.videoStartedAtWallClock + framePtsMs` 做帧↔遥测映射;输入单文件
- media3 ExoPlayer 原生支持 playlist(`setMediaItems` + `currentMediaItemIndex`/`currentPosition`)
- UI 入口判断 ×3(`LapDetailScreen:184`/`LapSessionDetailScreen:240`/`LapVideoPlaybackScreen:175`)读旧字段——双写期间=最新段,语义正确
- ②a 留的 fake 连锁面:9 个测试文件 fake `VideoSegmentDao` + `VideoSegmentAttachCascadeTest`/`PerftestOrphanCleanupTest` 内 2 个,DAO 加方法全部要补 stub

## Goals / Non-Goals

**Goals:**

- 按圈回放/导出从"最新段"升级为"按圈窗口选段"——救援段画面可见(2026-06-03 事故体验闭环)
- 跨段圈回放可用(playlist + 段感知状态机,gap 走既有黑屏 ticker 时间轴保真)
- playable 三态在首播时收敛(null→true/false)

**Non-Goals:**

- 跨段导出完整拼裁(v1 明确拒绝,follow-up `video-export-cross-segment-concat`)
- UI 入口判断切子表 / 旧字段停写(双写期间无收益,留 ②b 后评估)
- playable=false 段的 UI 灰显(本 round 仅回写,展示留 follow-up)
- ②b 按圈轮换分段

## Decisions

### Decision 1: 选段纯函数放 core/domain,窗口重叠判定对 endWallClock null 保守入选

```kotlin
object VideoSegmentSelector {
    fun selectForWindow(segments: List<VideoSegment>, windowStartMs: Long, windowEndMs: Long): List<VideoSegment> =
        segments.filter { seg ->
            val segEnd = seg.endWallClock ?: Long.MAX_VALUE  // 救援段时长未知 → 保守视为延伸到无穷
            seg.startWallClock <= windowEndMs && segEnd >= windowStartMs
        }.sortedBy { it.segmentIndex }
}
```

- null endWallClock 保守入选的理由:救援段(playable=null)恰是最需要被看到的段(本 round 核心动机);漏选 = 事故复发。误选代价 = ExoPlayer 播放该段时长由容器实际时长决定,播完自动切下段,无功能损害
- 替代:null 视为零长跳过 → 拒绝:救援段永远选不中,②a 白保数据
- 替代:选段逻辑内联在 loader → 拒绝:导出端复用 + 纯函数可测(memo §6 测试要求)

### Decision 2: LapPlaybackContext 加 `segments` 字段,旧字段保留=首选段

`load` 改造:`repo.getVideoSegments(sessionId)` → 空列表 fallback 旧字段单段合成(向后兼容 v9 前未迁移数据论上不存在,防御)→ `selectForWindow(segments, lapStart-3s, lapEnd+3s)` → 空结果 return null(语义同现状"该圈无录像")。context `videoStartedAtWallClock` = 选中首段 start(渐进兼容,导出端单段路径直接可用)。

- 替代:context 改 breaking(删旧字段)→ 拒绝:VideoExportPipeline 等多消费点一次性全改,diff 大而无益

### Decision 3: 回放屏多段 = setMediaItems playlist + 段感知 playhead 状态机(apply 期修订:gap 走既有黑屏 ticker,非剪辑语义)

> **apply 期修订记录**:初稿写"gap 剪辑跳过"——实际架构(`LapVideoPlaybackScreen:250-256` KDoc)是 **playheadWallClock 圈时间轴主导**的状态机:覆盖段内视频驱动 / 段外黑屏 ticker 1x 实时推进。多段是该状态机的自然延伸,gap 由既有 blackout ticker 处理(时间轴保真、overlay 继续叠),**零新机制**,比剪辑语义更优且改动更小。方案核心(playlist + 按段映射)不变。

`setMediaItems(selected.map { MediaItem.fromUri(it.filePath) })`;`VideoTelemetrySync` 加纯函数 `segmentIndexAt(playheadWallClock, segments): Int?`(playhead 所在段 index,gap → null);播放循环:
- `segmentIndexAt != null` = withinCoverage:若段 index ≠ `exoPlayer.currentMediaItemIndex` 或刚出黑屏 → `seekTo(segIdx, playheadToVideoPosition(playhead, seg.startWallClock, segDuration))` + play;playhead = `frameWallClock(segments[currentMediaItemIndex].startWallClock, exoPlayer.currentPosition)`
- null = gap/越界:既有 blackout ticker(pause + 1x 推进),零改动
- 段时长:`seg.durationMs ?: 0L`(null 时 `playheadToVideoPosition` 的 `<=0` 分支自然不设上界 clamp,行为安全)

- 替代:ConcatenatingMediaSource → media3 中已被 playlist API 取代,setMediaItems 是正路
- 替代:gap 剪辑跳过(初稿) → 拒绝:需绕开既有 playhead 主导状态机,改动更大且时间轴失真

### Decision 4: 导出跨段 v1 直接 fail 带明确文案(apply 期修订:降级路径不可达)

> **apply 期修订记录**:初稿写"降级取交集最长段"——实测 `VideoExportService:97-103` 既有 gate `isLapFullyCovered` 在圈窗口未被(单)视频完整覆盖时直接 fail("该圈未被视频完整覆盖");跨段圈降级到最长段后必然不完整覆盖 → 被该 gate 拦截,降级路径**不可达**。修订为:选段 >1 时直接 fail,文案"该圈横跨多段录像,导出暂不支持" + `FileLogger.e`,比做一个永远到不了终点的降级更诚实。

导出选段后:`selected.size == 1` 主路径——`sourcePath = seg.filePath`、Clip 窗口计算的 videoStart 用 `seg.startWallClock`(替代 ctx 旧字段),既有 `isLapFullyCovered`/`computeClipRange` gate 原样;`selected.size > 1` → fail + FileLogger.e(含 "cross-segment" 字样);`isEmpty` → 既有"无视频"fail 路径。完整拼裁推 follow-up `video-export-cross-segment-concat`。

- 拒绝"本 round 做完整拼裁"的量化:跨段圈仅出现在"圈中途停录再录"场景(罕见;②b 轮换切段按圈边界切,不产生跨段圈),MediaMuxer 双文件 concat(编码参数/时间戳重排)风险面不划算

### Decision 5: playable 首播回写挂 ExoPlayer Listener

`onRenderedFirstFrame` → 当前 item 对应段 `updateSegmentPlayable(id, true)`(仅 playable==null 的段写,幂等);`onPlayerError` → 当前段 `false`。DAO `@Query("UPDATE video_segments SET playable = :playable WHERE id = :id")`。回写在 IO 协程,失败仅日志。

- 替代:打开屏即探测所有段(MediaMetadataRetriever)→ 拒绝:进屏延迟 + 探测≠可播;首播是真实信号

### Decision 6: 日志锚点(road-test-first MANDATORY)

| 位置 | 手段 | 内容 |
|---|---|---|
| LapPlaybackLoader 选段 | `FileLogger.d("VideoOverlay")`(既有 TAG) | 总段数/选中段 index 列表/窗口 |
| 回放屏段切换 | `FileLogger.d`(既有 TAG=LapVideoPlayback) | item index 切换 + wallClock 映射基准 |
| 导出跨段降级 | `FileLogger.e("VideoExport")` | 降级选段决策 |
| playable 回写 | `FileLogger.d` | segId/结果 |

## Risks / Trade-offs

- [多段 seek 切换瞬间画面闪黑] → seekTo(index, pos) 是 media3 标准跨 item seek,缓冲由 STATE_READY gate 兜住(既有循环已有该 gate);真机攒批观察体验
- [endWallClock null 保守入选导致多余段进 playlist] → 误选段播完自动切下段;首播回写后段获得真实可播性,后续可凭 durationMs 优化(follow-up)
- [DAO 加 updatePlayable 波及 11 处 fake] → ②a 同款编译 gate,清单见 tasks
- [回放屏 873 行改造回归风险] → 改动收敛在 setMediaItems + 映射源 + listener 三点,播放循环骨架不动;feature/test 全量测试 + 真机攒批
- [export 单段路径 ctx.videoStartedAtWallClock=首选段 start 与旧语义(最新段)不同] → 这正是修复本身(按圈选段);旧行为是 bug 不是契约

## Migration Plan

无 schema 改动(DAO 加 @Query 不动 version)。部署即生效;回滚 revert 后回放退回"最新段"行为,子表数据无损。

## Open Questions

(无。)
