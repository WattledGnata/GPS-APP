# Tasks: ble-device-memory

> 流程档位:road-test-first(去 Codex / 不调子 agent / FileLogger 兜底 / 真机攒批)。
> apply 启动前 MUST:`grep '^### Decision ' openspec/changes/ble-device-memory/design.md` 自检 Decision 列表(#17 条款);跑 #3(锚点 grep 对齐)/#14(测试连锁)/#16(共享字段 drift——本 round 改 BluetoothDeviceEntity,消费方仅本 round 新链路,无已合回 round 消费此字段,无 drift 风险)三项自查。
> 实现 MUST 在独立 git worktree 进行(base = 本地 feature/track-tech-v2)。

## 1. 锚点自查(apply 启动门槛,盲点 #3/#4)

- [x] 1.1 grep 验证锚点仍有效:`BleDeviceManager.kt` 64 行附近 `val lastDeviceAddress: String? = null`、141 行附近 `// deviceRepository.saveDevice`、`GpsDataViewModel.kt:183-186` `fun connectDevice`、`AppModule.kt:81-85` bluetoothModule / `:164` `single { GpsDataViewModel(get(), get(), get()) }`、`AppDatabase.kt:33` `version = 7`。任一漂移 → 以 grep 实际结果为准更新本文件锚点再继续。done:5 处 grep 全部命中或已修订。

## 2. core/data 数据层(独立可编译里程碑 1)

- [x] 2.1 `BluetoothDeviceEntity.kt` 加 `alias: String?` + `lastConnectedAtMs: Long?`(均默认 null);`BluetoothDeviceModel.kt` 同步加两字段 + `displayName` 扩展属性(design Decision 4 优先级);`EntityMapper.kt` 的 toEntity/toModel 同步映射。done:三文件编译通过。
- [x] 2.2 `BluetoothDeviceDao.kt` 新增三方法:`insertIfAbsent`(@Insert IGNORE 返回 Long)、`touchConnected`(@Query UPDATE name+lastConnectedAtMs WHERE address)、`updateAlias`(@Query UPDATE alias WHERE address)、`getLastConnectedDevice`(@Query WHERE lastConnectedAtMs IS NOT NULL ORDER BY DESC LIMIT 1)。done:DAO 编译通过。
- [x] 2.3 `BluetoothDeviceRepository.kt` 新增 `recordConnected(address, name, ts)`(insertIfAbsent+touchConnected 两步,design Decision 2)、`setAlias(address, alias)`、`getLastConnectedDevice(): BluetoothDeviceModel?`;既有方法不动。done:repository 编译通过。
- [x] 2.4 `AppDatabase.kt`:version 7→8;`migration7To8Sql`(2 条 ALTER,nullable 无 DEFAULT,Decision 3)+ `migration7To8` 对象 + `migrationChain` 追加链尾(完全模仿 v5→v6 模式,223-252 行)。done:编译通过。
- [x] 2.5 新增 `core/data/src/test/.../local/BleDeviceMemoryMigrationTest.kt`(首行 `// @IgnoreFormatCheck`,模仿 `PendingLapUploadMigrationTest.kt`):migrationChain 含 7→8 且为链尾、migration7To8Sql 含 2 条 ALTER 且含 alias/lastConnectedAtMs、SQL MUST NOT 含 `NOT NULL`/`DEFAULT`(盲点 #6 反例守护)。~~反射断言 @Database version=8~~(apply 期修订:Room @Database retention 非 RUNTIME 无法反射,参 AppDatabaseMigrationSqlTest:83 既有结论;version 语义由链尾断言等价覆盖)。done:`gradle :core:data:testDebugUnitTest --offline` 绿(131/132,唯一失败 case G 系 pre-existing 见 §10)。
- [x] 2.6 新增 `core/data/src/test/.../repository/BluetoothDeviceRepositoryTest.kt`(@IgnoreFormatCheck,内存 fake DAO——首次为 BluetoothDeviceDao 写 fake,需实现全部接口方法含既有 4 个,盲点 #14):recordConnected 新设备插入、recordConnected 已有 alias 行不清除 alias(spec REPLACE 反例)、setAlias 生效、getLastConnectedDevice 取 ts 最大者且排除 NULL、displayName 优先级(alias>name>address,含空白串 fallback)。done:测试绿。

### §2 apply 期连锁记录(2026-06-06)

- migration 链增长连锁更新(沿 AppDatabaseMigrationSqlTest:205 既有"名称保留断言更新"惯例):`AppDatabaseMigrationSqlTest` 链 size 5→6 ×2 + 链尾 v7→v8;`PendingLapUploadMigrationTest.migrationChain_lastIsSixToSeven` 改"6→7 后继为 7→8"位置断言(链尾断言移交本 round 测试)。
- `BinaryPerftestTelemetryRoundTripTest` case F 修复 worktree 自排除:删 `.filterNot { "/.worktrees/" }`(对齐 CrossingWallClockEscapeContractTest:48-50 已验证结论——mainJavaRoot 子树下无 worktrees,绝对路径排除在 worktree 内跑时致 0 文件假性红)。主区行为不变。

## 3. core/bluetooth 接线层(独立可编译里程碑 2)

- [x] 3.1 `BleDeviceManager.kt`:构造追加 `lastDeviceProvider: (suspend () -> String?)? = null` + `onDeviceConnected: (suspend (String, String?) -> Unit)? = null`(Decision 1);`connect(deviceAddress: String, deviceName: String? = null)` 加默认参,记 pending(address+name);新增 connectionState collect——转 CONNECTED 且 pending 非空 → 调 onDeviceConnected + FileLogger 不可用于 core 模块时用既有 Log.d + 由闭包侧(feature 层)落 FileLogger(Decision 2 + 埋点表);`autoReconnectLastDevice()` 64 行 null 替换为 `lastDeviceProvider?.invoke()`,冷启动 connect 改走自身 `connect(address)` 统一 pending,既有 10s 轮询/fallback 骨架不动。done:`gradle :core:bluetooth:compileDebugKotlin --offline` 通过。
- [x] 3.2 ~~改写~~ `BleDeviceManagerTest.kt`(apply 期修订:既有 2 个源码断言锁的是 else 分支结构 + "fallback 到扫描" 文案,本 round 改动保留该结构 → **不破坏无需改写**);追加新形态断言 `autoReconnectLastDevice_queriesLastDeviceProvider_andConnectsViaSelf`(锁 lastDeviceProvider?.invoke() + 旧硬编码 null 已移除 + 冷启动走自身 connect 统一 pending)。done:`gradle :core:bluetooth:testDebugUnitTest --offline` 92/92 绿。

## 4. feature/test 接线 + UI(里程碑 3)

- [x] 4.1 `AppModule.kt` bluetoothModule(81-85 行):`BleDeviceManager` 注册改为传两个闭包——`lastDeviceProvider = { get<BluetoothDeviceRepository>().getLastConnectedDevice()?.address }`、`onDeviceConnected = { addr, name -> repository.recordConnected(...) + FileLogger.d("BleDeviceMemory", "persisted ...") + "cold-start/persist" 锚点 }`(注意 Koin 惰性解析 caveat,Decision 1);viewModelModule 164 行 GpsDataViewModel 加第 4 个 get()。done:`gradle :feature:test:compileDebugKotlin --offline` 通过。
- [x] 4.2 `GpsDataViewModel.kt`:构造注入 `BluetoothDeviceRepository`;暴露 `savedDevices: StateFlow<List<BluetoothDeviceModel>>`(devicesFlow.stateIn);`connectDevice()` 改调 `bleDeviceManager.connect(device.address, device.name)`;新增 `setAlias(address, alias)`(含当前连接设备名同步,Decision 8 + FileLogger `alias set`)、`deleteSavedDevice(address)`(FileLogger `record deleted`);init 增加 CONNECTED 回填 `_connectedDeviceName`(Decision 8)。done:编译通过。
- [x] 4.3 同步 `GpsDataViewModelTest.kt:55` 构造(3→4 参数,Mockito mock BluetoothDeviceRepository,盲点 #14)。done:`gradle :feature:test:testDebugUnitTest --offline` 绿。
- [x] 4.4 `BleScanBottomSheet.kt`:签名不变(sheet 已持有 gpsViewModel,design Decision 7 修订),sheet 内 collect `savedDevices`;DeviceRow 显示名走 join(alias 优先)+ lastConnected 最大者行加 "Last connected" 标识(形态见 design 交互细化节;V2 约束:maxLines=1+Ellipsis)。done:编译通过。
- [x] 4.5 新增 `SavedDevicesSheet.kt`(@IgnoreFormatCheck;ModalBottomSheet,Decision 5 + UI 交互细化节):列表行 displayName(连接中行加绿点 Connected)/address·相对时间/行尾 ✏🗑 + 铅笔改名(AlertDialog+OutlinedTextField 预填,清空=还原固件名 → VM.setAlias)+ 垃圾桶删除(**AlertDialog 二次确认** → VM.deleteSavedDevice);空态文案。done:编译通过。
- [x] 4.6 `DeviceHomeScreen.kt`:GPS DETAILS 与 SETTINGS 行之间加 `TrackTechRow("SAVED DEVICES", subtitle="<N> devices · <最近设备名>"或"None yet")` 驱动 SavedDevicesSheet 显隐;ConnectedDeviceCard 显示名链路确认走回填后的 connectedDeviceName;Hero statusLine 冷启动 CONNECTING 态显示 "Auto-connecting last device…"(UI 交互细化 §5)。done:编译通过。

## 5. 整体验证 + 攒批路测准备

- [x] 5.1 全量编译 + 单测:`gradle :core:data:testDebugUnitTest :core:bluetooth:testDebugUnitTest :feature:test:testDebugUnitTest :app:compileDebugKotlin --offline` 全绿。done:命令输出确认。
- [x] 5.2 #17 自查:逐 Decision(1-8)比对实施无 drift;有 drift → 暂停按条款走修订。done:metrics.yaml 草稿记 `design_decisions_diverged_during_apply`。
- [x] 5.3 列出本 round FileLogger 埋点锚点清单(road-test-first MANDATORY,对照 design 埋点表 5 项)报告给 user。done:对话中列出。
- [x] 5.4 合回主区(ff-only)+ 看板 §5 更新;真机路测项挂攒批清单:升级安装(v7 库实测 migration)→ 连接 → 杀进程重启验证自动连 → 改名验证三处显示 → 删记录 → 再冷启动验证 fallback 扫描。done:看板更新 + 路测清单登记。

## 10. Follow-up backlog

- **fix-perftest-case-g-shape-drift**:`BinaryPerftestTelemetryRoundTripTest` case G("processFilteredData 形态 A: P4 shouldEnd 检查未命中")在主区 387edcb 即失败(本 round worktree fork 前已红,主区实测确认 pre-existing)——某已合回 round 改了 `TestSessionViewModel.processFilteredData` 形态未同步 M round 的 gate 锚点。修复 = 按现行代码形态更新 case G 行号锚点断言(或确认形态变化是否破坏 M round 锁的 fallback 语义)。本 round 按"verify 不扩 scope"不修。
