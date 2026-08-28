## ADDED Requirements

### Requirement: 圈速驾驶 HUD 常驻可信速度

圈速驾驶 HUD MUST 在无需横滑的同一页面持续显示当前速度，并且 MUST 复用 `TestSessionViewModel.filteredSpeedKmh` 作为唯一速度数值来源。CURRENT、DELTA、LAST、BEST SHALL 保留可见。

#### Scenario: 车辆静止且 Main 数据持续刷新

- **GIVEN** BLE 已连接、GPS readiness 为 ARMED 且最新 Main 帧未超过动态 freshness deadline
- **WHEN** 滤波后速度为 0 km/h
- **THEN** HUD MUST 显示可信的 `0`
- **AND** 同屏 MUST 显示 GPS 心跳、更新频率、卫星数和最新数据年龄

#### Scenario: 最后 Main 帧超过新鲜度阈值

- **GIVEN** HUD 曾显示一个非零滤波速度
- **WHEN** 距最后真实 Main 帧的单调年龄达到动态 deadline
- **THEN** 速度 MUST 显示 `--`
- **AND** HUD MUST 显示数据中断及继续增长的数据年龄
- **AND** HUD MUST NOT 保留旧速度

### Requirement: GPS 心跳以真实 Main 帧为准

GPS 心跳 MUST 由当前连接代次的 `mainFrameReceivedAtElapsedRealtimeMs`、`hasMainFrame`、`isStale` 和动态 Main deadline 推导，MUST NOT 以 Compose 重组、GPS Time 包或缓存速度变化冒充新 Main 帧。

#### Scenario: 已连接但尚未收到 Main

- **GIVEN** BLE CONNECTED 但当前代次 `hasMainFrame == false`
- **WHEN** 圈速驾驶页显示
- **THEN** 心跳 MUST 显示等待 Main 数据
- **AND** 速度、频率、卫星数和年龄中的未知值 MUST 显示 `--`

#### Scenario: Main 新鲜但尚在定位或恢复稳定

- **GIVEN** Main 帧持续刷新
- **WHEN** readiness 为 ACQUIRING_FIX 或 STABILIZING
- **THEN** 心跳 MUST 显示对应状态及真实频率、卫星数和年龄
- **AND** 速度 MUST 显示 `--`，直到 ARMED

### Requirement: 异常提示与既有圈速页面能力不回归

BLE 断开 MUST 继续沿用既有硬中断提示；GPS stale 和等待定位 SHALL 与心跳状态一致。横屏、屏幕常亮、相机横滑页、录像状态和 HOLD TO END SHALL 保持既有行为。

#### Scenario: BLE 断开

- **WHEN** 圈速 Session 中 BLE 连接断开
- **THEN** 既有 BLE_DISCONNECTED 硬中断 MUST 继续覆盖仪表区域
- **AND** 心跳状态 MUST 为断开且速度 MUST 为 `--`

#### Scenario: 驾驶页与相机页能力保留

- **WHEN** 用户停留驾驶页或横滑到相机页
- **THEN** 驾驶页 MUST 同屏保留速度心跳与四个圈速指标
- **AND** 相机页、REC 状态和 HOLD TO END SHALL 保持工作
