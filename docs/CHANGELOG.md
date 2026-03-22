# CHANGELOG - feature/gps-data-filter

## 2026-03-22

### Bug Fixes

- **fix: GForceChart display maxG now matches key metrics** (`SpeedChart.kt`)
  - G值曲线标题显示的最大G值与关键指标不一致
  - 根因：`elvis ?: 0.5f` 兜底逻辑导致小数被强制提升到0.5
  - 修复：移除重复的 `coerceAtLeast(0.5f)`，使用关键指标传入的 `maxAcceleration` 作为显示值

- **fix: braking test shows wrong start speed threshold** (`TestSessionViewModel.kt`, `SmartTestLauncher.kt`)
  - 刹车测试的起点条件显示为 "0-3 km/h"，应为 "95-105 km/h"
  - 根因：`updateLaunchStatus` 调用 `checkLaunchConditions` 时未根据测试类型传递速度范围
  - 修复：根据 `TestTemplate` 类型传递对应的起点速度范围

- **fix: BluetoothDataSource Flow collection leak** (`BluetoothDataSource.kt`)
  - `connect()` 中启动的 `stateFlow.collect` 协程在 disconnect 时未取消
  - 多次 connect/disconnect 会累积游离协程
  - 修复：使用 `connectionCollectJob: Job?` 跟踪协程，disconnect 时 cancel

- **fix: ConnectionManager fake connection detection never works** (`ConnectionManager.kt`)
  - `getCurrentDeviceAddress()` 永远返回 null，导致假连接后无法重连
  - 修复：添加 `setCurrentDevice(address)` 方法供外部设置设备地址

- **fix: DeviceConnectionScreen duplicate GpsSignalCard** (`DeviceConnectionScreen.kt`)
  - 条件分支 `if (A) GpsSignalCard` 和 `else if (A) GpsSignalCard` 完全重复
  - POOR 状态下会显示两个相同的 GpsSignalCard
  - 修复：合并为单一条件，GpsSignalCard 在连接状态下始终显示

- **fix: SimulatorScreen duplicate DataPreviewCard** (`SimulatorScreen.kt`)
  - `DataPreviewCard` 在页面中出现两次，内容完全相同
  - 修复：移除多余的 DataPreviewCard

### Cleanup

- **chore: remove debug FileLogger/Log.d from result screens** (`SpeedChart.kt`, `TestResultScreen.kt`, `TestExecutionScreen.kt`, `CalculateResultUseCase.kt`)
  - 移除测试验证期间添加的临时日志输出

### Refactor

- **refactor: ConnectionManager device address tracking** (`ConnectionManager.kt`)
  - 添加 `currentDeviceAddress` 字段和 `setCurrentDevice()` 方法
  - 移除无用的 `getCurrentDeviceAddress()` stub 方法

### Git Config

- **chore: update git remote to correct repository** (`.git/config`)
  - 原远端指向本地路径，现已更正为 `https://github.com/WattledGnata/GPS-APP.git`
