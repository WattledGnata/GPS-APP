## ADDED Requirements

### Requirement: 宁波完整布局作为 release 可用预置赛道

系统 SHALL 在 `PresetTrackCatalog` 中提供 ID 为 `preset-nic-full` 的宁波国际赛道完整布局，并 MUST 在 debug 与 release variant 中均可见。其名称 MUST 为中文 `宁波国际赛道`、英文 `Ningbo International Circuit`、缩写 `NIC`，官方长度 MUST 为 `4.010 km`，来源 MUST 为 `TrackSource.Preset`。

#### Scenario: release 目录可选择宁波

- **WHEN** release variant 调用 `PresetTrackCatalog().getAllTracks()`
- **THEN** 返回列表 MUST 包含 `preset-nic-full`，且其名称、长度与来源 MUST 符合上述契约

### Requirement: 宁波计时门使用官方成绩校准几何

`preset-nic-full` MUST 包含一个起终点门和按顺序排列的 S1、S2 两个分段门。三个门的线段端点、通过方向和 `sequenceIndex` MUST 与 2025-10-26 官方光电计时整圈及 S1/S2/S3 分段反推结果一致；系统 MUST NOT 使用用户最初提供的 RaceChrono 副本门点代替校准门点。

#### Scenario: 宁波门链完整且有序

- **WHEN** 读取 `preset-nic-full`
- **THEN** `startFinishGate.sequenceIndex` MUST 为 0，`orderedSectorGates` MUST 依次为 `s1` 和 `s2`，其 `sequenceIndex` MUST 分别为 1 和 2

#### Scenario: 宁波起终点避开 P 区重复触发位置

- **WHEN** 检查 `preset-nic-full.startFinishGate` 的中心点
- **THEN** 中心点 MUST 位于约 `29.7625563115, 121.8641500154`，不得回退到原副本约 `29.7610116667, 121.863963`

### Requirement: 宁波参考路径来自有效圈 GPS 并闭合

`preset-nic-full.referencePath` SHALL 使用用户提供的 2025-10-26 有效圈 GPS 轨迹生成，MUST 至少包含 50 个按行驶顺序排列的点，并 MUST 以首尾同点显式闭合。参考路径 MUST NOT 从 51GT3 图片人工描边。

#### Scenario: 宁波参考路径可用于离线轮廓

- **WHEN** 读取 `preset-nic-full.referencePath`
- **THEN** 点数 MUST 不少于 50，`points.first()` MUST 等于 `points.last()`，且在无网络状态下足以由 `TrackMiniMap` 绘制完整轮廓

### Requirement: 宁波预置不依赖网络与第三方图片

宁波赛道的计时几何、referencePath 和矢量预览 MUST 随 APK 打包。实现 MUST NOT 下载、嵌入或引用 51GT3 图片 URL 或水印资产。

#### Scenario: 离线选择宁波并取得计时几何

- **WHEN** App 无网络且用户选择 `preset-nic-full`
- **THEN** `TrackCatalog.getTrack()` MUST 返回完整 referencePath、起终点门和两个分段门，不触发网络请求
