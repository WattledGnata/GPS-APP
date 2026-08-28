## MODIFIED Requirements

### Requirement: TFIC 预置数据契约

The system SHALL 在 `PresetTracks.kt` 提供单条 TFIC 预置赛道，字段值 MUST 严格匹配下表：

| 字段 | 值 |
|------|---|
| `id` | `"preset-tfic-lpcc"` |
| `name.zh` | `"成都天府国际赛道"` |
| `name.en` | `"Chengdu Tianfu International Circuit"` |
| `name.abbr` | `"TFIC"` |
| `lengthKm` | `3.260` |
| `thumbnailAssetPath` | `"track_thumbnails/chengdu_tianfu.png"` |
| `source` | `TrackSource.Preset` |

`referencePath` 与 `sectorGates` 几何坐标 MUST 保持现有契约。`startFinishGate` MUST 保持官方计时平面的中心、方向与 `passDirection` 不变，仅沿原门线方向关于中心对称扩展到 `120 ± 0.2` 米。

#### Scenario: TFIC 预置数据完整

- **WHEN** 测试通过 `PresetTrackCatalog().getTrack("preset-tfic-lpcc")` 取出赛道
- **THEN** 字段值 MUST 全部匹配上表，且 `name` 类型 MUST 为 `TrackName`，`thumbnailAssetPath` MUST 为非空字符串
- **AND** 起终点门宽 MUST 为 `120 ± 0.2` 米

#### Scenario: TFIC 缩略图资产存在

- **WHEN** 构建后的 APK 被安装运行
- **THEN** asset 路径 `feature/test/src/main/assets/track_thumbnails/chengdu_tianfu.png` MUST 实际存在并可被 `AssetManager.open()` 成功加载

## ADDED Requirements

### Requirement: 预置赛道起终点门线覆盖标准

The system SHALL 将 main 源集中的 TFIC、XIC、NIC、V1以及 debug 源集中的天投泊寓预置赛道起终点门线设为 `120 ± 0.2` 米。扩宽 MUST 保持各门线原中心点、原方向和 `passDirection` 不变；sector 门与 `referencePath` MUST NOT 因此改变。

#### Scenario: main 预置起终点门宽统一

- **WHEN** debug 或 release 测试读取 main 预置 TFIC、XIC、NIC、V1
- **THEN** 每条赛道 `startFinishGate.line` 的米制长度 MUST 位于 `[119.8, 120.2]`

#### Scenario: debug-only 预置起终点门宽统一

- **WHEN** debug 测试读取 `preset-boyu-loop`
- **THEN** 其 `startFinishGate.line` 的米制长度 MUST 位于 `[119.8, 120.2]`

#### Scenario: 门线扩宽不改变计时平面

- **WHEN** 比较扩宽前后的任一预置起终点门
- **THEN** 两条门线中点 MUST 在 `0.05` 米以内一致
- **AND** `passDirection` MUST 完全一致

#### Scenario: 实测轨迹不存在新增目标方向过线

- **WHEN** 使用现有 TFIC、NIC、V1 实测轨迹比较扩宽前后的目标方向有限门线过线序列
- **THEN** 120 米门线 MUST NOT 产生新增的非起终点目标方向过线
