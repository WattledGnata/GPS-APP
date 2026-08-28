# Tasks: lap-detail-triview-panel

## 1. 锚点自检

- [x] 1.1 grep:`grep -n "cursorAbsoluteTs" LapDetailScreen.kt`(共享游标 hoist ~line 91);`grep -n "lapCoverage" VideoExportClip.kt`(~line 80);`grep -n "lap_video" LapSessionDetailScreen.kt`(行尾图标导航 ~line 256);`grep '^### Decision ' design.md`(5 决策)。

## 2. 视频面板组件(新 LapVideoPanel.kt)

- [x] 2.1 组件:ExoPlayer(remember+DisposableEffect release)+ PlayerView(useController=false)+ 自绘控制条(播放/暂停 + Slider 进度 + 全屏按钮);入参 videoFilePath/videoStartedAtWallClock/lapStart/lapEnd/cursorAbsoluteTs/cursorSource/onCursorChange(from VIDEO)/onFullscreen。
- [x] 2.2 三联动(design Decision 1):LaunchedEffect(cursor) 仅 CHART 来源 seekTo(clamp 覆盖区间);播放 ticker 10Hz 回写 cursor(VIDEO 来源);Slider 拖动同回写。
- [x] 2.3 FileLogger 锚点:面板就绪(duration/coverage)/seek 执行/回写节流后值(vSampled key=triview-cursor)。

## 3. LapDetailScreen 集成

- [x] 3.1 进屏加载 session 视频字段 + duration(MediaMetadataRetriever)→ coverage 判定(Decision 2);cursorSource 状态加入(CHART 默认,图表 onCursorChange 标 CHART)。
- [x] 3.2 面板列表重构:面板 id 枚举 + 按持久化顺序渲染;VIDEO 条件渲染(spec R1)。
- [x] 3.3 全屏按钮 → navigate("lap_video/{sessionId}/{lapIndex}")(Decision 4:面板 player 随生命周期 STOP)。

## 4. 排序持久化

- [x] 4.1 UserProfileRepository(或其 DataStore 实例)加 `lapDetailPanelOrder: Flow<List<String>>` + setter(逗号分隔,默认顺序兜底,未知 id 容错)。
- [x] 4.2 长按拖动 reorder(detectDragGesturesAfterLongPress + 槽位交换 + 落定持久化;若手势实现超预期复杂 → 降级拖拽柄方案,透明记录)。
- [x] 4.3 纯函数单测:顺序序列化/反序列化/未知 id 容错/VIDEO 缺席时槽位保留(spec R3)。

## 5. 入口收敛

- [x] 5.1 LapSessionDetailScreen 圈行尾播放图标删除(lap_video 路由保留给全屏按钮);相关 coverage 行内判定代码一并清理。

## 6. 自审 gate(road-test-first)

- [x] 6.1 编译 + `:feature:test:testDebugUnitTest` 全绿;#14/#16 自查;真机/模拟器验证:回放真实 0-100 资产生成的 session 进详情屏验三联动。

## 10. Follow-up backlog

- 全屏页(LapVideoPlaybackScreen)自身补 seek 进度条(本 round 面板已承担主 seek 路径,全屏页是否需要另议)。
- PARTIAL 覆盖的覆盖外区段视觉提示(进度条灰段)。

## 7. 真机反馈打磨轮次(2026-06-05 凌晨实录)

- [x] 7.1 一轮×3:进度条改本圈坐标系(磨叽段不进条/开圈起播/收圈自停)+ overlay 图层补齐(OverlayHud/updateOverlay 共享管线,internal 化)+ 游标回写吸附最近样本(精确匹配 miss 根因)——8aee5ef
- [x] 7.2 三轮:面板 HUD 紧凑缩放(OverlayHud scale 参数,面板 0.5f/全屏 1f)——8d5a84c
- [x] 7.3 四轮(用户睡前留):面板→全屏进度接力(startWc 路由参数)+ 全屏页进度条(状态机 break→atEnd 停驻常驻,seekRequestWallClock 唤醒)——387edcb
