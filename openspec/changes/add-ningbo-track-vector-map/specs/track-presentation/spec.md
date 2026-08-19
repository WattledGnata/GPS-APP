## ADDED Requirements

### Requirement: 宁波赛道使用 TrackTech 静态矢量预览

系统 SHALL 为 `preset-nic-full` 提供专属 VectorDrawable，并通过 `thumbnailDrawableResId` 接入 `TrackThumbnail` 的最高优先级静态矢量分支。矢量图 MUST 使用 TrackTech Cyan `#FF67E8F9`、圆角轮廓与带深色描边的起点圆；MUST 不包含 51GT3 水印、Logo 或位图内容。

#### Scenario: 宁波缩略图优先渲染静态矢量

- **WHEN** `TrackThumbnail` 渲染 `preset-nic-full`
- **THEN** MUST 使用宁波 `thumbnailDrawableResId` 对应的 VectorDrawable，而不是 asset PNG 或运行时动态投影分支

#### Scenario: 宁波矢量资源符合既有视觉

- **WHEN** 检查宁波 VectorDrawable 资源
- **THEN** 主轮廓 MUST 为不透明 Cyan、round cap/join，起点 MUST 为 Cyan 实心圆加深色描边，且资源 MUST 不引用外部文件或网络 URL
