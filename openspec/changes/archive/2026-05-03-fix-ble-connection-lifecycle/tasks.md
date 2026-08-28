# 实施任务（依赖顺序）

本战役按 6 个 Requirement 组织任务，严格执行顺序建议：

1. **R1 死代码清零**必须先做（删 `ConnectionManager.kt`），之后 R2~R5 的所有
   改动都在"`BleConnection` 是 GATT 生命周期唯一持有者"前提下进行
2. **R2 + R3** 改同一文件 `BleConnection.kt`，强耦合（close 路径统一），同批做
3. **R4 + R5** 改 `BluetoothDataSource.kt` + `RaceChronoParser.kt`，R4 先 / R5
   后（isConnected 语义收敛先建立，连接清旧后补；两者合 1 commit）
4. **R6** 改 `BleDeviceManager.kt` + 修订 review 文档（A45 / A46），与 R1~R5
   独立

合流门槛集中在第 7 节。每组最末条是"**运行门槛测试**"自检，独立于第 7 节
总门槛；总门槛覆盖全模块回归。

---

## 1. R1 死代码清零 + 连接职责收敛（A23 + A42）

- [x] 1.1 **预先校验 grep 清零证据**：执行 `grep -Rn "ConnectionManager"
      core/ feature/ app/ 2>/dev/null`，确认当前命中仅限于 `ConnectionManager.kt`
      自身（145 行）与注释 —— 若有外部引用，**先加 TODO 再删**避免编译断裂。
      预期命中 ≤ 5 行（文件内自身命中，外部应为 0）。
- [x] 1.2 **删除文件**：`rm core/bluetooth/src/main/java/com/blazepush/core/bluetooth/ConnectionManager.kt`
      （145 行全删）。
- [x] 1.3 **验证 AppModule 无残留**：执行 `grep -n "ConnectionManager"
      feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt`，
      确认输出为空。若有 factory / single 引用，**顺手删除**并加 import 清理。
- [x] 1.4 **验证全仓零残留**：执行 `grep -Rn "ConnectionManager" core/
      feature/ app/ docs/ 2>/dev/null | grep -v "attack-backlog.md" | grep -v
      "2026-04-22-lap-timing-and-gps-adversarial-review.md" | grep -v "proposal.md"
      | grep -v "spec.md"`，输出 MUST 为空（文档引用不在验证范围，代码与
      DI 残留必须 0）。
- [x] 1.5 **门槛自检**：`./gradlew :core:bluetooth:assembleDebug`
      BUILD SUCCESSFUL（无 unresolved reference）。
- [x] 1.6 **新增 Spec Scenario 测试**（可放 `BleConnectionTest` 或独立
      `BleConnectionLifecycleContractTest.kt`，后者更贴合 "职责契约" 语义）：
      `bleConnectionLifecycle_gattOwnership_bluetoothGattFieldOnlyInBleConnection`
      —— 使用 JVM reflection 或 Kotlin `Class.declaredFields` 检查
      `BluetoothDataSource` / `BleDeviceManager` / `BleDeviceScanner` 均不
      持有 `BluetoothGatt` 类型字段。

## 2. R2 + R3 BleConnection 生命周期（A24 + A40）

- [x] 2.1 **代码改动 A24 race + 释放**：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BleConnection.kt:244-252`
      `startDataTimeoutCheck` 改为：
    ```kotlin
    private fun startDataTimeoutCheck() {
        timeoutJob = scope.launch {
            delay(DATA_TIMEOUT_MS)
            ensureActive()
            if (System.currentTimeMillis() - lastDataTime > DATA_TIMEOUT_MS) {
                Log.w(TAG, "数据接收超时：释放 GATT 资源")
                bluetoothGatt?.disconnect()
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }
    ```
    **注意**：
    - 不再调 `_connectionState.value = DISCONNECTED` 前的 `bluetoothGatt?.close()`（R3）
    - `ensureActive()` 出现在 `delay` 结束之后、`if` 判断之前
- [x] 2.2 **代码改动 A40 disconnect 只做异步**：`BleConnection.kt:172-178`
      `disconnect()` 改为：
    ```kotlin
    fun disconnect() {
        cleanup()
        bluetoothGatt?.disconnect()
    }
    ```
    删除 `bluetoothGatt?.close()`、`bluetoothGatt = null`、`_connectionState.value
    = DISCONNECTED` 三行（由回调接管）。
- [x] 2.3 **代码改动 A40 回调补 close + null**：`BleConnection.kt` gattCallback
      的 `onConnectionStateChange(STATE_DISCONNECTED)` 分支（73-77 行）：
    ```kotlin
    BluetoothProfile.STATE_DISCONNECTED -> {
        Log.d(TAG, "已断开连接（回调）")
        _connectionState.value = ConnectionState.DISCONNECTED
        cleanup()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
    ```
- [x] 2.4 **import 补齐**：确认 `import kotlinx.coroutines.ensureActive` 或
      `import kotlinx.coroutines.job.ensureActive`（按 Kotlin coroutines 版本
      选择对应路径）。
- [x] 2.5 **新增测试** `BleConnectionTest.startDataTimeoutCheck_onTimeout_releasesGattAndTransitionsDisconnected`
      （对应 Spec R2 Scenario 1）：
    - 使用 Robolectric 或 fake GATT stub 构造 `BleConnection`
    - 预设 state=CONNECTED、lastDataTime=0L、timeoutJob null
    - 调用 `startDataTimeoutCheck()` 并推进虚拟时钟 > DATA_TIMEOUT_MS
    - 断言 `bluetoothGatt.disconnect()` 被调 1 次、`_connectionState.value ==
      DISCONNECTED`、`bluetoothGatt.close()` **未**被调、字段非 null
- [x] 2.6 **新增测试** `BleConnectionTest.startDataTimeoutCheck_rapidCancelRestart_doesNotProduceSpuriousDisconnected`
      （对应 Spec R2 Scenario 2，硬区分 v1/v2）：
    - 使用 coroutine test dispatcher 构造 cancel/restart 100 次循环，每次让
      `delay` 恰在 cancel 到达前 1ms 结束
    - 断言 `_connectionState.value` 始终保持 CONNECTED（无 DISCONNECTED 瞬间）
    - 硬区分：v1 无 `ensureActive()` 应出现 >=1 次抖动
- [x] 2.7 **新增测试** `BleConnectionTest.disconnect_doesNotCloseGattBeforeStateDisconnectedCallback`
      （对应 Spec R3 Scenario 1）：
    - 调 `disconnect()`，立即检查 `bluetoothGatt` 非 null、`close()` 未调
- [x] 2.8 **新增测试** `BleConnectionTest.onConnectionStateChange_stateDisconnected_closesGattAndNullsReference`
      （对应 Spec R3 Scenario 2）：
    - 触发 fake gattCallback.onConnectionStateChange(gatt, 0, STATE_DISCONNECTED)
    - 断言 `bluetoothGatt.close()` 被调 1 次、字段变 null、`_connectionState
      == DISCONNECTED`、`cleanup()` 被调
- [x] 2.9 **新增测试** `BleConnectionTest.startDataTimeoutCheck_triggeredDisconnectUsesCallbackReleasePath`
      （对应 Spec R3 Scenario 3）：
    - 组合：R2 超时触发 disconnect → fake 回调到达 → 检查 close + null 执行
      一次
- [x] 2.10 **门槛自检**：`./gradlew :core:bluetooth:testDebugUnitTest --tests
       "*BleConnectionTest*"` 全绿。

## 3. R4 `isConnected` 语义收敛（A25）

- [x] 3.1 **代码改动 parser 短包（两路对称）**：
      `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt`
      对 GPS_MAIN + GPS_TIME 两个 parse 函数的短包分支对称处理：
    ```kotlin
    // parseGpsData 短包（行 139）BEFORE → AFTER：
    if (data.size < 20) { return currentData }
    → if (data.size < 20) { return currentData.copy(errorMessage = "short-packet") }

    // parseGpsTimeData 短包（行 71）BEFORE → AFTER：
    if (data.size < 3) { Log.e(...); return currentData }
    → if (data.size < 3) { Log.e(...); return currentData.copy(errorMessage = "short-packet") }
    ```
    第五轮 review 指出：原本只修 parseGpsData 漏了 parseGpsTimeData，两路 parse 函数
    MUST 对称实现失败信号。
- [x] 3.2 **代码改动 parser catch（两路对称）**：
    ```kotlin
    // parseGpsData catch（行 302）AFTER：
    } catch (e: Exception) {
        Log.e(TAG, "Error parsing GPS data", e)
        return currentData.copy(errorMessage = "parse-error: ${e.message}")
    }

    // parseGpsTimeData catch（行 106）AFTER：
    } catch (e: Exception) {
        Log.e(TAG, "Error parsing GPS time data", e)
        currentData.copy(errorMessage = "parse-error: ${e.message}")
    }
    ```
- [x] 3.3 **代码改动 BluetoothDataSource 分支**：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BluetoothDataSource.kt:49-56`
      回调内整体重写为 nullable parseResult + if-not-null 包裹写入：
    ```kotlin
    BEFORE:
    bleConnection = BleConnection(context, deviceAddress) { uuid, rawData ->
        val gpsData = when (uuid.toString()) {
            "00000003-0000-1000-8000-00805f9b34fb" -> parser.parseGpsData(rawData, _dataFlow.value)
            "00000004-0000-1000-8000-00805f9b34fb" -> parser.parseGpsTimeData(rawData, _dataFlow.value)
            else -> _dataFlow.value
        }
        _dataFlow.value = gpsData.copy(isConnected = true)
    }

    AFTER:
    bleConnection = BleConnection(context, deviceAddress) { uuid, rawData ->
        val parseResult: GpsData? = when (uuid.toString()) {
            "00000003-0000-1000-8000-00805f9b34fb" -> parser.parseGpsData(rawData, _dataFlow.value)
            "00000004-0000-1000-8000-00805f9b34fb" -> parser.parseGpsTimeData(rawData, _dataFlow.value)
            else -> null   // 未知 UUID：bypass，_dataFlow.value 完全不触碰
        }
        if (parseResult != null) {
            _dataFlow.value = if (parseResult.errorMessage != null) {
                // 失败分支：**显式** 置 isConnected=false，堵住 "上一帧成功 → 当前帧失败"
                // 时 parser copy 保留前帧 isConnected=true 的契约漏洞（第四轮 review 挑出）
                parseResult.copy(isConnected = false)
            } else {
                parseResult.copy(isConnected = true, errorMessage = null)
            }
        }
    }
    ```
    **关键 1**：未知 UUID 分支通过 `parseResult == null` 让整个写入块跳过，
    不再走 `gpsData.copy(isConnected = true)`。这是 Spec R4 Scenario 4 的
    硬断言 "GIVEN isConnected == false → THEN 仍 false" 能通过的前提。

    **关键 2**（第四轮 review 挑出的契约漏洞）：失败分支 MUST 显式
    `parseResult.copy(isConnected = false)` 而不是 `_dataFlow.value = parseResult`。
    parser 失败路径 `currentData.copy(errorMessage = ...)` 保留前帧 `isConnected`
    字段，若不显式翻转，"上一帧成功 → 当前帧短包" 路径会输出 `isConnected = true +
    errorMessage != null` 状态自相矛盾。显式 false 堵死此漏洞（Spec R4 Scenario
    "成功后失败" 硬断言锁定）。
- [x] 3.4 **新增测试** `RaceChronoParserTest.parseGpsData_shortPacket_setsErrorMessageShortPacket`
      （对应 Spec R4 Scenario 1 parser 侧）：
    - 输入 10 字节 rawData
    - 断言返回 `gpsData.errorMessage == "short-packet"`
- [x] 3.5 **新增测试** `RaceChronoParserTest.parseGpsData_throwsException_setsErrorMessageParseError`
      （对应 Spec R4 Scenario 2 parser 侧）：
    - 输入特制会触发 NumberFormatException 的 rawData
    - 断言返回 `gpsData.errorMessage` 以 "parse-error" 开头
- [x] 3.6 **新增测试** `BluetoothDataSourceTest.onDataReceived_shortPacket_doesNotFlagIsConnectedTrue`
      （对应 Spec R4 Scenario 1 BluetoothDataSource 侧，硬区分 v1/v2）：
    - 使用 fake `RaceChronoParser` 返回 `errorMessage = "short-packet"` 的
      gpsData
    - 触发回调
    - 断言 `_dataFlow.value.isConnected == false`、`errorMessage == "short-packet"`
    - 硬区分：v1 下 `isConnected == true`（被污染）
- [x] 3.7 **新增测试** `BluetoothDataSourceTest.onDataReceived_successfulParse_clearsErrorMessageAndFlagsIsConnectedTrue`
      （对应 Spec R4 Scenario 3）：
    - 预设 `_dataFlow.value.errorMessage == "short-packet"`（上次残留）
    - fake parser 返回正常解析结果 `errorMessage == null`
    - 触发回调
    - 断言 `_dataFlow.value.isConnected == true` 且 `errorMessage == null`
- [x] 3.8 **新增测试** `BluetoothDataSourceTest.onDataReceived_unknownUuid_doesNotFlipIsConnected`
      （对应 Spec R4 Scenario 4）：
    - 初始 `_dataFlow.value.isConnected == false`
    - 触发未知 UUID 回调
    - 断言 `_dataFlow.value.isConnected == false`（未被强置 true）
- [ ] 3.11 **新增代码改动 parser 成功路径清 errorMessage（契约闭合，两路对称）**：
      第五轮 review 推导发现：parser 的 `currentData` 参数是 `_dataFlow.value`，`copy` 默认
      保留未指定字段。若前帧失败（errorMessage != null），parser 成功路径 copy 不显式清
      → 前帧 errorMessage 被 carry → 下游 BluetoothDataSource 把本帧 parse 成功误走
      失败分支 copy(isConnected=false) → "短包后第一帧成功永远无法恢复 isConnected=true"
      级联故障。
    ```kotlin
    // parseGpsData 成功路径（行 276 附近）AFTER：
    currentData = currentData.copy(
        timestamp = protocolTimestamp,
        ...
        isTimeSynced = syncedNow,
        errorMessage = null  // 显式切断级联
    )

    // parseGpsTimeData 成功路径（行 101）AFTER：
    if (!currentData.isTestReady) {
        currentData.copy(isTestReady = true, errorMessage = null)
    } else {
        currentData.copy(errorMessage = null)
    }
    ```
- [ ] 3.12 **新增测试** `RaceChronoParserTest.parseGpsTimeData_shortPacket_setsErrorMessageShortPacket`
      （对应 Spec R4 新增 Scenario "GPS_TIME 短包"）：
    - 输入 2 字节 rawData（< 3 字节阈值）
    - 调 `parser.parseGpsTimeData(data, createTestData())`
    - 断言 `result.errorMessage == "short-packet"`
- [ ] 3.13 **新增测试** `RaceChronoParserTest.parseGpsTimeData_catchBlockSetsErrorMessageParseError_sourceAssertion`
      （对称 3.5，源码锚点 "Error parsing GPS time data"）：
    - 锚点字符串 "Error parsing GPS time data" 精确定位 parseGpsTimeData 的 catch
    - 断言 catchBody 含 `errorMessage = "parse-error:`
- [ ] 3.14 **新增测试** `BluetoothDataSourceTest.onDataReceived_gpsTimeShortPacket_doesNotFlagIsConnectedTrue`
      （对应 Spec R4 Scenario "GPS_TIME 短包不污染 isConnected"）：
    - 预设 `_dataFlow.value.isConnected = true`（前帧成功）
    - fake parser `parseGpsTimeData` 返回 `currentData.copy(errorMessage = "short-packet")`
    - 调 `source.handleIncomingData(gpsTimeUuid, ByteArray(2))`
    - 断言 `_dataFlow.value.isConnected == false` + `errorMessage == "short-packet"`
    - 硬区分：第五轮 review 前 `parseGpsTimeData` 短包不写 errorMessage →
      BluetoothDataSource 走成功分支 `copy(isConnected = true)` → isConnected 仍 true；
      第五轮修补后 parser 写 errorMessage → 失败分支 copy(isConnected=false)
- [ ] 3.15 **新增测试** `BluetoothDataSourceTest.onDataReceived_shortPacketThenSuccess_recoversIsConnectedTrue`
      （对应 Spec R4 新增 Scenario "短包后第一帧成功 MUST 恢复 true"，**端到端级联验证**）：
    - 第 1 步：fake parser.parseGpsData 返回 `currentData.copy(errorMessage="short-packet")`
      → 触发 handleIncomingData → `_dataFlow.value.isConnected = false` +
      `errorMessage = "short-packet"`
    - 第 2 步：fake parser.parseGpsData 返回正常解析结果（errorMessage = null，
      模拟 parser 成功路径已显式清 errorMessage）
    - 第 3 步：再触发 handleIncomingData → 断言
      `_dataFlow.value.isConnected == true` + `errorMessage == null`
    - 硬区分：第五轮 review 前 parser 成功路径不清 errorMessage → fake parser 返回
      carry 前帧 errorMessage → 下游失败分支 → isConnected 永远 false（级联）；
      修补后显式清 → 成功分支恢复 true
- [ ] 3.10 **新增测试** `BluetoothDataSourceTest.onDataReceived_successThenShortPacket_flipsIsConnectedBackToFalse`
      （对应 Spec R4 新增 Scenario "成功后失败"，第四轮 review 补齐）：
    - 第 1 步预设：`_dataFlow.value.isConnected = true` + `errorMessage = null`
      （模拟上一帧成功 parse 的结果；也可以先喂一个成功 parse 让状态自然变成此态）
    - 第 2 步：fake parser 返回 `_dataFlow.value.copy(errorMessage = "short-packet")`
      —— 注意 parser 的 copy 保留 isConnected=true 字段
    - 第 3 步触发 `handleIncomingData(gpsMainUuid, ByteArray(10))`
    - 断言：`_dataFlow.value.isConnected == false`（**显式** 被翻转，非简单保留）
    - 断言：`_dataFlow.value.errorMessage == "short-packet"`
    - **硬区分**：第三轮 v2 下 `_dataFlow.value = parseResult` 保留 isConnected=true
      → 本断言 fail；第四轮 v3 下 `parseResult.copy(isConnected = false)` → 本断言 pass
- [x] 3.9 **门槛自检**：`./gradlew :core:bluetooth:testDebugUnitTest --tests
      "*RaceChronoParserTest*"` + `--tests "*BluetoothDataSourceTest*"` 全绿。

## 4. R5 `connect()` 切设备前清旧连接（A27）

- [x] 4.1 **代码改动 BluetoothDataSource.connect**：`BluetoothDataSource.kt:42-82`
      `try` 块开头按严格顺序插入：
    ```kotlin
    fun connect(deviceAddress: String) {
        Log.d(TAG, "connect() called with address: $deviceAddress")
        scope.launch {
            try {
                // A27 切设备前清旧连接（严格顺序）
                connectionCollectJob?.cancel()
                connectionCollectJob = null
                bleConnection?.disconnect()
                bleConnection = null

                _connectionState.value = ConnectionState.CONNECTING
                bleConnection = BleConnection(context, deviceAddress) { uuid, rawData ->
                    // R4 分支在第 3 组已改
                    ...
                }
                bleConnection?.connectionState?.let { stateFlow ->
                    connectionCollectJob = scope.launch {
                        stateFlow.collect { state ->
                            Log.d(TAG, "BleConnection 状态变化: $state")
                            _connectionState.value = state
                        }
                    }
                }
                bleConnection?.connect()
            } catch (e: Exception) { ... }
        }
    }
    ```
    删除原 60-67 行内联的 `connectionCollectJob?.cancel()`（已在 try 开头统一处理）。
- [x] 4.2 **新增测试** `BluetoothDataSourceTest.connect_whileAlreadyConnected_releasesPreviousConnection`
      （对应 Spec R5 Scenario 1，硬区分 v1/v2）：
    - 先 `connect("AA:AA:AA:AA:AA:AA")`，等 state CONNECTED
    - 记录当前 `bleConnection` 实例引用 `oldBle`
    - 再 `connect("BB:BB:BB:BB:BB:BB")`
    - 断言 `oldBle.disconnect()` 被调 1 次、`bleConnection` 指向新实例、新
      实例 `deviceAddress == "BB:..."`
    - 硬区分：v1 下 oldBle.disconnect 未被调 → 旧 GATT 泄漏
- [x] 4.3 **新增测试** `BluetoothDataSourceTest.connect_cancelsPreviousCollectJobBeforeNewConnection`
      （对应 Spec R5 Scenario 2）：
    - 使用 Kotlin coroutines test 观察 job lifecycle
    - 断言旧 collectJob 的 `isCancelled == true` **早于**新 bleConnection 构造完成
- [x] 4.4 **新增测试** `BluetoothDataSourceTest.connect_sameAddressTwice_pathIsIdempotent`
      （对应 Spec R5 Scenario 3）：
    - 连续 `connect(sameAddress) x 2`
    - 断言流程不抛异常、旧 disconnect 被调、state 最终是 CONNECTING
- [x] 4.5 **门槛自检**：`./gradlew :core:bluetooth:testDebugUnitTest --tests
      "*BluetoothDataSourceTest.connect*"` 全绿。

## 5. R6 `autoReconnectLastDevice` else fallback 扫描（A29 + A46 + A45 捆绑）

- [x] 5.1 **代码改动 BleDeviceManager else 分支**：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/BleDeviceManager.kt:85-87`
    ```kotlin
    BEFORE:
    } else {
        Log.d(TAG, "没有上次连接的设备记录")
    }

    AFTER:
    } else {
        Log.d(TAG, "没有上次连接的设备记录，fallback 到扫描")
        startScan()
    }
    ```
- [x] 5.2 **review 文档修订 A46**：`docs/superpowers/reviews/2026-04-22-gps-ingestion-and-filter-review.md § 11.6`
      按 `2026-04-22-lap-timing-and-gps-adversarial-review.md § 3.2` 的替代
      文本改写。关键行：
    - 小节标题："`BleDeviceManager.autoReconnectLastDevice` 实质未实现，
      且 `else` 分支原先不 fallback"
    - 主体："`BleDeviceManager.kt:59` 硬编码 `lastDeviceAddress = null`，TODO
      挂着。`else` 分支（84-87 行）**已在战役 G 修复**为调 `startScan()`
      （A29）。"
    - 影响描述："冷启动后 app 自动开始扫描（战役 G 之前的行为：只 log 不 scan，
      用户必须手动点"扫描"按钮）"
    - 后续工作："`lastDeviceAddress` TODO 留给下一战役 `fix-ble-reconnection-layer`
      接入 `BluetoothDeviceRepository`。"
- [x] 5.3 **review 文档修订 A45（捆绑，评审方批准后执行）**：
      `docs/superpowers/reviews/2026-04-22-gps-ingestion-and-filter-review.md § 11.5`
      按 `2026-04-22-lap-timing-and-gps-adversarial-review.md § 3.1` 的替代
      文本改写。关键行：
    - 小节标题："假连接恢复未实现（`ConnectionManager` 已删）"
    - 主体：原"双层超时互相干涉"叙述整段替换为"`ConnectionManager` 在战役 G
      `fix-ble-connection-lifecycle` 采纳方案 B **整体删除**（145 行文件
      移除 + AppModule 零引用 + 全仓 grep 零命中）。原文档叙述的'双层超时
      互相干涉' 与事实相反 —— ConnectionManager 从未被 DI 注册 / 实例化 /
      调 `setCurrentDevice`，从未运行过。假连接检测与 GATT 释放收敛到
      `BleConnection.startDataTimeoutCheck`（见 11.3）+ `onConnectionStateChange`
      回调（A40 统一释放路径）。"
- [x] 5.4 **新增测试** `BleDeviceManagerTest.autoReconnectLastDevice_whenLastAddressNull_fallsBackToStartScan`
      （对应 Spec R6 Scenario 1，硬区分 v1/v2）：
    - 使用 fake `BleDeviceScanner` 可观察 `startScan()` 被调
    - 构造 `BleDeviceManager(context, fakeDataSource)` 让 `autoReconnectLastDevice`
      走 else 分支
    - 断言 `scanner.startScan()` 被调 ≥1 次
    - 硬区分：v1 下不被调
- [x] 5.5 **新增测试** `BleDeviceManagerTest.autoReconnectLastDevice_initTriggersFallbackScan`
      （对应 Spec R6 Scenario 2）：
    - 构造 `BleDeviceManager` 触发 `init { autoReconnectLastDevice() }`
    - 在 RECONNECT_TIMEOUT_MS 内轮询断言 `startScan()` 被调
    - 断言 `autoReconnectInProgress` finally 块恢复 false
- [x] 5.6 **门槛自检**：`./gradlew :core:bluetooth:testDebugUnitTest --tests
      "*BleDeviceManagerTest*"` 全绿。

> 注：Spec R6 Scenario 3（A46 review 文档）+ Scenario 4（A45 review 文档）
> 的验证不在 JVM 单测层做，移交**合流门槛级 grep 命令**（tasks 7.6）。
> 理由：文档修订本身是字符串替换，不涉及代码逻辑；让单测访问 `docs/` 目录
> 会把文档路径硬编码进代码层，违背分层原则。合流门槛的 `git diff` + `grep -c`
> 足够覆盖核销条件。评审方 2026-04-24 第二轮评审明确此调整。

## 6. attack-backlog 状态迁移

本节与合流门槛第 7 节对齐，放在所有代码 commit 后作为独立"文档 commit"
执行（不混入代码 commit）。

- [ ] 6.1 **A23 / A24 / A25 / A27 / A29 / A40 迁 🟢 `pending_review`**：
      `docs/superpowers/reviews/attack-backlog.md` 第一节对应条目整体搬到
      第三节，每条状态行追加：
      ```
      - **状态**：🟢 `pending_review`（@impl, commit <hash>, 2026-04-24）
        - 🔴 → 🟡：@impl 认领（2026-04-24）
        - 🟡 → 🟢：commit <hash>，本战役 G 合流门槛全绿（2026-04-24）
      ```

      **A23 特殊处理**（评审方第一轮 proposal review 批准的核销条件修订必须
      显式留痕）：迁档时 MUST **保留**评审方 2026-04-24 第一轮 proposal
      review 批准的"核销条件修订"记录块 —— 该块已在当前 attack-backlog.md
      A23 条目里追加（由本战役 proposal 起草时一并写入），迁档 / 搬节 /
      第五节存档合并时**不得被误删或精简**。

      **(i) 必保留的文本条目**（5 条，纯文本清单）：
      - 原 (2) "真机 15 秒自动重连" 打删除线 + 移交 `fix-ble-reconnection-layer`
        战役说明
      - 新 (2a) `_connectionState` 在 `DATA_TIMEOUT_MS + 1s` 内变 DISCONNECTED
      - 新 (2b) log 中 "数据接收超时：释放 GATT 资源" 条目
      - 新 (2c) `bluetoothManager.getConnectedDevices(...)` 审计无该设备地址
      - 依据段：指向 `openspec/changes/fix-ble-connection-lifecycle/proposal.md §
        Alternatives` "建议评审方同步修订 A23 核销条件" + 评审方 2026-04-24 批准

      **(ii) 迁档后文档顺序**（纪律，非文本）：A23 条目搬到第五节存档时，
      段落顺序 MUST 为 `来源 → 证据 → 攻击点 → 原核销条件（删除线）→
      核销条件修订 → 状态（含历次变更记录）` —— 纪律对齐战役 C
      engine-entry-hardening 存档精确性。

      **(iii) 验证动作**（grep 命令，归合流门槛 7.7）：迁档 / 存档后执行
      7.7 的 "A23 核销条件修订记录未丢失" 与 "A23 原 (2) 打删除线" 两组
      grep，输出命中一致才算迁档未破坏历史。

      **A42 特殊处理**：A42 本身核销条件（"依赖 A23 决策"）已由 R1 方案 B 整体删除
      `ConnectionManager.kt` 自动兑现 —— 文件删 = init 的空 collect 连同类定义
      一起消失。A42 状态行追加：
      ```
      - **状态**：🟢 `pending_review`（@impl, commit <hash>, 2026-04-24）
        - 🔴 → 🟢：依附 A23 方案 B 决策，随 `ConnectionManager.kt` 整体删除
          自动闭环（commit <hash> 同批）；无需独立代码改动，合流门槛第 7.2 项
          全绿即证
      ```
- [ ] 6.2 **A46 迁 🟢 `pending_review`** + **A45 迁 🟢 `pending_review`**（若
      评审方批准捆绑）：同上，附文档 commit hash。
- [ ] 6.3 **附录编号总览更新**：`docs/superpowers/reviews/attack-backlog.md`
      附录表格中 7 + 1（+1）条目的 ✅ 列改为当前 commit hash。

## 7. 合流门槛（Non-negotiable）

**全部打钩后才能走 `/opsx:archive` 归档流程。任一项失败，回到 pending，
改代码 / 改 spec / 改 proposal 后重跑。**

- [x] 7.1 **Spec 验证**：`$(npm config get prefix)/bin/openspec validate
      fix-ble-connection-lifecycle --strict` 退出码 0，无警告。
      注：CLI 已从 `openspec-chinese` 更名为 `openspec`（`@org-hex/openspec` 1.3.1
      起），本战役 G 实施期间发生变更；memory `reference_openspec_cli_location.md`
      需同步更新路径与命令名。
- [x] 7.2 **`core:bluetooth` 模块单测全绿**：`./gradlew :core:bluetooth:testDebugUnitTest`
      BUILD SUCCESSFUL。覆盖：
      - R1 契约测试（文件不存在、grep 零命中、BluetoothGatt 所有者校验）
      - R2 两条（超时释放 + race 消除）
      - R3 三条（disconnect 不提前 close + 回调触发 close + 超时走同路径）
      - R4 四条（parser 短包 / catch + BluetoothDataSource isConnected 语义
        + 成功 parse 清 errorMessage + 未知 UUID 不翻转 isConnected）
      - R5 三条（A→B 清旧 + collectJob 早 cancel + 相同地址幂等）
      - R6 四条（else fallback + init 触发 fallback + A46 文档 + A45 文档）
- [x] 7.3 **`core:bluetooth` 模块编译成功**：`./gradlew :core:bluetooth:assembleDebug`
      BUILD SUCCESSFUL（验证 ConnectionManager 删除无 unresolved reference）。
- [x] 7.4 **下游模块零回归**（编译 + 测试）：
      - `./gradlew :core:domain:test` BUILD SUCCESSFUL（战役 C filter 测试
        全绿）
      - `./gradlew :feature:test:testDebugUnitTest` BUILD SUCCESSFUL（engine
        + ViewModel + E2E 契约全绿，含战役 A 时钟守卫、engine-entry-hardening
        入口守卫）
      - `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL（app 层无
        ConnectionManager 引用）
- [x] 7.5 **端到端 E2E 契约全绿**：`./gradlew :feature:test:testDebugUnitTest
      --tests "*EndToEndLapTimingContractTest*"` 全绿（6 条契约）+
      `./gradlew :feature:test:testDebugUnitTest --tests
      "*TestSessionViewModelTrackLapTest*"` 全绿（含战役 A `processFilteredData_runningPhase_ignoresUnsyncedFrames`
      等关键回归）。
- [x] 7.6 **文档修订 diff 审核**（正向 + 反向双向校验，防止"整段误删" 陷阱）：
      - `git diff docs/superpowers/reviews/2026-04-22-gps-ingestion-and-filter-review.md`
        含 § 11.5（A45 如捆绑）+ § 11.6（A46）替换
      - **正向 grep（新叙述存在）**——若反向 grep 全过但实施方整段误删，
        以下断言仍 MUST 成立，防"假闭环"：
        - `grep -c "autoReconnectLastDevice 实质未实现，且 .else. 分支"
          docs/superpowers/reviews/2026-04-22-gps-ingestion-and-filter-review.md`
          输出 ≥ `1`（对应 Spec R6 Scenario 3：A46 § 11.6 新叙述存在）
        - `grep -c "假连接恢复未实现（ConnectionManager 已删）"
          docs/superpowers/reviews/2026-04-22-gps-ingestion-and-filter-review.md`
          输出 ≥ `1`（对应 Spec R6 Scenario 4：A45 § 11.5 新叙述存在，
          **仅当评审方批准 A45 捆绑时启用本断言**；若评审方最终选择 A45
          不捆绑，此条在合流门槛里显式标注 `N/A` 而非删除）
      - **反向 grep（旧叙述不存在）**：
        - `grep -c "双层超时互相干涉"
          docs/superpowers/reviews/2026-04-22-gps-ingestion-and-filter-review.md`
          输出 `0`
        - `grep -c "每次冷启动都走扫描路径"
          docs/superpowers/reviews/2026-04-22-gps-ingestion-and-filter-review.md`
          输出 `0`
        - `grep -c "没有.上次设备优先.能力"
          docs/superpowers/reviews/2026-04-22-gps-ingestion-and-filter-review.md`
          输出 `0`
      - **双向校验的闭环逻辑**：正向确认"替换后的新叙述真的写进去了"，反向
        确认"旧叙述真的被清掉了"。任一方向单独成立不足以证明文档修订正确
        完成 —— 正向不过 = 文档可能被整段误删；反向不过 = 旧叙述残留导致
        未来决策前提错误
- [ ] 7.7 **backlog 状态一致性**：
      - **从 🔴 `pending` 移除 8 条**（A23 / A24 / A25 / A27 / A29 / A40 /
        A42 / A46）+ **1 条可选**（A45，仅当评审方批准捆绑时移除）
      - **第三节 🟢 `pending_review` 新增 8 条**（A23 / A24 / A25 / A27 /
        A29 / A40 / A42 / A46）+ **1 条可选**（A45 如捆绑），每条附 commit hash
      - 附录 "攻击点编号总览" 表格状态列同步更新上述全部条目
      - **A23 核销条件修订记录未丢失**（对齐 6.1 A23 特殊处理）：
        `grep -n "核销条件修订.*评审方 2026-04-24" docs/superpowers/reviews/attack-backlog.md`
        输出 ≥ 1 行
      - **A23 原核销条件 (2) 真机 15 秒自愈段被打删除线**（拆两段独立 grep
        降低 markdown prettier 折行脆弱性 —— 单行 `.*` 模式会在未来格式化
        把整句折行时失败）：
        - `grep -c "~~真机集成测试" docs/superpowers/reviews/attack-backlog.md`
          输出 ≥ 1（删除线起点存在）
        - `grep -c "15 秒内自动重连~~" docs/superpowers/reviews/attack-backlog.md`
          输出 ≥ 1（删除线闭合存在）
        - 保留历史 + 显式标记移交，不是直接抹掉

## 8. Commit 策略

按"每独立功能点一 commit"拆分，4~5 个代码 commit + 1 个文档 commit：

1. **commit 1（R1）**：`fix(bluetooth): 战役 G R1 删除 ConnectionManager 死代码（A23 + A42）`
   - 删 `ConnectionManager.kt`
   - `AppModule.kt` grep 残留（若有）
   - 新增契约测试 `BleConnectionLifecycleContractTest.kt`
2. **commit 2（R2 + R3）**：`fix(bluetooth): 战役 G R2/R3 BleConnection 超时释放 + disconnect close 时机（A24 + A40）`
   - `BleConnection.kt` 的 `startDataTimeoutCheck` + `disconnect` + `onConnectionStateChange` 三处改动
   - 新增 `BleConnectionTest` 共 5 条
3. **commit 3（R4 + R5）**：`fix(bluetooth): 战役 G R4/R5 isConnected 语义收敛 + connect 清旧连接（A25 + A27）`
   - `RaceChronoParser.kt` 短包 / catch 分支加 errorMessage
   - `BluetoothDataSource.kt` 回调分支 + connect 清旧（顺序严格）
   - 新增 `RaceChronoParserTest` 2 条 + `BluetoothDataSourceTest` 6 条
4. **commit 4（R6 + 文档）**：`fix(bluetooth): 战役 G R6 autoReconnect else fallback 扫描 + review 文档修订（A29 + A46 + A45 捆绑）`
   - `BleDeviceManager.kt` else 分支
   - `docs/.../2026-04-22-gps-ingestion-and-filter-review.md` § 11.5 / § 11.6 修订
   - 新增 `BleDeviceManagerTest` 4 条
5. **commit 5（backlog）**：`docs(backlog): 战役 G 8 条攻击点迁 🟢 pending_review`
   - `attack-backlog.md` 状态机迁移 + 附录表格更新
   - 本 commit 作为独立"文档 commit"，不混入代码 commit

**Commit message 规范**：遵循 Conventional Commits；body 必须标明攻击点 ID
（A23 / A24 / ... / A46）便于 `git log --grep` 追溯。
