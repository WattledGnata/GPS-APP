## Context

现有 `PresetTrackCatalog` 在 main 源集包含 TFIC 与 XIC，`TrackThumbnail` 已定义 VectorDrawable > asset PNG > 动态轮廓的展示优先级。宁波数据来自用户提供的 2025-10-26 RaceChrono 25 Hz 实测 session；起终点、S1、S2 已用同场官方光电门整圈与分段成绩反推并复核，整圈最大残差约 14 ms、单段最大残差约 37 ms。

`LapTime/miniprogram/track_images/ningbo.jpg` 带 51GT3 水印，不具备直接再分发条件；因此只可用于人工理解布局，不能成为 App 资产来源。

## Goals / Non-Goals

**Goals:**

- 让 release/debug App 离线可选择宁波国际赛道完整布局。
- 计时使用已校准的 S/F、S1、S2，referencePath 使用同场有效圈实测轨迹。
- 生成符合现有 TrackTech Cyan 轮廓风格的静态 VectorDrawable。
- 用自动化测试锁定赛道身份、门点几何、目录顺序与矢量资源接线。

**Non-Goals:**

- 本变更不接入服务端赛道下发、版本覆盖或 Room 缓存。
- 不修改 livetiming-server seed，不发布任何服务端数据。
- 不复刻 51GT3 位图、水印、弯号字体或版式。
- 不重新调整已经由官方成绩验证的三个计时门。

## Decisions

### D1：宁波使用稳定 ID `preset-nic-full`

NIC 表示 Ningbo International Circuit，`full` 明确当前是完整布局，为未来短布局预留独立 ID。赛道加入 main preset，因此 debug/release 均可见，`source = TrackSource.Preset`。

### D2：referencePath 从有效计时圈生成，不使用第三方示意图描边

选取 2025-10-26 session 中连续、完整且无 P 区运动的有效圈，按经纬度等距矩形坐标抽稀为约 20–30 米间距，并显式闭合。这样计时地图、动态小地图和静态矢量图都来自同一份自有 GPS 几何。

### D3：计时门沿用官方计时反推结果

- S/F 中心：`29.76255631148434, 121.86415001540229`，通过方向约 186°，宽 75 m。
- S1 中心：`29.761261548958334, 121.86798783125`，通过方向约 41.72°，宽 50 m。
- S2 中心：`29.762812847916667, 121.86962061145833`，通过方向约 321.05°，宽 50 m。

代码直接保存已计算好的端点和方向向量，避免运行时再做 bearing/宽度换算；测试锁定端点、顺序与方向。

### D4：矢量图严格复用 TrackTech 预览语言

使用 100×120 viewport、120×144 dp，正北朝上、等距矩形投影、四周 8 单位 padding。主轮廓为 `#FF67E8F9`、2 单位、round cap/join；起点为 Cyan 实心圆加深色描边。S1/S2 不在缩略图中额外标文字，避免在 96×64 dp RECENT 卡片中产生噪声；门点仍由计时数据和调试门线展示承载。

### D5：静态资源为主，动态轮廓继续作为降级

宁波 `thumbnailDrawableResId` 指向专属 drawable，`thumbnailAssetPath = null`。若未来资源接线被移除，现有 `TrackThumbnail` 仍可根据 referencePath 动态画轮廓，不产生空白或崩溃。

## Risks / Trade-offs

- [单场 GPS 轨迹可能包含厘米到米级偏移] → 预览只表达赛道形状；计时精度由独立校准门点保证，门点已通过官方成绩复核。
- [静态矢量与 referencePath 后续可能漂移] → XML 注释记录生成来源，并用资源/接线测试锁定；轨迹变更时必须重新生成矢量。
- [完整布局长度存在不同公开口径] → 预置展示采用完整布局 `4.010 km`，不从 GPS 累计距离推导。
- [main preset 增加条目会改变列表顺序] → 明确追加在 TFIC、XIC 之后，并更新 release variant 契约测试。

## Migration Plan

新增 preset 和 drawable，不涉及数据库迁移。若需要回滚，删除宁波 Track 条目、drawable 与对应测试即可；现有赛道和历史 session 不受影响。

## Open Questions

无。本轮只交付 App 内置的宁波矢量预览与预置赛道；服务端下发另立变更。
