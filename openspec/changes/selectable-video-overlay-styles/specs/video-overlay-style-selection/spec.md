## ADDED Requirements

### Requirement: 用户可选择并持久化三种视频 HUD 样式

系统 SHALL 提供 `FLAT`、`RAIL`、`MECHANICAL` 三种视频 HUD 样式。`FLAT` SHALL 作为无历史选择时的默认值；用户在全屏视频回放中选择样式后，回放 MUST 即时更新，并在页面切换及进程重启后保留该选择。

样式选择 MUST 只改变布局与视觉表达，不得改变速度、圈号/圈时、最佳圈差值、G 值及赛道小地图的数据含义。

#### Scenario: 首次进入默认显示简洁平铺 HUD

- **GIVEN** 本地不存在已保存的 HUD 样式，或保存值为空
- **WHEN** 用户进入视频回放页
- **THEN** 系统 MUST 选中 `FLAT`
- **AND** 回放画面 MUST 以简洁平铺 HUD 显示全部五类遥测信息

#### Scenario: 选择样式后立即预览并跨进程保留

- **GIVEN** 用户正在视频回放页且样本已加载
- **WHEN** 用户在 HUD 样式选择器中选择 `RAIL` 或 `MECHANICAL`
- **THEN** 当前回放画面 MUST 无需重新加载视频即切换样式
- **AND** 选择 MUST 写入本地偏好
- **AND** 重新进入页面或重启进程后 MUST 恢复同一样式

#### Scenario: 非法或未来样式值安全降级

- **GIVEN** 本地偏好或导出 Intent 中的样式值无法映射到当前枚举
- **WHEN** 系统解析样式
- **THEN** MUST 返回 `FLAT`
- **AND** MUST NOT 崩溃、隐藏 HUD 或阻塞视频播放/导出

### Requirement: 导出任务冻结启动时的 HUD 样式

系统 SHALL 在用户启动导出时读取当前 HUD 样式并作为任务参数冻结。导出过程中用户再次切换全局样式 MUST NOT 改变已经运行的导出任务。

#### Scenario: 导出开始后切换样式不影响本次成片

- **GIVEN** 当前样式为 `RAIL`，用户启动一个视频导出任务
- **WHEN** 导出尚未结束时用户将全局样式切换为 `FLAT`
- **THEN** 正在运行的导出 MUST 继续使用 `RAIL`
- **AND** 后续新启动的导出 MUST 使用 `FLAT`
