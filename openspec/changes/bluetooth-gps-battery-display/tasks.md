## 1. BleConnection — Battery Service GATT 集成

- [x] 1.1 在 `BleConnection.kt` companion object 中新增 `BATTERY_SERVICE_UUID`（`0x180F`）和 `BATTERY_LEVEL_UUID`（`0x2A19`）常量（`CCCD_UUID` 之后、`CONNECTION_TIMEOUT_MS` 之前）
- [x] 1.2 新增 `_batteryPercent: MutableStateFlow<Int?>` 字段和 `batteryPercent: StateFlow<Int?>` 公开属性（`_dataStale` 之后）
- [x] 1.3 在 companion object 中新增 `parseBatteryPercent(value: ByteArray?): Int?` 纯函数——第一字节 `0..100` 返回百分比，非法/空/null 返回 `null`
- [x] 1.4 新增 `setupBattery(gatt: BluetoothGatt)` 私有方法：发现 `0x180F` 服务 → 获取 `0x2A19` 特征 → 根据 PROPERTY_NOTIFY/PROPERTY_INDICATE/READ 执行相应订阅或读取
- [x] 1.5 在 `onDescriptorWrite` 中追加 Battery CCCD 写完毕→读回逻辑 + GPS 通知全部启用后触发 `setupBattery(gatt)`
- [x] 1.6 在 gattCallback 中新增 `onCharacteristicRead`（两重重载：API 33+ 含 value 参数 + deprecated 版本）处理 Battery Level 读取结果
- [x] 1.7 在 `handleCharacteristicChange` 开头拦截 `BATTERY_LEVEL_UUID` 通知——调用 `parseBatteryPercent` 更新 `_batteryPercent`，不进入 `onDataReceived` 数据流
- [x] 1.8 在 `cleanup()` 末尾追加 `_batteryPercent.value = null`
- [x] 1.9 编译 `:core:bluetooth:compileDebugKotlin` 通过
- [x] 1.10 `parseBatteryPercent` 单元测试：7 条用例（85/0/100/>100/空/null/255），路径 `core/bluetooth/src/test/.../BleConnectionBatteryTest.kt`

## 2. BluetoothDataSource — 代理电量 StateFlow

- [x] 2.1 在 `BluetoothDataSource.kt` 中新增 `_batteryPercent: MutableStateFlow<Int?>` 和 `batteryPercent: StateFlow<Int?>` 公开属性
- [x] 2.2 在 `doConnect()` 中新增 `launch { bleConnection?.batteryPercent?.collect { _batteryPercent.value = it } }`（与 `dataStale` 收集平级）
- [x] 2.3 在 `disconnect()` 中追加 `_batteryPercent.value = null`
- [x] 2.4 编译 `:core:bluetooth:compileDebugKotlin` 通过

## 3. Repository + ViewModel — 暴露电量到 UI 层

- [x] 3.1 `GpsDataRepository.kt`：新增 `val batteryPercent: StateFlow<Int?> = bluetoothDataSource.batteryPercent`
- [x] 3.2 `GpsDataViewModel.kt`：新增 `val batteryPercent: StateFlow<Int?>` 通过 `stateIn(WhileSubscribed(5000), null)` 暴露
- [x] 3.3 编译 `:feature:test:compileDebugKotlin` 通过

## 4. DeviceHomeScreen — BatteryIndicator UI

- [x] 4.1 确认 `feature/test/build.gradle.kts` 是否有 `material-icons-extended` 依赖；无则添加
- [x] 4.2 在 `DeviceHomeScreen.kt` 中新增 `BatteryIndicator` Composable：7 档电池图标映射 + Mechanical Small 百分比数字 + `%` unit；null 时灰色 `BatteryUnknown` + `N/A`
- [x] 4.3 `BatteryIndicator` 中所有 `Text` 加 `maxLines = 1, overflow = TextOverflow.Ellipsis`
- [x] 4.4 `ConnectedDeviceCard` 新增 `batteryPercent: Int?` 参数，在状态圆点行之后、按钮行之前插入电池行（仅在 `CONNECTED` 时显示）
- [x] 4.5 `DeviceHomeScreen` 中 `collectAsState` 收集 `batteryPercent` 并传递给 `ConnectedDeviceCard`
- [x] 4.6 编译 `:feature:test:compileDebugKotlin` 通过

## 5. 全量测试 + 真机验证

- [ ] 5.1 运行 `:core:bluetooth:testDebugUnitTest` 确认所有现有测试 + 新增 7 条 parseBatteryPercent 测试全绿
- [ ] 5.2 运行 `gradle testDebugUnitTest --offline` 全量单测确认无回归
- [ ] 5.3 vivo V2405A（小屏）：`adb install` debug APK，验证 ConnectedDeviceCard 加电池行后不换行/不溢出
- [ ] 5.4 华为 8KE0219522008434：连接 v2 GPS（有 Battery Service）→ 显示电池图标 + 百分比；连接 v1 GPS（无）→ 灰色 N/A；断连 → 电池行消失
