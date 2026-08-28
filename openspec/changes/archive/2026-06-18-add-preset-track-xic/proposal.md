## Why

目前 `PresetTrackCatalog.mainPresets` 只含一条 TFIC（preset-tfic-lpcc），所有 variant 共用。
用户在厦门国际赛车场（XIC）有真实赛道数据（vbo session + rcz 计时门导出），需要把 XIC
作为第二条 main-variant 预置赛道，跟 TFIC 同级（release/debug 都可见），让用户在 vivo / 华为
真机上直接选用 XIC 跑圈速测试，不必依赖 ReplayAlignedTrackCatalog 的拟合或临时硬塞。

## What Changes

- `PresetTracks.kt` `mainPresets` list 在 TFIC 之后追加一条 `Track` entry `preset-xic-lpcc`（XIC / 厦门国际赛车场 / Xiamen International Racetrack）
- entry 包含：
  - `id = "preset-xic-lpcc"`
  - `name = TrackName(zh = "厦门国际赛车场", en = "Xiamen International Racetrack", abbr = "XIC")`
  - `lengthKm = 1.662`（vbo lap=002 累计 haversine 1662.0m 确定性算出，参考圈 70.3s）
  - `thumbnailAssetPath = null`（暂无 thumbnail 资源，留 follow-up；现有 UI fallback 已覆盖 null）
  - `referencePath` 由 vbo lap=002（25Hz × 1758 samples）按 haversine 累计距离等距采样 **15** 个路径点（跟 TFIC 13 点同量级），起点对齐 rcz Start/Finish 中心
  - `startFinishGate`：rcz trap `Start/Finish`（centerLat=24.6547017°, centerLon=118.3154570°, bearing=54°, width=75m），算 GeoLine 端点（右垂直偏移 width/2）+ passDirection
  - `sectorGates`：rcz trap `Split1` + `Split2` 转 sector gates（s1 / s2），同 line 计算 + passDirection
- **BREAKING（仅测试断言）**：`TrackCatalogReleaseVariantTest` 断言从 `["preset-tfic-lpcc"]` 改为 `["preset-tfic-lpcc", "preset-xic-lpcc"]`；`TrackCatalogDebugVariantTest` 断言从 `["preset-tfic-lpcc", "preset-boyu-loop"]` 改为 `["preset-tfic-lpcc", "preset-xic-lpcc", "preset-boyu-loop"]`（mainPresets + extraPresetTracks 拼接顺序锁定）
- `track-catalog-hot-start` capability spec 的两个 variant scenario MODIFIED 同步新列表

## Capabilities

### New Capabilities
（无）

### Modified Capabilities

- `track-catalog-hot-start`：`PresetTrackCatalog` 内存直返实现的 release / debug variant scenario MODIFIED——release 现在含 TFIC + XIC，debug 含 TFIC + XIC + 天投泊寓；mainPresets list 第 0 位仍为 TFIC 不变。

## Impact

**代码改动**（约 ~80 行，3 文件）：
- `feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt` — `mainPresets` list 加 Track entry preset-xic-lpcc（约 65 行 Track(...)）
- `feature/test/src/testRelease/.../TrackCatalogReleaseVariantTest.kt` — 断言列表加 preset-xic-lpcc + 注释更新
- `feature/test/src/testDebug/.../TrackCatalogDebugVariantTest.kt` — 断言列表加 preset-xic-lpcc + 注释更新

**数据来源**（确定性算出，可重现）：
- vbo: `session_20260530_1340.vbo`（25Hz × 31157 行 RaceLogic VBO）
- rcz: `track_厦门国际赛车场_的副本.rcz`（含 3 traps：S/F + 2 splits）
- 算法：
  - lat = vbo_minutes / 60；lon = -vbo_minutes / 60（RaceLogic VBO 经度 negative=East 惯例，已用 rcz 中心点交叉验证）
  - rcz lat/lon = (raw_value / 1e5) / 60（minutes × 1e5 编码）；bearing = raw / 1e3 度；width = raw mm（已用厦门 ~24.65°N / 118.32°E 实际位置反演验证）
  - 累计距离 = haversine 半径 6371000m
  - trap GeoLine 端点 = center ± (width/2) × right_perp(bearing) 方向；right_perp 转 lat/lon 用 1° = 111111m 球面常数
  - passDirection magnitude = 0.00025°（跟 TFIC 0.0002° 同量级；方向 = bearing 单位向量）

**协议 / Schema / 公共数据契约**：
- 不动 RaceChrono BLE 协议、不动 replay 协议、不动 Room schema
- TrackCatalog interface 不变（PresetTrackCatalog 已支持多条 entry）
- LapTimingEngine 几何（gate × ray 相交 + passDirection 点积）不变

**真机验证 gate**（road-test-first 模式，user 明早后真机补）：
- 赛道选择/管理界面看到 XIC 进入列表（华为 8KE0219522008434）
- 选 XIC 跑一圈圈速：起点 / sector 1 / sector 2 / 起终点 切换正常，圈时无异常
- referencePath 在 TrackPolylineMap 渲染形状跟 vbo lap=002 轨迹一致
