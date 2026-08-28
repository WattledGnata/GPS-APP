# Design: fix-ble-auto-reconnect

## Context

`BluetoothDataSource`(core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BluetoothDataSource.kt)是连接状态权威:`connect(deviceAddress)`(line 47-98)新建 `BleConnection` 并在 `connectionCollectJob` 内 collect 其 state 流传导到 `_connectionState`;`disconnect()`(line 100-110)按 A27 顺序清理。`BleConnection` 一次性使用:断开即 close GATT(A40),重连必须新建实例(走 `connect()` 全路径)。

DISCONNECTED 的三个来源(都应触发重连,除非用户主动):
1. 远端真断(BLE supervision timeout → onConnectionStateChange 回调);
2. 连接超时(BleConnection.kt:226-230,CONNECTING 15s 未果);
3. `connect()` 异常分支(BluetoothDataSource.kt:89-96)。

模块依赖:core/bluetooth → core/domain(仅此);FileLogger 在 feature/test,**不可引用**。feature 层 `GpsDataViewModel` 已有 `BleLiveness` FileLogger 锚点落盘所有 `connectionState ->` 转移(2026-06-03 路测即靠它定位)。

冷启动重连现状(本 round 非目标,侦查结论沉淀):`BleDeviceManager.autoReconnectLastDevice` 的 lastDeviceAddress 硬编码 null(TODO);`BluetoothDeviceRepository`(core/data)现有 devicesFlow/saveDevices/getSavedDevices/addDevice/removeDevice,**无 getLastConnected**(DAO 无时间戳排序);接线需 feature 层 DI 闭包注入(模块图不动)。

## Goals / Non-Goals

**Goals:**
- 意外断开(上述 3 来源)自动退避重连,直到成功或用户意图变更。
- 用户主动 disconnect / 切设备不触发重连。
- 重连过程经现有 state 流可观测(BleLiveness 日志免费获得)。

**Non-Goals:**
- 冷启动自动重连(backlog `cold-start-reconnect-wiring`)。
- `ConnectionState` 枚举加 RECONNECTING 值(公共 domain 模型,消费方多;CONNECTING 已足够表达,UI 增强另立项)。
- `BleConnection` 内部重试(违反 A40 一次性实例契约)。

## Decisions

### Decision 1: 重连逻辑归属 BluetoothDataSource

Alternatives:
- (a) `BleConnection` 内部重试:违反 A40"断开即 close、一次性实例"契约,且它不该自持新建自己的职责。拒绝。
- (b) `BleDeviceManager` 层:它只是 connect 转发方,connectionState 也是代理;在代理层 collect 状态再反调,绕一圈且测试基建弱于 DataSource。拒绝。
- (c) feature 层(GpsDataViewModel):VM 生命周期与 UI 绑定,后台(息屏跑圈)VM 可能不在;重连是传输层职责。拒绝。
- (d) **BluetoothDataSource(选)**:状态权威 + 持有 lastAddress + 已有 scope 与 collect 结构 + 单测基建(mockContext/mockParser)现成。

### Decision 2: 退避策略 = 1s·2^n 封顶 30s,无限重试

`delay = min(30_000, 1_000L shl attempt)` → 1s,2s,4s,8s,16s,30s,30s…;CONNECTED 或用户意图变更时复位 attempt=0。

Alternatives:
- (a) 固定间隔(如 5s):设备瞬断(供电抖动 1-2s)恢复慢;持续不在场时又重试过密。拒绝。
- (b) 有限次数(如 10 次后放弃):跑圈中设备超距几分钟后返回(进 pit 区)是真实场景,放弃后用户驾驶中无法手动恢复——恰是本 round 要消灭的处境。拒绝。
- (c) 无限 + 封顶 30s(选):瞬断秒级恢复,长断每 30s 一次 connectGatt(单次毫秒级 CPU + 系统层广播扫描,耗电可忽略);用户主动 disconnect 即停,App 进程死亡 scope 随之消亡,无泄漏路径。

### Decision 3: 用户意图区分 = userInitiatedDisconnect 标志 + lastRequestedAddress

- `connect(address)`(公开入口):`lastRequestedAddress = address`、`userInitiatedDisconnect = false`、复位 attempt、取消挂起 reconnectJob(切设备旧重连作废)。
- `disconnect()`(公开入口):`userInitiatedDisconnect = true`、取消 reconnectJob。
- collect 到 DISCONNECTED:`!userInitiatedDisconnect && lastRequestedAddress != null` → schedule 重连。
- collect 到 CONNECTED:attempt 复位 0。

Alternatives:
- (a) 由调用方(Manager/VM)传"是否自动重连"参数:把传输层策略泄漏给每个调用点,易漏传。拒绝。
- (b) 内部标志(选):语义即"最后一次用户意图"(connect=想连,disconnect=想断),两个公开入口天然覆盖全部意图变更点。

### Decision 4: 重连入口复用公开 connect() 全路径,但拆内部 doConnect 保退避计数

`connect()` 公开入口复位 attempt(用户重试应立即开始);重连路径调内部 `doConnect(address)`(同 A27 清理 + 新建逻辑,**不**复位 attempt)。避免"重连调 connect → attempt 永远 0 → 退避失效"。

Alternative(重连直接调 connect 并接受 attempt 复位):退避退化为恒 1s 重试,设备不在场时每秒一次 connectGatt,Android BT stack 高频 connect 有 133 错误风险。拒绝。

### Decision 5: 调度可测性 = 注入 delay 时长上限/调度器

测试用 `kotlinx-coroutines-test` 的 `TestDispatcher` 注入(构造参数 `dispatcher: CoroutineDispatcher = Dispatchers.IO`),`advanceTimeBy` 驱动退避;不引入额外抽象(不做 Clock 接口)。现有构造点(AppModule.kt:83 `single { BluetoothDataSource(androidContext(), get()) }`)默认参数零改动。

Alternative(真实 delay + Thread.sleep 测试):分钟级测试时长 + flaky。拒绝。

## Risks / Trade-offs

- **StateFlow 初值 replay 假重连(apply 期发现,实现细节加固非 Decision 修订)**:`BleConnection._connectionState` 初值恒 DISCONNECTED,每次 doConnect 新建的 collect 会先收到 replay 初值 → 若不处理会立即误调度重连,1s 后拆掉刚建好的连接。双保险:collect `drop(1)` 跳过 replay + CONNECTED 分支取消挂起 reconnectJob。spec R2 已补反例 scenario 锁回归。

- **状态闪变**:重连周期内 UI 状态 DISCONNECTED↔CONNECTING 交替,LapLive 红 banner 可能闪烁;接受(可见即可诊断),RECONNECTING 显式态列 UI backlog。
- **与 Bluetooth 关闭的交互**:系统蓝牙关闭时 connectGatt 立即失败 → 退避继续(30s 一次,无害);蓝牙重开后下一轮尝试自然成功。不做 BluetoothAdapter 状态监听(改动面+权限面,收益小)。
- **重连与 connect 异常分支竞态**:`connect()` catch 分支(line 89-96)直接置 DISCONNECTED——该路径不经 collect(直接赋值 `_connectionState`),需确认 schedule 同样触发:实现时在 catch 分支后也走 schedule 判定(tasks 锁)。
- **core/bluetooth 无落盘日志**:重连尝试本身只进 logcat(Log.d);落盘可观测靠 feature 层 BleLiveness 的状态转移日志(每次尝试必然产生 `-> CONNECTING` 转移)。声明:若路测需要 attempt 计数落盘,后续在 GpsDataViewModel 扩展,不动模块图。
- **测试基建教训(apply 期排查,2026-06-04)**:`runTest` 收尾的 advanceUntilIdle 与"无限重试"语义天然冲突——虚拟时钟上永远有下一个重连 delay → 收尾无限推进 → 无限 doConnect → OOM(mockStatic(Log) 的 invocation 记录加速堆爆)。每个用例 MUST 以 `source.disconnect()` 收尾让调度器可 idle;新测试类不 mockStatic(Log)(模块 isReturnDefaultValues=true 已兜底)。
