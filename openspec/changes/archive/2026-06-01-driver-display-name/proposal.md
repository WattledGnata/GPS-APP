## Why

App 当前**无任何用户概念**——`AndroidManifest` 无用户、无车手身份字段。但 livetiming 圈速上报 API（`livetiming-server/docs/api/lap-upload.md`）的 `driver` 字段**必填**（"车手显示名，榜单展示用；App 需收集一次"）。这是整个 livetiming 接入的本地第一步。

**Baseline（已查实）**：`DeviceHomeScreen`（Device tab）有个 "SETTINGS" 行（`feature/test/.../ui/tracktech/DeviceHomeScreen.kt:201`），副标题 "Units · Voice · Auto reconnect"，但 onClick 只弹 Toast `"Settings · coming in next round"`——**设置页是空占位**。DataStore Preferences 已在 feature/test，有 `RecordingPreferencesRepository`/`RecentTracksStore` 双构造先例。

**用户场景**：车手首次进设置填一次显示名（昵称/车号），跨会话保留；将来出圈上报 livetiming 时带上这个 `driver`。

## What Changes

- **新增 `UserProfileRepository`**（DataStore Preferences，仿 `RecordingPreferencesRepository`）：存一个 `driverName: String`（未填则空串）。
- **填空设置页**：新增 `SettingsScreen`（Compose，Track Tech V2 视觉），第一个功能 = 车手显示名输入（`OutlinedTextField` + 本地 draft 防光标跳 + 即时持久化）。
- **`DeviceHomeScreen` SETTINGS 行**：onClick 从 Toast 改 `navController.navigate("settings")`；副标题改 "车手显示名 · 更多设置"。
- **导航**：`TrackTechAppShell` 注册 `composable("settings")`。
- **明确不做**：网络上报（lap-upload，需网络地基，后续 round E/F/G）；从 carModel/蓝牙名自动取默认（保持 D 最小，留作打磨）；其他设置项（单位/语音/自动重连，后续在此页扩展）。

## Capabilities

### New Capabilities

- `driver-display-name`: 本地车手显示名的存储与设置页输入（livetiming lap-upload `driver` 字段的本地前置）。

### Modified Capabilities

（无。仅把 DeviceHomeScreen SETTINGS 占位接到真实设置页。）

## Impact

- **模块**：`feature/test`（全部，符合隔离边界）。
- **文件**：新增 `datastore/UserProfileRepository.kt`、`ui/settings/SettingsScreen.kt`；改 `di/AppModule.kt`（DI）、`ui/tracktech/TrackTechAppShell.kt`（route）、`ui/tracktech/DeviceHomeScreen.kt`（SETTINGS 导航）。
- **依赖/schema**：无新增依赖、无 Room 改动、**无网络**（纯本地 DataStore）。
- **测试**：`UserProfileRepository` roundtrip 单测（空默认 / 写读 / 覆盖）。真机：进设置填名、杀进程重进保留。
- **执行模式**：road-test-first。复杂度 small。
- **关联**：livetiming 完整接入 E（网络地基）/ F（track-delivery）/ G（lap-upload）是后续更大的网络批；D 是其本地起点。
