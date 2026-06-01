## ADDED Requirements

### Requirement: 车手显示名本地持久化

系统 SHALL 用 `UserProfileRepository`（DataStore Preferences）存一个车手显示名 `driverName: String`，跨会话保留。未填时 MUST 返回空串（不崩、不抛）。

#### Scenario: 写入后跨会话读回
- **WHEN** 设置 driverName="老王"，杀进程重进
- **THEN** repository roundtrip SHALL 读回 "老王"

#### Scenario: 新安装未填返回空串
- **WHEN** 全新安装、未设过 driverName
- **THEN** `driverName` flow SHALL 发出 ""，MUST NOT 因缺 key 崩溃

#### Scenario: 反例——覆盖写不残留旧值
- **WHEN** 先设 "88号" 再设 "99号"
- **THEN** 读回 SHALL 为 "99号"，MUST NOT 残留 "88号"

### Requirement: 设置页车手名输入入口

系统 SHALL 提供 `SettingsScreen`（从 `DeviceHomeScreen` 的 SETTINGS 行进入），含车手显示名输入框，输入即持久化（trim）。`DeviceHomeScreen` SETTINGS 行 MUST 导航到该页，MUST NOT 再弹 "coming in next round" Toast。

#### Scenario: 从 Device tab 进设置改名
- **WHEN** 用户在 Device tab 点 SETTINGS
- **THEN** 系统 SHALL 导航到 `SettingsScreen`，显示车手名输入框（当前值）

#### Scenario: 输入即持久化
- **WHEN** 用户在车手名框输入 "老王"
- **THEN** 系统 SHALL 经 `UserProfileRepository.setDriverName` 持久化（trim 后）

#### Scenario: 反例——SETTINGS 不再是占位 Toast
- **WHEN** 点 DeviceHomeScreen 的 SETTINGS 行
- **THEN** MUST NOT 出现 "Settings · coming in next round" Toast，MUST 进真实设置页
