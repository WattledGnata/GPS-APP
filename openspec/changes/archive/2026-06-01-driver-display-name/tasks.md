## 1. 持久化层

- [x] 1.1 `datastore/UserProfileRepository.kt`：DataStore Preferences 双构造，`driverName: Flow<String>`（缺 key→空串）+ `setDriverName(name)`；key `driver_name`。
- [x] 1.2 `di/AppModule.kt` repositoryModule 注册 `single { UserProfileRepository(androidContext()) }`。

## 2. 设置页 UI

- [x] 2.1 `ui/settings/SettingsScreen.kt`：返回头 + "车手" section + `OutlinedTextField`（本地 draft 防光标跳 + LaunchedEffect 同步已存值 + onValueChange trim 即时持久化）；Track Tech V2 视觉 + Text maxLines=1+Ellipsis。
- [x] 2.2 `TrackTechAppShell` 注册 `composable("settings") { SettingsScreen(navController) }`。
- [x] 2.3 `DeviceHomeScreen` SETTINGS 行 onClick 从 Toast 改 `navController.navigate("settings")`，副标题改 "车手显示名 · 更多设置"。

## 3. 单元测试

- [x] 3.1 `UserProfileRepositoryTest`：空默认 / 写读 roundtrip / 覆盖写（3 测试，PreferenceDataStoreFactory 临时文件）。

## 4. 编译 + road-test-first gate

- [x] 4.1 `:feature:test:compileDebugKotlin` + `testDebugUnitTest --offline` 全绿。
- [x] 4.2 `:app:assembleDebug --offline` 构建 apk。FileLogger 锚点 `UserProfile`。
- [ ] 4.3 【真机·攒批·user 路测】进 Device tab → SETTINGS → 填车手名 → 杀进程重进保留；SETTINGS 不再弹 Toast。

## 10. Follow-up backlog

- [ ] 10.1 默认名预填（从 carModel / 蓝牙设备名）。
- [ ] 10.2 设置页扩展更多项（单位 / 语音开关接 VoiceAnnouncer.setEnabled / 自动重连）。
- [ ] 10.3 livetiming 网络批 E（INTERNET+HTTP client）/ F（track-delivery）/ G（lap-upload 带 driver + clientLapId 幂等）。
