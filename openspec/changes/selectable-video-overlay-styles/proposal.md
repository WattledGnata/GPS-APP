## Why

现有视频 HUD 将速度表、G 球、圈时和小地图分别放在四角，组件视觉重量不一致、遮挡面积分散，成片容易呈现“多个插件拼接”的观感。现在回放与导出已经共用绘制链路，适合在不改变遥测数据和视频管线的前提下提供多套统一风格，让用户按画面与用途选择。

## What Changes

- 新增三套可选视频 HUD：
  - `FLAT`：简洁平铺 HUD，作为新安装和无历史选择时的默认样式。
  - `RAIL`：统一底部遥测栏，强调快速扫读与整齐的信息层级。
  - `MECHANICAL`：机械仪表簇，保留速度弧与更强的 TrackTech 风格。
- 在视频回放/导出流程提供样式选择卡片；选择后立即更新回放预览，并记住上次选择。
- 回放、内嵌视频面板和最终导出烧录消费同一个样式配置与共享绘制层，保证“预览所见 = 导出所得”。
- 三套样式均保留速度、圈号/圈时、最佳圈差值、G 值和赛道小地图；只调整布局、视觉层级和绘制方式，不改变数据含义。
- 旧 session、旧视频和既有遥测数据无需迁移；无法读取历史选择时安全降级到 `FLAT`。
- 本变更不增加第三方视频、地图或 UI 依赖。

## Capabilities

### New Capabilities

- `video-overlay-style-selection`：定义三套 HUD 的可选项、默认值、实时预览、持久化和降级行为。

### Modified Capabilities

- `video-overlay-playback`：将固定四角 HUD 改为消费用户选择的 HUD 样式，并要求全屏回放与内嵌回放保持一致。
- `video-export`：将固定 overlay 绘制改为按同一 HUD 样式烧录，继续约束回放与导出复用同一绘制实现。

## Impact

- `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/`
  - 视频回放、内嵌视频面板及样式选择 UI。
- `feature/test/src/main/java/com/blazepush/feature/test/overlay/`
  - 三套共享 HUD 布局与 Canvas 绘制入口。
- `feature/test/src/main/java/com/blazepush/feature/test/export/`
  - 导出渲染器接收并应用选定样式。
- `feature/test/src/main/java/com/blazepush/feature/test/datastore/`
  - HUD 样式选择的轻量持久化。
- `feature/test/src/test/`
  - 默认值、持久化降级、布局几何和回放/导出样式透传测试。
- 不影响 `core/bluetooth`、`core/domain`、`core/data`、Room schema、simulator 或服务端。

## 协议兼容性

不修改 RaceChrono BLE UUID、包格式、GPS 解析、binary 遥测格式、圈速 crossing 语义或 Livetiming API。该变更仅作用于客户端视频 HUD 的展示与导出绘制，协议完全兼容。
