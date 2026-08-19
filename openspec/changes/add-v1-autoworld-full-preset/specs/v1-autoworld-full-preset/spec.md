## ADDED Requirements

### Requirement: 天津 V1 完整布局作为 release 可用预置赛道

系统 SHALL 在 `PresetTrackCatalog` 中提供 ID 为 `preset-v1-autoworld-full` 的天津 V1 4.29 km 完整布局，并 MUST 在 debug 与 release variant 中均可见。其名称 MUST 为中文 `天津V1国际赛车场`、英文 `V1 Autoworld Circuit`、缩写 `V1`，长度 MUST 为 `4.290 km`，来源 MUST 为 `TrackSource.Preset`。

#### Scenario: release 目录可选择天津 V1 完整布局

- **WHEN** release variant 调用 `PresetTrackCatalog().getAllTracks()`
- **THEN** 返回列表 MUST 包含 `preset-v1-autoworld-full`，且其名称、长度与来源 MUST 符合上述契约

### Requirement: 天津 V1 起终点使用官方圈速多圈拟合几何

`preset-v1-autoworld-full` MUST 使用六个 MYLAPS 完整圈共同最小化 RMSE 后的起终点门，中心 MUST 位于约 `39.3829583023, 116.9931881087`，通过方向 MUST 为约 278°，门宽 MUST 为约 75 m。系统 MUST NOT 回退到原 RaceChrono RCZ 中心约 `39.3831453333, 116.9914663333`。

#### Scenario: 起终点几何锁定拟合结果

- **WHEN** 读取 `preset-v1-autoworld-full.startFinishGate`
- **THEN** 线段端点、passDirection 和 `sequenceIndex == 0` MUST 与设计文档 D2 一致

#### Scenario: 六个官方完整圈残差受控

- **WHEN** 使用该门回放 2025-07-19 CSV 中与金政官方圈 1 至 6 对齐的 GPS
- **THEN** 每圈相对 MYLAPS 官方圈速的绝对残差 MUST 不超过 55 ms，且平均误差的绝对值 MUST 小于 5 ms

### Requirement: 4.29 km 完整布局不混用 2.4 km 分段门

`preset-v1-autoworld-full.sectorGates` MUST 为空。在取得完整布局官方 S1/S2 坐标或可复核数据前，系统 MUST NOT 将原 RCZ 的两个 Split 作为完整布局官方分段。

#### Scenario: 完整布局只进行整圈计时

- **WHEN** 圈速引擎加载 `preset-v1-autoworld-full`
- **THEN** `orderedSectorGates` MUST 为空，圈完成判定 MUST 仅使用拟合起终点门

### Requirement: 天津 V1 参考路径来自完整有效圈 GPS 并闭合

`preset-v1-autoworld-full.referencePath` SHALL 使用用户提供的 2025-07-19 完整有效圈 GPS 生成，MUST 至少包含 100 个按行驶顺序排列的点，并 MUST 以首尾同点显式闭合。参考路径 MUST NOT 从第三方图片人工描边。

#### Scenario: 天津 V1 参考路径可离线绘制完整布局

- **WHEN** 读取 `preset-v1-autoworld-full.referencePath`
- **THEN** 点数 MUST 不少于 100，`points.first()` MUST 等于 `points.last()`，且无网络时足以绘制完整赛道轮廓

### Requirement: 天津 V1 预置完全离线

天津 V1 的身份、计时几何、referencePath 与预览资源 MUST 随 APK 打包，实现 MUST NOT 在选择或计时时发起网络请求。

#### Scenario: 无网络选择天津 V1

- **WHEN** App 无网络且用户选择 `preset-v1-autoworld-full`
- **THEN** `TrackCatalog.getTrack()` MUST 返回完整 referencePath 和起终点门，不触发网络请求
