## Why

现有预置赛道起终点门线仅约 50–75 米，车辆沿主直道旁的 P 区通道或受 GPS 横向误差影响时可能从有限门线端点外通过，导致不开圈或漏记成绩。天府 2026-08-24 实测已暴露该问题，需要在保持官方计时平面和方向不变的前提下扩大横向覆盖。

## What Changes

- 将 main 与 debug 预置赛道的起终点门线统一扩展到约 120 米。
- 保持每条门线的中心点、方向、`passDirection`、分段门和参考线路不变，避免改变正常赛车线的过线时间。
- 增加几何契约测试，锁定门宽、中心点和方向，并防止未来回退到过窄门线。
- 使用现有天府、宁波、V1 实测轨迹验证扩宽后的覆盖与非目标交叉风险，并用 XIC 参考线路约束最大安全宽度。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `track-presentation`: 修改预置赛道起终点门线几何契约，使门线覆盖主赛道旁的可通行 P 区走廊并保留原计时平面。

## Impact

- `feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt`
- `feature/test/src/debug/java/com/blazepush/feature/test/repository/ExtraPresetTracksDebug.kt`
- `feature/test/src/test*/java/com/blazepush/feature/test/repository/` 下的预置赛道几何测试
- 不修改 RaceChrono BLE 公共协议、计时引擎算法、Room 数据结构或 Livetiming 网络协议。

## 协议兼容性

本变更仅调整本地预置赛道静态几何数据，不涉及发射端 simulator 或接收端 BLE 协议字段，双端协议保持兼容。
