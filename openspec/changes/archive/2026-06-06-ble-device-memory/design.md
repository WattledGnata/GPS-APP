# Design: ble-device-memory

## Context

蓝牙设备记忆三功能(持久化+自动连/别名/记录管理)的实施设计。Baseline(2026-06-06 逐行核实):

- **数据层空架子**:`bluetooth_devices` 表(`BluetoothDeviceEntity`:address PK + name?)、`BluetoothDeviceDao`(getAllDevices/getAllDevicesSync/insertDevice[REPLACE]/deleteDevice)、`BluetoothDeviceRepository`(devicesFlow/saveDevices/getSavedDevices/addDevice/removeDevice)均存在且已注册 Koin(`feature/test/.../di/AppModule.kt:71` dao、`:94` repository),但生产代码零调用。
- **冷启动骨架**:`core/bluetooth/.../BleDeviceManager.kt:52-105` `autoReconnectLastDevice()`——查到地址则 connect + 10s 轮询(`RECONNECT_TIMEOUT_MS`)超时 startScan,无地址直接 startScan;`lastDeviceAddress` 硬编码 null(64 行 TODO)。`connect()`(129-147)141 行 TODO"连接成功后保存设备"。
- **模块依赖**:core/bluetooth 仅依赖 core/domain,**不依赖 core/data**(`fix-ble-auto-reconnect` design Context 侦查结论:接线需 feature 层 DI 闭包注入,模块图不动)。
- **连接链路**:`GpsDataViewModel`(Koin **single**,AppModule.kt:164,非生命周期 viewModel)`.connectDevice(ScannedDevice)`(GpsDataViewModel.kt:183-186)写 `_connectedDeviceName` + 调 `bleDeviceManager.connect(address)`;会话内意外断开自动重连已由 `fix-ble-auto-reconnect` 落地在 `BluetoothDataSource`(指数退避,不碰)。
- **UI**:`BleScanBottomSheet.kt` DeviceRow(258 行)展示 name/address/RSSI/SignalBars/DeviceLabel 分类徽标;`DeviceHomeScreen.kt` ConnectedDeviceCard(341 行)显示 `connectedDeviceName ?: "No device"`,TrackTechRow 设置区(214/220 行)。
- **Room**:`AppDatabase` version=7、exportSchema=false、`migrationChain` 模式 = `migrationXToYSql: List<String>`(internal,暴露 JVM 单测)+ `Migration` 对象;migration 单测惯例见 `core/data/src/test/.../PendingLapUploadMigrationTest.kt`(SQL string 直断 + migrationChain 覆盖断言)。
- **测试连锁**:`BleDeviceManagerTest.kt:30` `autoReconnectLastDevice_whenLastAddressNull_fallsBackToStartScan_sourceAssertion` 用**源码 grep 断言**锁"硬编码 null + fallback startScan"现状——本 round 填实后该测试必失败,MUST 同步改写;`GpsDataViewModelTest.kt:55` Mockito 构造 3 参数——VM 加第 4 依赖后 MUST 同步。
- **流程档位**:road-test-first(user 2026-06-06 授权)+ 复杂度 medium(Room migration 强制升级场景→本模式下 = CC 自审加深,不调子 agent)。

## Goals / Non-Goals

**Goals:**
- 连接成功的设备持久化(address/固件名/最近连接时间),含别名字段。
- 冷启动自动连接最近设备,失败/无记录 fallback 扫描(既有骨架语义不变)。
- 扫描列表显示别名 + "Last connected" 徽标;主屏显示别名优先。
- 已保存设备管理:改别名、删记录。
- 全链路 FileLogger 埋点(road-test-first 安全网)。

**Non-Goals:**
- 不碰 `BleConnection`(A40 一次性实例契约)与 `BluetoothDataSource`(会话内退避重连)。
- 不做多设备并发连接、设备白名单、RSSI 历史。
- 不改冷启动重连超时参数(RECONNECT_TIMEOUT_MS=10s 既有值)。
- 不做"忘记设备后阻止本次会话内重连"——删除记录只影响后续冷启动选择,不主动断开当前连接。

## Decisions

### Decision 1: 接线层级 = Koin 闭包注入 BleDeviceManager

Alternatives:
- (a) core/bluetooth 直接依赖 core/data(BleDeviceManager 注入 BluetoothDeviceRepository):改模块依赖图,违反既有分层(core/bluetooth 仅依赖 core/domain),且把 Room 拖进蓝牙模块编译闭包。拒绝。
- (b) 全部逻辑放 GpsDataViewModel(VM 观察 connectionState 写表 + 冷启动搬 VM):冷启动重连骨架已在 `BleDeviceManager.init`(52-105 行,含 10s 轮询 fallback),搬层级等于重写已验证逻辑;且写表会分散两处(手动连在 VM、自动连在 manager)。拒绝。
- (c) **闭包注入(选)**:`BleDeviceManager` 构造追加两个可空默认参数:
  ```kotlin
  private val lastDeviceProvider: (suspend () -> String?)? = null,
  private val onDeviceConnected: (suspend (address: String, name: String?) -> Unit)? = null,
  ```
  Koin `bluetoothModule`(AppModule.kt:81-85)注册处桥接 `get<BluetoothDeviceRepository>()`。模块图不动,逻辑收口 manager 一处,`fix-ble-auto-reconnect` design 沉淀的侦查结论即此方案。默认 null 保持 `BleDeviceManagerTest` 等既有构造可编译(渐进接线)。

**Koin 注册顺序 caveat**:`bluetoothModule` 在 `repositoryModule` 之前传入 startKoin(BlazePushApplication.kt:28-37),但 Koin 解析是惰性的(single lambda 调用时 get()),`BleDeviceManager` 首次被注入(GpsDataViewModel)时 repositoryModule 已注册完毕——闭包内 get() 不会 MissingDefinition。闭包内仍走 `get<BluetoothDeviceRepository>()` 延迟到 lambda 体执行时解析。

### Decision 2: 写表时机 = 首次 CONNECTED,upsert 不覆盖 alias

Alternatives:
- (a) `connect()` 发起时立即写表:连接失败的设备也被记忆,冷启动可能自动连一台从未成功过的设备。拒绝。
- (b) **connectionState 转 CONNECTED 时写(选)**:`BleDeviceManager.connect(address, name)` 加可选 name 参数并记 pending(address+name);manager 内 collect `connectionState`(已是代理 property,34 行),转 CONNECTED 且 pending 非空时调 `onDeviceConnected(address, name)` 并清 pending。语义与 BlazePush"上次连接过"一致——只记成功连过的。

**upsert 语义(关键)**:现有 `insertDevice` 用 `OnConflictStrategy.REPLACE`,直接复用会把已有行的 alias 抹掉。新增 DAO 方法对:
```kotlin
@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insertIfAbsent(device: BluetoothDeviceEntity): Long  // 返回 -1 = 已存在

@Query("UPDATE bluetooth_devices SET name = :name, lastConnectedAtMs = :ts WHERE address = :address")
suspend fun touchConnected(address: String, name: String?, ts: Long)
```
Repository 层 `recordConnected(address, name, ts)` = `insertIfAbsent` + `touchConnected`(顺序执行,不要求事务——两步幂等,中断最坏丢一次 touch)。alias 只经 `updateAlias` 路径变更。

**实施期补充(2026-06-06,非 Decision 修订——细化 name=null 场景)**:`touchConnected` 的 name 用 `COALESCE(:name, name)`——冷启动自动重连不经扫描无广播名(name=null),直接 UPDATE 会把已存固件名抹成 NULL;COALESCE 让 null 保留原值,手动连接(ScannedDevice.name 非空)正常刷新。单测 `recordConnected_nullName_keepsExistingFirmwareName` 锁此语义。

**pending 生命周期语义**:pending 代表"最后一次经 manager 发起的连接意图",仅 `manager.connect()` 设置/覆盖,首次 CONNECTED 写表后清空;**不在 DISCONNECTED 清**——connect 发起→15s 超时→`BluetoothDataSource` 退避重连→最终 CONNECTED 时 pending 仍在,正确落表(最终确实连上了)。pending 清空后的会话内再次断连重连(不经 manager.connect)**不会重复 touch 时间戳**——无害:同会话同设备,该设备本就是 lastConnectedAtMs 最大者,冷启动选择语义不受影响。冷启动路径(autoReconnectLastDevice 内部原直调 bluetoothDataSource.connect)改为经 manager 自身 `connect(address, name=null)` 以统一 pending 机制。

### Decision 3: schema 两列 nullable、无哨兵(盲点 #6)

```sql
ALTER TABLE bluetooth_devices ADD COLUMN alias TEXT
ALTER TABLE bluetooth_devices ADD COLUMN lastConnectedAtMs INTEGER
```
- `alias TEXT` NULL = 未设置别名 → 显示 fallback 固件名。
- `lastConnectedAtMs INTEGER` NULL = 无成功连接记录 → 冷启动查询 `WHERE lastConnectedAtMs IS NOT NULL` 天然排除。

Alternatives:
- NOT NULL DEFAULT 0:0 是 1970 时间戳哨兵,"最近设备"排序误命中 + 与"从未连接成功"语义混淆(盲点 #6 原型场景)。拒绝。
- NOT NULL DEFAULT (strftime now):migration 时刻被记成"连接时间",数据失真。拒绝。
- 当前表实际为空(零写入链路),migration 实际作用于空表,风险极低,但 schema 语义仍按上述设计(防呆)。

Room version 7→8,`migration7To8Sql: List<String>`(2 条 ALTER)+ `migration7To8` 对象 + `migrationChain` 追加,完全沿用 v5→v6(纯 ALTER nullable)既有模式(AppDatabase.kt:223-252)。

### Decision 4: 显示名优先级 helper 放 BluetoothDeviceModel 扩展

`BluetoothDeviceModel` 加 `alias: String?` + `lastConnectedAtMs: Long?` 字段,并提供:
```kotlin
val BluetoothDeviceModel.displayName: String
    get() = alias?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() } ?: address
```

Alternatives:
- 各 UI 调用点自行拼优先级:三处以上(扫描列表/主屏卡片/管理列表)重复 + 漂移风险。拒绝。
- 放 core/domain 新模型:BluetoothDeviceModel 在 core/data,迁移模型超 scope。拒绝。

### Decision 5: 已保存设备管理 UI = ModalBottomSheet + AlertDialog 改名

Alternatives:
- (a) 独立全屏 Screen + 导航入栈:Track Tech shell 是 4 tab 平铺结构,为低频管理页引入导航栈层级,重。拒绝。
- (b) **ModalBottomSheet(选)**:新文件 `SavedDevicesSheet.kt`,与 `BleScanBottomSheet` 同形态惯例。每行:displayName + address + 最近连接相对时间;行尾改名(铅笔 icon → `AlertDialog` + `OutlinedTextField`,确认调 VM.setAlias)与删除(垃圾桶 icon → **AlertDialog 二次确认**后执行,user 2026-06-06 拍板,防误触优先)。
- 删除当前已连接设备的记录:允许,不断开连接(Non-Goal 声明)。
- 改名入口**仅**管理 sheet 内(user 拍板):扫描列表行职责单一(选设备连接),不加长按/行内编辑。
- 入口:`DeviceHomeScreen` TrackTechRow 设置区(214 行附近)追加一行 "Saved devices"。
- 文案遵循 V2 视觉约束:所有行内 Text `maxLines=1 + Ellipsis`;别名输入 dialog 不限单行(MUST NOT 对输入框加 maxLines=1 的约束不适用——输入框非 metric/label)。

**UI 交互细化(user 2026-06-06 对齐)**:

1. **主屏入口**:`TrackTechRow(title="SAVED DEVICES", subtitle="<N> devices · <最近设备显示名>")` 插在 GPS DETAILS 与 SETTINGS 行之间;0 台时 subtitle = "None yet"。
2. **扫描列表标识形态**:沿用 DeviceRow 第二行彩色小字标签惯例(非 chip)——lastConnectedAtMs 最大者第二行显示 `"Last connected · <既有分类标签>"`,"Last connected" 用 `TrackTechColors.Green`(连接/成功语义),分类标签保持原色;其余行不变。第一行设备名 = displayName(别名优先)。
3. **管理 sheet 行布局**:第一行 displayName(当前连接中的设备行尾加绿点 + "Connected" 小字);第二行 `address · 相对时间`(<24h 用 "2h ago" 式,否则 "Jun 3" 式);行尾 ✏/🗑 图标按钮(直接可见,不做长按/侧滑——驾驶手套场景手势发现性差)。空态文案:"No saved devices yet — connect a device to remember it"。
4. **改名 dialog**:预填现有别名;清空保存 = 存 null 还原固件名显示,不设单独"恢复默认"按钮。
5. **冷启动反馈**:CONNECTING 且 `connectedDeviceName == null` 时 Hero statusLine 显示 "Auto-connecting last device…";CONNECTED 后名字回填(Decision 8)与手动连接无差别;超时 fallback 回既有 "Tap SCAN to find devices"(后台扫描已预热,A29 既有行为)。打断方式 = 既有 SCAN 按钮 + 点新设备即切换,不加额外取消控件。

### Decision 6: 冷启动目标 = 单表真相源按 lastConnectedAtMs 取最大

```kotlin
@Query("SELECT * FROM bluetooth_devices WHERE lastConnectedAtMs IS NOT NULL ORDER BY lastConnectedAtMs DESC LIMIT 1")
suspend fun getLastConnectedDevice(): BluetoothDeviceEntity?
```

Alternatives:
- DataStore 存 "lastAddress" 单 key:双数据源——用户删除设备记录后 DataStore 残留,冷启动自动连一台"已被清除"的设备,直接违背本 round"记录清除"功能语义。拒绝(单表真相源天然满足删除即遗忘)。
- devicesFlow 全量取回内存排序:多余 IO + 排序逻辑散落调用方。拒绝。

### Decision 7: 扫描列表 join + "Last connected" 徽标

`GpsDataViewModel` 注入 `BluetoothDeviceRepository`(构造第 4 参数),暴露:
```kotlin
val savedDevices: StateFlow<List<BluetoothDeviceModel>>   // devicesFlow.stateIn
```
`BleScanBottomSheet` 现签名为 `(visible, onDismiss, gpsViewModel)`——**已直接持有 VM**(DeviceHomeScreen.kt 底部调用处核实),savedDevices 由 sheet 内 `gpsViewModel.savedDevices.collectAsState()` 自行获取,不改签名。DeviceRow:
- 显示名:join(address)命中已保存且有 alias → alias,否则扫描广播名。
- 徽标:address == savedDevices 中 lastConnectedAtMs 最大者 → "LAST CONNECTED" chip,与既有 DeviceLabel 分类徽标并列(Last connected 在前)。

Alternatives:
- 调用方 collect 后逐参传入:sheet 已持有 VM,再拆参数徒增签名变更与调用点同步成本。拒绝。
- 不做 join 只显示广播名:别名功能对扫描场景失效(多设备分不清恰是痛点)。拒绝。

### Decision 8: 冷启动连上后主屏设备名回填

现状 `_connectedDeviceName` 仅 `connectDevice()` 写入;冷启动自动连成功后值为 null → 主屏显示 "No device" 但状态 Connected,矛盾。

方案(选):`GpsDataViewModel.init` 增加 collect:`connectionState` 转 CONNECTED 且 `_connectedDeviceName.value == null` 时,查 `repository.getLastConnectedDevice()?.displayName` 回填。同时 `setAlias()` 成功后若目标是当前连接设备,同步刷新 `_connectedDeviceName`。

Alternatives:
- BleDeviceManager 暴露 autoConnectedDeviceName StateFlow:manager 再扛 UI 显示职责且需闭包再查 displayName,职责漂移。拒绝。
- 主屏直接 collect savedDevices 派生:连接中设备 ≠ 最近记录设备的瞬态窗口(切设备中)会显示错名。拒绝(VM 收口)。

## FileLogger 埋点锚点(road-test-first MUST,统一 TAG="BleDeviceMemory")

| 锚点 | 调用点 | 内容 |
|---|---|---|
| 设备落库 | Koin 闭包 onDeviceConnected 执行后(AppModule) | `persisted addr=.. name=.. firstTime=<bool>` |
| 冷启动决策 | Koin 闭包 lastDeviceProvider 内(AppModule) | `cold-start target=<addr / none>` |
| 冷启动结果 | **实施期修订**:core 模块无 FileLogger,10s 轮询出口仅 logcat;落盘信号由组合覆盖——`cold-start target` + 既有 `BleLiveness connectionState -> ..` 全转移流 + 成功时 `cold-start name backfill`(VM Decision 8 回填) | 组合推断:target=X 后有无 CONNECTED |
| 别名变更 | VM.setAlias | `alias set addr=.. alias=..` |
| 记录删除 | VM.deleteSavedDevice | `record deleted addr=..` |

## Risks / Trade-offs

- **R1 migration 风险**:实际表为空(零写入链路),ALTER 作用于空表;仍按惯例写 `migration7To8Sql` 断言单测 + migrationChain 覆盖断言(模仿 PendingLapUploadMigrationTest)。→ 缓解:单测 + road-test 真机升级安装验证(华为机已有 v7 库)。
- **R2 既有测试连锁断裂**:`BleDeviceManagerTest` 两个源码断言锁旧 null 行为,填实后必红 → tasks 显式列改写任务(断言新形态:lastDeviceProvider 调用 + null 时 fallback startScan 保留);`GpsDataViewModelTest` 构造 3→4 参数 → 同步加 Mockito mock。两者在 apply 期 #14 自查清单内强制核对。
- **R3 设备不在场的冷启动行为**:自动连 10s 超时 fallback 扫描(既有骨架),但 `BluetoothDataSource` 会话内重连会对该 address 持续退避重试(fix-ble-auto-reconnect 的"无限重试直到用户意图变更"设计),与前台扫描并行——用户点新设备 connect 即取消旧重连(既有切设备逻辑)。此并行与现状用户手动连接失败后的行为一致,非本 round 引入。→ 透明声明,FileLogger 可诊断。
- **R4 扫描与 GATT connecting 并行**(manager 10s 轮询超时 startScan 时 BleConnection 15s 连接超时未到,短窗口扫描+连接并行):既有骨架行为(89 行),非本 round 引入,Android BLE 允许。→ 不动。
- **R5 Koin single 提前实例化时序**:GpsDataViewModel 是 single,首个 UI 注入点触发构造 → BleDeviceManager 构造 → init 冷启动自动连。时序与现状一致(manager 同为 single),闭包延迟解析 repository 无环。→ 声明。

## Migration Plan

1. core/data:entity/model/mapper/DAO/repository/migration + 单测(独立可编译里程碑)。
2. core/bluetooth:BleDeviceManager 闭包参数 + connect(name) + CONNECTED 观察 + 既有测试改写。
3. feature/test:Koin 接线 + VM 扩展 + UI(扫描列表徽标/别名、主屏回填、SavedDevicesSheet)。
4. 真机攒批路测(road-test-first):升级安装(v7→v8 migration 实测)→ 连接 → 重启 app 验证自动连 → 改名 → 删记录 → 再冷启动验证不再自动连。

回滚:整 round 单 commit 序列 revert 即可;migration 已发版后不可回撤(nullable 列残留无害)。

## Open Questions

- 无(UI 文案细节 LAST CONNECTED / Saved devices 等英文标签按 Track Tech V2 既有英文 UI 风格,apply 期定稿)。
