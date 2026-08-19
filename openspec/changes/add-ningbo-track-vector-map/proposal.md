## Why

宁波国际赛道的计时门点已经通过同场 GPS、RaceChrono 与官方光电计时数据完成校准，但 App 尚无可选择的宁波预置赛道，也没有符合 TrackTech 视觉体系的赛道预览。现在需要把已验证几何转成可离线使用的预置数据，并用自有 GPS 轨迹生成无第三方水印的矢量图。

## What Changes

- 新增宁波国际赛道完整布局预置，包含校准后的起终点、S1、S2 与有效圈 referencePath。
- 新增宁波静态 VectorDrawable 预览，沿用 TrackTech 深色背景上的 Cyan 轮廓、圆角线条和起点标记风格。
- 在 release/debug 赛道目录中暴露同一个稳定 track ID，并为关键几何及矢量资源增加回归测试。
- 不复制或分发 51GT3 图片；其图片仅作为弯号和 P 区语义的人工参考，最终图形完全由用户提供的 GPS 数据生成。

## Capabilities

### New Capabilities

- `ningbo-preset-track`: 宁波国际赛道预置身份、校准计时几何、参考轨迹和离线可用性契约。

### Modified Capabilities

- `track-presentation`: 赛道列表应使用宁波专属静态矢量缩略图，并保持既有 TrackTech 视觉与降级优先级。

## Impact

- `feature/test/src/main/java/.../repository/PresetTracks.kt`：增加宁波预置赛道。
- `feature/test/src/main/res/drawable/`：增加宁波 VectorDrawable。
- `feature/test/src/test*/`：增加 release 可见性、几何和资源契约测试。
- 不修改 RaceChrono BLE 公共协议，不修改 simulator，不引入网络或第三方图片依赖。
