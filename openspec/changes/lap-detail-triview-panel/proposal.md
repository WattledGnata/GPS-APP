# Proposal: lap-detail-triview-panel

## Why

用户需求(2026-06-05 凌晨,L0 已确认):圈速测试类应用最常用的"三视图"——视频/数据/地图同屏联动——当前是割裂的:`LapDetailScreen`(单圈详情)有数据图表+轨迹地图且共享游标,但**没有视频**;视频在独立的 `LapVideoPlaybackScreen`(从 session 详情圈行尾的播放小图标进入,与数据上下文脱节,入口突兀);且视频回放**无法拖动进度**(ExoPlayer useController=false,播放由圈时间轴状态机自动推进,LapVideoPlaybackScreen.kt:394)。

L0 决议(用户三点确认):
1. 三视图载体 = **LapDetailScreen**;视频面板**条件渲染**(该圈无视频覆盖不放,有则放)。
2. **独立视频回放屏保留**:从详情屏视频面板的**全屏按钮**进入,定位为整机全屏沉浸式播放页 + 继续承担**导出**角色。
3. 面板**拖动排序** + 顺序持久化(记住用户偏好)。

## What Changes

- `LapDetailScreen` 新增**视频面板**(coverage ≠ NONE 时渲染):内嵌 ExoPlayer + 播放/暂停 + **进度条 seek**(任务 #9 并入)+ 全屏按钮(导航 `lap_video/{sessionId}/{lapIndex}`)。
- **游标三联动**:拖图表游标 → 视频 `seekTo(cursorTs - videoStartedAtWallClock)` + 地图点跟随(已有);视频播放/拖进度 → `cursorAbsoluteTs` 回写(节流)→ 图表/地图跟随。复用既有 wallClock 对齐(`videoStartedAtWallClock`)与 `VideoExportClip.lapCoverage` 判定。
- **面板拖动排序**:长按拖动改变面板上下顺序,顺序持久化到 DataStore(per-app 偏好,非 per-lap)。
- **入口收敛**:`LapSessionDetailScreen` 圈行尾的播放小图标退役(统一从圈行 → 单圈详情,视频在详情内;全屏从面板进)。

非目标:LapVideoPlaybackScreen 自身改造(沉浸式现状即满足;其内部 seek 由本 round 视频面板承担主路径,全屏页是否补 seek 另议);session 级详情屏(除入口收敛外)不动;横屏布局。

## Capabilities

### New Capabilities
- `lap-detail-triview`: 单圈详情三视图——条件视频面板、游标三联动、面板排序持久化、入口收敛。

### Modified Capabilities
<!-- lap-detail-screen 既有 spec 的共享游标 requirements 不变(本 round 是给游标体系加一个新参与者);video-overlay-playback 不变(全屏页保留) -->

## Impact

- **代码**:`LapDetailScreen.kt`(面板列表重构为可排序 + 视频面板);新组件 `LapVideoPanel.kt`;新 `LapDetailPanelOrderStore`(DataStore);`LapSessionDetailScreen.kt`(行尾图标退役)。
- **不碰**:Room schema、导出链路、LapVideoPlaybackScreen 主体、overlay 绘制。
- **风险**:详情屏 ExoPlayer 与全屏页 ExoPlayer 生命周期独立(进全屏时面板 player release);LazyColumn 拖拽 reorder 手势与滚动冲突(长按触发规避)。
