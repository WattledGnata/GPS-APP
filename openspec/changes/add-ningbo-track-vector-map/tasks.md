## 1. 轨迹与矢量资产

- [x] 1.1 从 2025-10-26 RaceChrono CSV 选取无 P 区运动的完整有效圈，按行驶顺序抽稀并输出闭合 referencePath 坐标
- [x] 1.2 使用与 `TrackMiniMapProjection` 一致的正北朝上等距矩形投影生成 `track_preview_ningbo.xml`
- [x] 1.3 渲染宁波 VectorDrawable 并人工检查形状、方向、留白、Cyan 圆角轮廓和起点标记

## 2. 宁波预置接入

- [x] 2.1 在 `PresetTracks.kt` 新增 `preset-nic-full`，接入 4.010 km 身份、referencePath 与已校准 S/F、S1、S2
- [x] 2.2 将宁波 `thumbnailDrawableResId` 接到专属 VectorDrawable，并确保 debug/release 目录都包含该赛道

## 3. 自动化验证

- [x] 3.1 新增宁波预置契约测试，锁定身份、路径闭合、门点中心/端点/方向与 sector 顺序
- [x] 3.2 更新 release variant 目录测试，锁定 TFIC、XIC、NIC 的顺序和宁波可见性
- [x] 3.3 新增/更新矢量资源测试，验证宁波 drawable 存在、接线正确且不含 51GT3/外部 URL
- [x] 3.4 运行 `openspec validate add-ningbo-track-vector-map --strict` 与 `:feature:test:testDebugUnitTest`、release 相关测试
