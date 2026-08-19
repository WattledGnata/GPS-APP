## 1. 轨迹与矢量资产

- [x] 1.1 从 2025-07-19 CSV 裁切官方最快圈对应区间，按约 30 m 弧长等距抽样并输出显式闭合 referencePath
- [x] 1.2 使用与 `TrackMiniMapProjection` 一致的投影生成 `track_preview_v1_autoworld.xml`
- [x] 1.3 渲染并人工检查天津 V1 VectorDrawable 的完整布局形状、方向、留白、Cyan 圆角轮廓和起点标记

## 2. 天津 V1 预置接入

- [x] 2.1 在 `PresetTracks.kt` 新增 `preset-v1-autoworld-full`，接入官网名称、4.290 km、闭合 referencePath 和拟合 S/F
- [x] 2.2 明确保持 `sectorGates = emptyList()`，并确保 debug/release 目录均包含该赛道

## 3. 自动化验证

- [x] 3.1 新增天津 V1 预置契约测试，锁定身份、路径闭合、拟合起终点与无完整布局分段门
- [x] 3.2 更新 debug/release 目录顺序测试，锁定天津 V1 可见性
- [x] 3.3 更新矢量资源测试，验证天津 V1 drawable 存在、接线正确且无第三方/外部 URL
- [x] 3.4 运行六圈离线回放证据，确认最大残差 ≤55 ms、平均绝对偏差 <5 ms
- [x] 3.5 运行 `openspec validate add-v1-autoworld-full-preset --strict` 与 `:feature:test:testDebugUnitTest`、release 相关测试
