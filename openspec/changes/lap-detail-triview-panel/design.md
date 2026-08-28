# Design: lap-detail-triview-panel

## Context

- `LapDetailScreen.kt`:LazyColumn 5 个 item(Overview/SPEED/ACCEL/SECTORS/TRACK),共享 `cursorAbsoluteTs: Long?`(hoist 单一真相,line 91);telemetry 含 `lapStartWallClock/lapEndWallClock`。
- 视频侧:session(`TelemetryRepository.getSession`)含 `videoFilePath/videoStartedAtWallClock`;覆盖判定 `VideoExportClip.lapCoverage(lapStart, lapEnd, videoStart, videoDurationMs)` → NONE/PARTIAL/FULL(VideoExportClip.kt:80);wallClock↔视频位置:`positionMs = wallClock - videoStartedAtWallClock`。
- `LapVideoPlaybackScreen`(lap_video 路由):ExoPlayer、四角 overlay、导出按钮;无 seek UI——保留为全屏沉浸页。
- 入口现状:LapSessionDetailScreen 圈行尾播放图标(line ~256)→ lap_video。

## Goals / Non-Goals

**Goals:** 视频/数据/地图同屏、游标三联动(双向)、视频面板 seek、面板排序持久化、入口收敛。
**Non-Goals:** 全屏页改造、横屏、per-lap 排序、session 详情其余改动。

## Decisions

### Decision 1: 视频面板为游标体系的"对等参与者",双向同步经同一 cursorAbsoluteTs

- 图表→视频:`LaunchedEffect(cursorAbsoluteTs)` 中 `exoPlayer.seekTo(cursor - videoStart)`(仅用户拖图表时;clamp 到 [0, duration])。
- 视频→图表:播放中 ticker(~10Hz)读 `currentPosition` 回写 `cursorAbsoluteTs = videoStart + position`;拖进度条同。
- **回环抑制**:来源标记(`cursorSource: CHART/VIDEO`)——视频回写的 cursor 变化不再触发 seekTo(否则 seek→position 微差→回写→再 seek 抖动)。简单实现:seekTo 仅在 `cursorSource == CHART` 时执行。
- Alternatives:(a) 视频独立进度不联动——三视图核心价值即联动,拒绝;(b) 双 state 互相 observe——回环更难控,拒绝;(c) 单一 cursor + 来源标记(选)。

### Decision 2: 条件渲染 = coverage ≠ NONE(复用 lapCoverage)

进屏 LaunchedEffect 读 session(videoFilePath/videoStartedAtWallClock)+ 视频 durationMs(MediaMetadataRetriever 或 ExoPlayer onReady 后读);无视频/NONE → 不渲染视频面板(列表自然少一项)。与 LapSessionDetailScreen 行尾图标的既有判定同源(该图标本 round 退役,判定逻辑迁来)。

### Decision 3: 面板排序 = 长按拖动 + DataStore 持久化(per-app)

- 面板 id 枚举:VIDEO/OVERVIEW/SPEED/ACCEL/SECTORS/TRACK;默认顺序如上(视频在顶)。
- 拖拽:LazyColumn item 长按进入拖动(detectDragGesturesAfterLongPress),拖动中计算目标槽位实时交换,松手落定 + 持久化;无视频时 VIDEO 项不渲染但顺序键保留(下次有视频的圈仍按偏好)。
- 持久化:`lap_detail_panel_order` 字符串(逗号分隔 id)存 Preferences DataStore(新 store 或挂现有 UserProfile store——**用现有 UserProfileRepository 的 DataStore 实例加 key**,避免新建 store 文件)。
- Alternatives:(a) ↑↓按钮排序——用户明确说"拖动",拒绝;(b) per-lap 顺序——记忆负担反而高,拒绝;(c) per-app 长按拖动(选)。

### Decision 4: 详情屏 ExoPlayer 生命周期独立,进全屏即释放

面板内 `remember + DisposableEffect` 管理 ExoPlayer(onDispose release);点全屏 → navigate(lap_video) → 面板随 NavBackStackEntry STOP 暂停(返回恢复位置:cursorAbsoluteTs 仍在,onResume seek 回去)。两屏两实例不共享(共享 player 跨 NavBackStackEntry 生命周期复杂度高,收益小)。

### Decision 5: 入口收敛仅删行尾图标

LapSessionDetailScreen 圈行尾播放图标删除;行点击 → lap_detail(不变)。lap_video 路由保留(全屏按钮使用)。"删除视频"按钮(session 级)不动。

## Risks / Trade-offs

- **拖拽与 LazyColumn 滚动手势冲突**:长按阈值触发拖拽规避;实现若超预期复杂,降级为"拖拽柄(handle)区域拖动"——tasks 单列,不阻塞核心三联动先行合入。
- **PARTIAL 覆盖**:圈头/尾无视频段——seek clamp 到覆盖区间,图表游标在覆盖外时视频停在最近边界帧(与全屏页 playhead 状态机语义一致的简化版);透明接受。
- **双 ExoPlayer 内存**:同屏仅一实例(全屏时面板已 STOP),峰值单实例,无叠加。
- **25Hz 回写重组**:视频→cursor 回写 10Hz 节流,图表重组可控(图表自身拖动本就 25Hz)。
