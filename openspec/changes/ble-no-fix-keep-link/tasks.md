# Tasks — ble-no-fix-keep-link

> 实施前必读：proposal.md（Why + 双端固件差异 + 跨 capability ripple）、design.md（4 Decisions + Risks）、specs/ble-connection-liveness/spec.md（3 Requirements，含反例锁）。

> **apply 进度（2026-06-02，road-test-first 模式）**：生产代码 §1-§5 + 日志 + UI 全部落地，三模块编译通过。
> §6 单测**全绿**：`core:bluetooth` 8 testsuites / 85 tests，fail=0 err=0；新增/改的 5 个 stale 测试
> （含 2 条反例锁）+ `BleConnectionLifecycleContractTest` 等既有测试全过，无回归。
> 构建曾被 aliyun 镜像对 KSP marker jar 持续 502 阻塞 → 已在 `settings.gradle` 把华为云
> （`repo.huaweicloud.com`）+ 腾讯云镜像加到 aliyun 之前（增量、可回退）解决。
> §9 真机 gate（仅真机 blazepush-peter 可复现丢星）待 user 放行。

## 1. 锚点 verify（apply 启动自查 · blind spot #3/#4 rebase 漂移防护）

- [x] 1.1 grep `private fun startDataTimeoutCheck` 于 `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BleConnection.kt`，确认当前到期分支含 `bluetoothGatt?.disconnect()` + `_connectionState.value = ConnectionState.DISCONNECTED`（原 ~304-311 行）；记录实际行号。done：实际行号与 design 描述一致或已更新工件。
- [x] 1.2 grep `private fun handleCharacteristicChange` 同文件，确认 `lastDataTime` 更新 + `startDataTimeoutCheck()` 唯一调用点（原 ~173-189 行）。done：定位准确。
- [x] 1.3 grep `data class GpsData` 于 `core/domain/src/main/java/com/blazepush/core/domain/model/GpsData.kt`，确认无 `isStale` 字段、且 `Empty` 工厂（原 ~35-50 行）列全字段。done：确认新增点。
- [x] 1.4 grep `connectionState` + `resetStats` 于 `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/GpsDataViewModel.kt`，确认 `filter { it == DISCONNECTED }.collect { resetStats() }`（原 ~83-86 行）——本 round MUST NOT 改它。done：记录为"不可回归"基线。

## 2. GpsData 新增 isStale 字段（core/domain）

- [x] 2.1 在 `GpsData.kt` data class 主构造尾部新增 `val isStale: Boolean = false`（紧随 `isTimeSynced` 默认值惯例，spec R3）。done：编译通过 + 默认 false。
- [x] 2.2 `GpsData.Empty` 工厂显式 `isStale = false`（或依赖默认值，二选一保持与现有 Empty 列全字段风格一致）。done：`GpsData.Empty.isStale == false`。
- [x] 2.3 grep 全仓（排除 `/bin/`、`/.worktrees/`）确认无 positional 构造 `GpsData(...)`（非具名）会因新增字段错位；如有，补具名或默认。done：零错位编译失败。

## 3. BleConnection：看门狗到期改置软状态、不拆链（core/bluetooth）

- [x] 3.1 新增 `private val _dataStale = MutableStateFlow(false)` + `val dataStale: StateFlow<Boolean> = _dataStale.asStateFlow()`，mirror 现有 `connectionState` 暴露形态（BleConnection.kt ~71-72）。done：编译 + 暴露只读 StateFlow。
- [x] 3.2 改 `startDataTimeoutCheck()` 到期分支（spec R1）：**删除** `bluetoothGatt?.disconnect()` 与 `_connectionState.value = DISCONNECTED`；**改为** `_dataStale.value = true` + 保留 `Log.w` 改文案为"数据静默：标记陈旧（不拆链）"。`ensureActive()`（A24 guard，~303 行）**保留不动**。done：grep 该函数体无 `disconnect(`/`DISCONNECTED`、含 `_dataStale.value = true`。
- [x] 3.3 在 `handleCharacteristicChange()`（~173-189）收到帧处置 `_dataStale.value = false`（与 `lastDataTime = ...` 同处；先于/独立于 timeoutJob 重启），spec R1 恢复场景。done：任意帧到达即 dataStale=false。
- [x] 3.4 verify `onConnectionStateChange(STATE_DISCONNECTED)`（~101-109）与公开 `disconnect()`（~228-231）**未改动**（真断开路径保留，spec R2）。done：两路径 diff 为零。
- [x] 3.5 verify 未在 `BluetoothDataSource`/`BleDeviceManager`/`BleDeviceScanner` 引入任何 `BluetoothGatt` 字段（`BleConnectionLifecycleContractTest` 反射契约不回归）。done：契约测试仍绿。

## 4. BluetoothDataSource：collect dataStale → 写 GpsData.isStale（core/bluetooth）

- [x] 4.1 在 `connect()` 的状态 collect 块（~69-76）旁，新增对 `bleConnection.dataStale` 的 collect：`_dataFlow.value = _dataFlow.value.copy(isStale = it)`，**不**触碰 `_connectionState`（spec R3）。建议复用/扩展 `connectionCollectJob` 的同 scope，切设备时一并 cancel（与 ~56-59 清理顺序一致）。done：dataStale=true 时 `_dataFlow.value.isStale==true` 且 connectionState 不变。
- [x] 4.2 `handleIncomingData()`（~124-139）parse 成功分支：在 `copy(isConnected = true, errorMessage = null)` 同时加 `isStale = false`（spec R3 显式翻转，防 parser copy 残留）。done：成功帧 isStale=false。
- [x] 4.3 `handleIncomingData()` parse 失败分支：在 `copy(isConnected = false)` 同时加 `isStale = false`（收到帧=非陈旧，即使这帧坏）。done：短包帧 isStale=false 且 isConnected=false。
- [x] 4.4 `disconnect()`（~92-101）verify 不需要额外处理 isStale（断开后 dataFlow 整体失效，连接态主导 UI）；如评估需要可在此 `copy(isStale = false)`，否则透明声明不动。done：决策记录。

## 5. UI 消费 isStale（feature/test · spec Decision 4）

- [x] 5.1 grep `connectionState`/`isConnected`/`gpsData` 在 `feature/test/.../ui/`（已知 `GpsDetailsScreen.kt:520`、`DataQualityCard.kt`、`LapDebugExecutionScreen.kt:208`），列出"断开/连接"文案点。done：消费点清单。
- [x] 5.2 在合适消费点（如 `DataQualityCard` 或 GPS 状态标签）加 isStale 分支：CONNECTED && isStale → 显示"等待卫星/信号丢失"语义；**不得**显示"已断开"。文案/视觉对齐现有风格。done：丢星时 UI 呈"等待卫星"非"已断开"。
- [x] 5.3 verify isStale UI 文案 `Text(...)` 遵守 V2 视觉约束（maxLines=1 + Ellipsis，若落在 metric/row/label 类）。done：符合 CLAUDE.md UI 约束。

## 6. 测试（spec 每条 Requirement 落地 · 含反例锁）

- [x] 6.1 **改** `BleConnectionTest.startDataTimeoutCheck_onTimeout_releasesGattAndTransitionsDisconnected`（:74）：反转断言为 spec R1 场景一——超时后 `disconnect()` 从未调用 + `connectionState==CONNECTED` + `dataStale.value==true`。重命名为 `..._marksStaleAndKeepsConnected`。done：测试绿且断言新行为。
- [x] 6.2 **改/删** `BleConnectionTest.startDataTimeoutCheck_triggeredDisconnectUsesCallbackReleasePath`（:149）：该测试断言旧"超时→disconnect 走回调释放"，与新行为冲突，改为 spec R1 恢复场景（静默 dataStale=true → 喂帧 → dataStale=false + 全程 CONNECTED）或删除并由 6.1 覆盖。done：无残留断言旧拆链行为。
- [x] 6.3 **新增** `BleConnectionTest` 源码结构断言（spec R1 反例锁）：截取 `startDataTimeoutCheck` 函数体（沿用 :96 `rapidCancelRestart_sourceHasEnsureActiveGuard` 的 source 截取手法），断言不含 `disconnect(`、不含 `DISCONNECTED`、含 `_dataStale`。done：违反时 fail。
- [x] 6.4 verify `BleConnectionTest` 既有 `onConnectionStateChange_stateDisconnected_closesGattAndNullsReference`（:135）+ `disconnect_doesNotCloseGattBeforeStateDisconnectedCallback`（:122）仍绿（spec R2 真断开路径不回归）。done：两测试通过未改。
- [x] 6.5 **新增** `BluetoothDataSourceTest` isStale 三场景（spec R3，mirror 既有 isConnected 契约测试 :66/:107 手法）：(a) 成功帧清 isStale；(b) 短包帧清 isStale 且 isConnected=false；(c) 反例锁——先 `setDataFlow(GpsData.Empty.copy(isStale=true))` 再喂成功帧 → isStale=false（证明显式翻转非 copy 残留）。done：三测试绿，(c) 在不显式翻转时会 fail。
- [x] 6.6 verify `BleConnectionLifecycleContractTest`（GATT 字段唯一所有者反射契约）仍绿。done：通过。

## 7. apply 期高频盲点自查（road-test-first / 加速通道补盲）

- [x] 7.1 **#16 跨 round 共享字段 drift**：`GpsData.isStale` 是 in-memory flow model 新字段，grep 确认无序列化/positional 消费 GpsData 的路径（binary writer / replay / Gson）会因新字段错位或隐式丢失（预期持久化走 LapTelemetrySample 等独立类型）。done：确认 GpsData 不被持久化序列化 positional 消费。
- [x] 7.2 **#3 grep 锚点对齐**：apply 完成后回扫 tasks 各 line 锚点与最终代码一致（rebase 漂移）。done：锚点对齐。
- [x] 7.3 **#14 fake DAO/接口签名**：本 round 未改任何 DAO/interface 签名（仅 GpsData data class + BleConnection 内部 + BluetoothDataSource 内部）→ 无 fake impl 需同步。done：确认 N/A。

## 8. 构建 + 测试验证

- [x] 8.1 `core/domain` + `core/bluetooth` 模块编译通过（注意本机 gradle 用 8.9 `--offline`，见 memory）。done：编译绿。
- [x] 8.2 跑 `core/bluetooth` 单测（BleConnectionTest + BluetoothDataSourceTest + BleConnectionLifecycleContractTest）全绿。done：测试绿，贴输出。
- [x] 8.3 跑 `gps-runtime-stats` 相关测试（若在 feature/test 模块有 GpsDataViewModel resetStats 测试）verify 不回归。done：不回归。

## 9. 真机验证 gate（MUST · 仅真机可复现）

- [x] 9.1 真机 blazepush-peter（华为 `8KE0219522008434`）连接后，制造丢星（遮挡天线/进室内）静默 >10s：验证 **不掉线**（设备仍连着）+ UI 显示"等待卫星/信号丢失"而非"已断开"。done：路测确认不拆链。
- [x] 9.2 丢星后恢复天空视野：验证数据**自动续上**、无需手动重连、isStale 提示消失。done：路测确认自愈续流。
- [x] 9.3 真机主动断开（关设备/走出范围）：验证 GATT 回调路径仍正常断开 + 状态回 DISCONNECTED（spec R2 真断开路径）。done：真断开仍生效。
- [x] 9.4 真机验证串行约束：install 前告知 user 当前 round/apk/场景，等授权（CLAUDE.md 并行协同强制串行规则）。done：user 放行后 install。

> 2026-07-23：user 明确确认以上丢星不断链与恢复续流场景均已路测，问题不大，按验收通过记录。

## 10. Follow-up backlog（延期立项 · 强制 memo 已沉淀）

- [x] 10.1 **② BLE 中途断开 auto-reconnect 自愈** — round 名建议 `ble-mid-session-auto-reconnect`。问题：`BleDeviceManager.autoReconnectLastDevice()` 仅在 `init` 跑一次（`BleDeviceManager.kt:46`）、且 `lastDeviceAddress` 硬编码 `null`（`:64` TODO 未实现），真链路死亡后不会自愈、需人工重扫。本 round（丢星不拆链）堵住误断后该问题紧迫性下降，但半开链路 / 真断开后仍无自动重连。完整设计 memo（9 章）见 `docs/design/ble-mid-session-auto-reconnect-deferred.md`。下次 `/opsx:ff ble-mid-session-auto-reconnect` 直接读该 memo 起草。

---

> push/commit 时机：CC 自驱推进到 apply 完成 + 真机验证；**push 由 user 拍板放行**（CLAUDE.md Git 规则 + 并行协同 push 顺序）。
