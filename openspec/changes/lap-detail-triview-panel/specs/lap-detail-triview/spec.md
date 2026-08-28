# lap-detail-triview

单圈详情三视图:条件视频面板、游标三联动、面板排序持久化、入口收敛。

## ADDED Requirements

### Requirement: 视频面板 MUST 按覆盖条件渲染

`LapDetailScreen` SHALL 在该圈视频覆盖(`VideoExportClip.lapCoverage`)≠ NONE 时渲染视频面板(内嵌播放器+进度条+全屏按钮);session 无视频或覆盖为 NONE 时 MUST NOT 渲染该面板(其余面板布局不受影响)。

#### Scenario: 有覆盖的圈显示视频面板
- **GIVEN** session 有视频且该圈 coverage = FULL/PARTIAL
- **WHEN** 进入单圈详情
- **THEN** 视频面板渲染,可播放/拖进度/点全屏

#### Scenario: 无视频的圈不显示(反例)
- **GIVEN** session 无 videoFilePath 或该圈 coverage = NONE
- **WHEN** 进入单圈详情
- **THEN** 无视频面板;数据/地图面板照常——MUST NOT 显示空壳或报错占位

### Requirement: 游标 MUST 三向联动且无回环抖动

视频面板 SHALL 与既有 `cursorAbsoluteTs` 双向同步:图表游标变更 → 视频 seek 到 `cursor - videoStartedAtWallClock`(clamp 覆盖区间);视频播放/拖进度 → 回写 cursor(≤10Hz 节流)→ 图表/SectorBar/地图跟随;视频来源的 cursor 变更 MUST NOT 触发回环 seek。

#### Scenario: 拖图表视频跟随
- **GIVEN** 视频面板就绪
- **WHEN** 用户拖 SPEED 图表游标到时刻 T
- **THEN** 视频画面 seek 至 T 对应位置;地图点同步至 T

#### Scenario: 播放视频图表跟随
- **GIVEN** 视频播放中
- **WHEN** 位置推进
- **THEN** 图表游标/地图点以 ≤10Hz 跟随;暂停后游标停在当前帧

#### Scenario: 回环抑制(反例)
- **GIVEN** 视频回写 cursor 产生变更
- **WHEN** 该变更传导
- **THEN** MUST NOT 再次触发视频 seekTo(无 seek↔回写振荡)

### Requirement: 面板顺序 MUST 可拖动调整并持久化

面板(视频/Overview/SPEED/ACCEL/SECTORS/TRACK)SHALL 支持长按拖动改变上下顺序;顺序 SHALL 持久化(per-app),重进屏/重启后保持;无视频圈进入时 VIDEO 槽位偏好 SHALL 保留(下次有视频按偏好呈现)。

#### Scenario: 拖动并记住
- **GIVEN** 用户把 TRACK 面板拖到 SPEED 之前
- **WHEN** 退出并重进任意圈详情
- **THEN** TRACK 仍在 SPEED 之前

### Requirement: 入口 MUST 收敛到详情屏

`LapSessionDetailScreen` 圈行尾的独立视频播放图标 SHALL 移除;全屏沉浸播放(lap_video 路由)SHALL 仅从详情屏视频面板的全屏按钮进入;导出功能随全屏页保留不变。

#### Scenario: 行尾图标退役
- **GIVEN** 有视频的圈行
- **WHEN** 渲染 session 详情圈列表
- **THEN** 行尾无播放图标;点行进单圈详情(视频在内)

#### Scenario: 全屏链路保留
- **GIVEN** 详情屏视频面板
- **WHEN** 点全屏按钮
- **THEN** 进入 LapVideoPlaybackScreen(overlay/导出均可用)
