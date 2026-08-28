# Tasks: fix-ble-auto-reconnect

## 1. 锚点自检(apply 启动前,#3 盲点)

- [x] 1.1 grep 验证:`grep -n "connectGatt(context, false" core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BleConnection.kt`(line 235 一处);`grep -n "fun connect(deviceAddress" core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BluetoothDataSource.kt`(line 47);`grep '^### Decision ' openspec/changes/fix-ble-auto-reconnect/design.md`(5 决策)。

## 2. 实现(BluetoothDataSource.kt)

- [x] 2.1 字段:`lastRequestedAddress`/`userInitiatedDisconnect`/`reconnectJob`/`reconnectAttempt`;构造参数 `dispatcher: CoroutineDispatcher = Dispatchers.IO`(scope 改用之,Decision 5;AppModule.kt:83 构造点默认参数零改动)。
- [x] 2.2 `connect(address)` 公开入口:置 lastRequestedAddress/复位 userInitiated+attempt/取消 reconnectJob 后委托内部 `doConnect(address)`(原 47-98 主体迁移,Decision 4)。
- [x] 2.3 state collect 分支(原 line 79-83):CONNECTED → attempt=0;DISCONNECTED → `maybeScheduleReconnect()`(guard:!userInitiated && lastRequestedAddress!=null,Decision 3);`doConnect` catch 分支(原 89-96)置 DISCONNECTED 后同样调 maybeScheduleReconnect(design Risks 竞态条款)。
- [x] 2.4 `maybeScheduleReconnect()`:reconnectJob 活跃则跳过;`delay(min(30_000, 1_000L shl attempt))` 后 attempt++ 并 `doConnect(lastRequestedAddress)`(不复位 attempt,Decision 4);Log.d 锚点记 attempt 与延迟。
- [x] 2.5 `disconnect()`:userInitiated=true + 取消 reconnectJob(置于原 A27 清理之前)。

## 3. 单测(BluetoothDataSourceTest.kt,TestDispatcher + advanceTimeBy)

- [x] 3.1 spec R1:远端断开→1s 后自动 CONNECTING;失败退避 2s/4s 递增封顶 30s;CONNECTED 复位后再断从 1s 起。
- [x] 3.2 spec R2 反例:disconnect() 后任意推进时间无自动 CONNECTING;connect(B) 后对 A 的挂起重连取消;无连接历史不重连。
- [x] 3.3 spec R3:断开→2 失败→1 成功的 state 序列完整可见。
- [x] 3.4 既有用例全绿(handleIncomingData 契约不受影响)。

## 4. 自审 gate(road-test-first)

- [x] 4.1 CC 单遍自审 + #14(本 round 无 DAO 变化,预期空命中)/#16(无共享字段扩展,预期空命中)自查记录。
- [x] 4.2 `./gradlew :core:bluetooth:testDebugUnitTest` 全绿(Android library 模块,确认 task 名,纯 JVM 则 `test`)。

## 10. Follow-up backlog

- `cold-start-reconnect-wiring`:BleDeviceManager.autoReconnectLastDevice 的 lastDeviceAddress TODO 填实——BluetoothDeviceRepository 加 getLastConnected(DAO 需时间戳排序字段核实)+ feature 层 DI lambda 注入(core/bluetooth 不依赖 core/data,模块图不动);连接成功保存链路同步核实(bluetooth_devices 表写入点)。侦查结论见本 round design Context。
- `ble-reconnecting-ui-state`:ConnectionState 加 RECONNECTING 显式态(domain 公共模型,消费方多,需盘点)或 UI 层组合态(DISCONNECTED+重连排队中)显示"重连中…"。
