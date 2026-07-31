## MODIFIED Requirements

### Requirement: 共享 overlay 绘制层（回放与导出双端复用）

系统 SHALL 提供一个消费 `android.graphics.Canvas` 的共享绘制层 `OverlayCanvasPainter`。该绘制层 MUST 提供整帧 HUD 入口，输入统一的 overlay 数据、`VideoOverlayStyle` 与画布尺寸，按 `FLAT`、`RAIL`、`MECHANICAL` 三套布局绘制速度、圈时/delta、G 值和小地图。

回放端（Compose 透明 Canvas）与导出端（GL overlay Bitmap）MUST 调用同一个整帧 HUD 入口，不得各写一套样式布局或坐标实现。既有 `GaugeMath`、`TrackMiniMapProjection` 和基础 Canvas 图元函数 MUST 继续复用。

#### Scenario: 回放与导出使用同一样式得到一致布局

- **GIVEN** 样式为 `RAIL`，overlay 数据与输出尺寸相同
- **WHEN** 回放 Canvas 与导出 Bitmap Canvas 分别调用共享整帧 HUD 入口
- **THEN** 两端的速度、圈时/delta、G 值和小地图布局矩形 MUST 相同
- **AND** 颜色、字号、线宽与值格式 MUST 由同一绘制代码产出

#### Scenario: 三套样式均可烧录

- **GIVEN** 一组有效 overlay 数据
- **WHEN** 分别以 `FLAT`、`RAIL`、`MECHANICAL` 调用共享绘制层
- **THEN** 三次渲染均 MUST 产生非空 overlay
- **AND** 每套样式均 MUST 包含速度、圈时/delta、G 值和小地图

#### Scenario: 反例——双端复制布局违反约束

- **GIVEN** 一次实现仅在 Compose 端新增样式布局，导出端另写坐标或仍固定旧四角布局
- **WHEN** 检视回放与导出代码路径
- **THEN** 该实现 MUST 被判为不合规
- **AND** 两端 MUST 统一改为调用共享整帧 HUD 入口

#### Scenario: null 数据和无效样式降级不崩

- **GIVEN** overlay 部分数据缺失，或导出样式参数无法解析
- **WHEN** 导出端绘制 overlay
- **THEN** MUST 使用占位值并降级到 `FLAT`
- **AND** MUST NOT 崩溃或产生空视频帧

## ADDED Requirements

### Requirement: 单段与跨段导出透传冻结的 HUD 样式

`VideoExportService` SHALL 在任务启动时冻结当前 HUD 样式，并将其传入单段及跨段导出 pipeline。每帧编码期间 MUST 复用该冻结值，不得重复读取偏好。

#### Scenario: 单段与跨段导出都使用任务样式

- **GIVEN** 用户以 `MECHANICAL` 启动导出
- **WHEN** 目标圈分别走单段或跨段 pipeline
- **THEN** 两条 pipeline 构造的 `ExportOverlayRenderer` MUST 都接收 `MECHANICAL`
- **AND** 产出帧 MUST 使用机械仪表布局

#### Scenario: 反例——缺失样式 extra 安全降级

- **GIVEN** 旧调用方启动 Service 时没有携带样式 extra
- **WHEN** Service 解析任务参数
- **THEN** MUST 使用 `FLAT`
- **AND** 导出 MUST 正常继续
