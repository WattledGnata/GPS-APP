# 实施任务（依赖顺序）

本 change 合并 A26 + A41，两个 Requirement 物理分离（R1 动 `parseGpsTimeData`，
R2 动 `parseGpsData` + 字段声明），互不依赖。按独立 commit 分两批落地便于评审方
按 commit diff 单独核销。

建议施工顺序：

1. **R1（A26）**：删 `parseGpsTimeData` 写 `isTestReady` 的分支 + 新增测试 —— 一
   个 commit
2. **R2（A41）**：删 5 个字段 + tracking 计算块 + class KDoc 修订 + 新增反射断言
   测试 —— 一个 commit
3. 两个 commit 互不依赖，任一回退不影响另一个

合流门槛集中在第 3 节。

---

## 1. R1（A26）`parseGpsTimeData` 不写 `isTestReady`（独立 commit）

### 1.1 删除时间包成功路径的 `isTestReady` 写入

- [ ] 1.1.1 **代码改动**：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt:109-115` 改写成功分支，删除 `isTestReady` 判断 + 写入：

    ```kotlin
    BEFORE:
    // A25 契约闭合：时间包成功路径显式清 errorMessage ...
    if (!currentData.isTestReady) {
        currentData.copy(isTestReady = true, errorMessage = null)
    } else {
        currentData.copy(errorMessage = null)
    }

    AFTER:
    // A26: 时间包 MUST NOT 写 isTestReady —— isTestReady 的唯一写入源是主包
    //      `parseGpsData` 的 `satellites >= 6 && hdop < 2.0` 判定。时间包只负责
    //      更新 `protocolTimeReference`（上方已完成）+ 清 errorMessage（A25）。
    // A25 契约闭合：时间包成功路径显式清 errorMessage，避免前帧失败残留让下游
    //               把"本帧 parse 成功"误解释成"最近一次 parse 失败"。
    currentData.copy(errorMessage = null)
    ```

- [ ] 1.1.2 **保留验证**：L102-105 `protocolTimeReference = ProtocolTimeReference(...)` 行必须保留；L116-120 catch 分支的 `errorMessage = "parse-error: ..."` 必须保留；L77-82 短包分支的 `errorMessage = "short-packet"` 必须保留。

### 1.2 新增 `RaceChronoParserTestReadyStateTest`

- [ ] 1.2.1 **新建文件**：`core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTestReadyStateTest.kt`（独立文件。**不复用** `RaceChronoParserProtocolTimeTest.kt`：该文件已存在并承载 A8 时间同步契约测试，scope 是 `protocolTimeReference` / `isTimeSynced`；本 change R1 scope 是 `isTestReady` 字段写入边界，契约不同，独立命名、独立文件。**不塞** `RaceChronoParserTest.kt`：后者已 40+ 测试 + `@IgnoreFormatCheck` legacy 豁免，新增契约测试不继承 legacy 豁免。class 名 `RaceChronoParserTestReadyStateTest`，与文件名一致）

- [ ] 1.2.2 **测试 1**：`parseGpsTimeData_whenInputIsTestReadyFalse_doesNotFlipToTrue`（Spec Scenario 1）

    ```kotlin
    @Test
    fun parseGpsTimeData_whenInputIsTestReadyFalse_doesNotFlipToTrue() {
        val parser = RaceChronoParser()
        val input = GpsData(isTestReady = false)  // 其他字段默认
        val timePacket = byteArrayOf(0x20.toByte(), 0x12.toByte(), 0x34.toByte())

        val result = parser.parseGpsTimeData(timePacket, input)

        // 硬区分 v1：v1 会返回 isTestReady=true，本断言证明 v2 已不在时间包写
        assertEquals(false, result.isTestReady)
        assertNull(result.errorMessage)
    }
    ```

- [ ] 1.2.3 **测试 2**：`parseGpsTimeData_whenInputIsTestReadyTrue_keepsTrue`（Spec Scenario 2）

- [ ] 1.2.4 **测试 3**：`parseGpsTimeData_andParseGpsData_coldStartSequence_noFlicker`（Spec Scenario 3）

    ```kotlin
    @Test
    fun parseGpsTimeData_andParseGpsData_coldStartSequence_noFlicker() {
        val parser = RaceChronoParser()
        var data = GpsData()  // 默认 isTestReady=false

        // 顺序：时间包 → 主包(sats=4) → 时间包 → 主包(sats=8)
        val timePacket = byteArrayOf(0x20.toByte(), 0x12.toByte(), 0x34.toByte())
        val mainPacketSats4 = buildMainPacket(satellites = 4, hdop = 1.5)
        val mainPacketSats8 = buildMainPacket(satellites = 8, hdop = 1.5)

        data = parser.parseGpsTimeData(timePacket, data)     // expect false
        assertEquals("time#1", false, data.isTestReady)
        data = parser.parseGpsData(mainPacketSats4, data)    // expect false (sats<6)
        assertEquals("main#1", false, data.isTestReady)
        data = parser.parseGpsTimeData(timePacket, data)     // expect false (v1 会翻 true)
        assertEquals("time#2", false, data.isTestReady)
        data = parser.parseGpsData(mainPacketSats8, data)    // expect true (sats>=6, hdop<2)
        assertEquals("main#2", true, data.isTestReady)
    }
    ```

    `buildMainPacket(satellites, hdop)` 为测试内 helper（参考
    `RaceChronoParserTest.kt` 的 `createValidGpsData20` 实现，但**不复用其
    helper**，避免与 A16b 既定 helper 耦合；本文件独立重写简化版仅覆盖本测试需要
    的 satellites/hdop 两个字段）

- [ ] 1.2.5 **测试 4**：`parseGpsTimeData_whenShortPacket_doesNotTouchIsTestReady`（Spec Scenario 4）

    ```kotlin
    @Test
    fun parseGpsTimeData_whenShortPacket_doesNotTouchIsTestReady() {
        val parser = RaceChronoParser()
        val input = GpsData(isTestReady = true)
        val shortPacket = byteArrayOf(0x20.toByte(), 0x12.toByte())  // 仅 2 字节

        val result = parser.parseGpsTimeData(shortPacket, input)

        assertEquals(true, result.isTestReady)
        assertEquals("short-packet", result.errorMessage)
    }
    ```

### 1.3 修订既有源码断言 `parseGpsTimeData_successPathExplicitlyClearsErrorMessage_sourceAssertion`

- [ ] 1.3.1 **代码改动**：`core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt:942-960` 修订源码断言语义，从"必须 ≥ 2 次 `errorMessage = null`"改为"成功路径单一 copy 分支显式包含 `errorMessage = null`，且函数体 NOT 包含 `isTestReady = true` / `if (!currentData.isTestReady)` 任一 v1 残留"：

    ```kotlin
    BEFORE (L942-960):
    @Test
    fun parseGpsTimeData_successPathExplicitlyClearsErrorMessage_sourceAssertion() {
        // parseGpsTimeData 成功路径 MUST 对两个分支都显式 errorMessage = null
        val source = java.io.File(
            "src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt"
        ).readText()
        val fnStart = source.indexOf("fun parseGpsTimeData(")
        assertTrue("parseGpsTimeData 必须定义", fnStart > 0)
        val fnEnd = source.indexOf("Error parsing GPS time data", fnStart)
        assertTrue("parseGpsTimeData catch 锚点必须在函数内", fnEnd > fnStart)
        val fnBody = source.substring(fnStart, fnEnd)
        // 断言：函数成功路径（try 体内）出现至少两次 errorMessage = null（对应 if/else 两分支）
        val occurrences = Regex("errorMessage = null").findAll(fnBody).count()
        assertTrue(
            "parseGpsTimeData 成功路径的 if/else 两个 copy 分支 MUST 都显式 errorMessage = null " +
                "（当前计数=$occurrences，应 ≥ 2）",
            occurrences >= 2,
        )
    }

    AFTER:
    @Test
    fun parseGpsTimeData_successPathExplicitlyClearsErrorMessage_sourceAssertion() {
        // A26 (refactor-parser-internal-state-cleanup R1)：parseGpsTimeData
        // 成功路径 MUST 合并为单一 copy 分支，仅显式 `errorMessage = null`；
        // MUST NOT 写 isTestReady（唯一写入源收敛为主包 satellites/hdop 判定）。
        // A25 契约：成功路径仍显式清 errorMessage，避免前帧失败残留级联。
        val source = java.io.File(
            "src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt"
        ).readText()
        val fnStart = source.indexOf("fun parseGpsTimeData(")
        assertTrue("parseGpsTimeData 必须定义", fnStart > 0)
        val fnEnd = source.indexOf("Error parsing GPS time data", fnStart)
        assertTrue("parseGpsTimeData catch 锚点必须在函数内", fnEnd > fnStart)
        val fnBody = source.substring(fnStart, fnEnd)

        // A25 保留：函数体（成功路径 + 短包分支 errorMessage 均由 copy 显式赋值）
        // MUST 至少出现 1 次 `errorMessage = null`，锁定成功路径清 error 行为。
        val clearOccurrences = Regex("errorMessage = null").findAll(fnBody).count()
        assertTrue(
            "A25 + A26：parseGpsTimeData 成功路径 MUST 显式 `errorMessage = null` " +
                "（当前计数=$clearOccurrences，应 ≥ 1）",
            clearOccurrences >= 1,
        )

        // A26 硬区分 v1：v1 源码在成功路径出现 `isTestReady = true` +
        // `if (!currentData.isTestReady)` 分支写入，v2 MUST 不包含任一残留。
        assertFalse(
            "A26：parseGpsTimeData MUST NOT 包含 `isTestReady = true` 赋值（v1 残留）",
            fnBody.contains("isTestReady = true"),
        )
        assertFalse(
            "A26：parseGpsTimeData MUST NOT 包含 `if (!currentData.isTestReady)` 分支（v1 残留）",
            fnBody.contains("if (!currentData.isTestReady)"),
        )
    }
    ```

- [ ] 1.3.2 **保留说明**：主包 `parseGpsData` L311 的 `isTestReady = satellites >= 6 && hdop < 2.0` **不是**字面量 `isTestReady = true`，不会误触上述两条 `assertFalse`。

### 1.4 R1 独立 commit

- [ ] 1.4.1 Commit message：
    ```
    refactor(bluetooth): 战役 H 一期 A26 parseGpsTimeData 不写 isTestReady

    Change: openspec/changes/refactor-parser-internal-state-cleanup (R1)
    - RaceChronoParser.kt:109-115 删除时间包成功路径的 isTestReady 写入
      + 合并 if/else 为单一 copy(errorMessage = null) 分支
    - isTestReady 的唯一写入源收敛为主包 satellites>=6 && hdop<2.0 判定
    - 新增 RaceChronoParserTestReadyStateTest 4 条契约断言
      （含冷启动序列硬区分 v1）
    - 同步修订 RaceChronoParserTest.parseGpsTimeData_successPathExplicitlyClearsErrorMessage_sourceAssertion
      ：从 "≥ 2 次 errorMessage=null" 改为 "≥ 1 次 + 不包含 isTestReady=true / v1 分支"
    - 不动 protocolTimeReference 写入 (A8) + A8 既有 RaceChronoParserProtocolTimeTest 零回归
    ```

---

## 2. R2（A41）删除 parser 内部死状态字段（独立 commit）

### 2.1 删除 5 个字段声明

- [ ] 2.1.1 **代码改动**：`RaceChronoParser.kt:36-41` 删除整 5 行：

    ```kotlin
    BEFORE:
    // Tracking state for calculations
    private var startTime: Long = 0
    private var totalDistance: Double = 0.0
    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null
    private var hasStartedTracking = false
    private var protocolTimeReference: ProtocolTimeReference? = null

    AFTER:
    private var protocolTimeReference: ProtocolTimeReference? = null
    ```

    保留 `protocolTimeReference` 字段（A8 单源时间同步），删除前 5 个 + 上方注释
    `// Tracking state for calculations`。

### 2.2 简化 `reset()` 方法

- [ ] 2.2.1 **代码改动**：`RaceChronoParser.kt:52-64` 删除 5 行死字段重置：

    ```kotlin
    BEFORE:
    fun reset() {
        hasStartedTracking = false
        startTime = 0
        totalDistance = 0.0
        lastLatitude = null
        lastLongitude = null
        gpsDataTimestamps.clear()
        gpsFrequency = 0.0
        protocolTimeReference = null
        // A8 / opsx code review C.4：`isTimeSynced` 不再作 parser 私有字段 ...
    }

    AFTER:
    fun reset() {
        gpsDataTimestamps.clear()
        gpsFrequency = 0.0
        protocolTimeReference = null
        // A8 / opsx code review C.4：`isTimeSynced` 不再作 parser 私有字段 ...
    }
    ```

    `lastFrequencyUpdateTime` 重置缺失（原 `reset()` 即未清理）与 R2 scope 无
    关，**不在此 change 顺手修**（符合 spec R2 正文显式声明与 "Scope Boundaries"
    规则，frequency 活状态完整性问题另行评估，候选归 A28 scope）。

### 2.3 删除 `parseGpsData` 内 Tracking Calculation 块

- [ ] 2.3.1 **代码改动**：`RaceChronoParser.kt:245-282` 删除整个 "Tracking Calculation (Non-Critical)" try/catch 块（从 `// Tracking Calculation (Non-Critical)` 注释行到对应 `} catch (e: Exception) { Log.e(TAG, "Error in tracking calculation", e) }` 闭合行）：

    ```kotlin
    BEFORE:
    // Tracking Calculation (Non-Critical)
    try {
        // Only calculate if we have a valid fix
        if (fixQuality > 0 && satellites >= 3) {
            if (!hasStartedTracking) {
                hasStartedTracking = true
                startTime = System.currentTimeMillis()
                lastLatitude = currentLatitude
                lastLongitude = currentLongitude
                totalDistance = 0.0
                ...
            } else {
                ...
                Location.distanceBetween(...)
                ...
                totalDistance += distanceStep / 1000.0
                ...
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error in tracking calculation", e)
    }

    AFTER:
    （整块删除，不留空行占位）
    ```

- [ ] 2.3.2 **保留验证**：上方 Frequency Calculation 块（L228-243）**不动**；下方"协议时间对齐判定"块（L284+）**不动**。

### 2.4 class KDoc 修订

- [ ] 2.4.1 **代码改动**：`RaceChronoParser.kt:15-22` 删除 "and tracking (distance/time)"：

    ```kotlin
    BEFORE:
    /**
     * RaceChrono GPS Protocol Parser
     * Handles parsing of raw byte arrays into GpsData objects.
     * Maintains state for frequency calculation and tracking (distance/time).
     *
     * Protocol: ESP32 20-byte GPS Main Data + 3-byte GPS Time Data
     * See: docs/RaceChrono_BLE_Protocol.md
     */

    AFTER:
    /**
     * RaceChrono GPS Protocol Parser
     * Handles parsing of raw byte arrays into GpsData objects.
     * Maintains state for frequency calculation (A41 已清除内部 tracking 死状态 2026-04-24).
     *
     * Protocol: ESP32 20-byte GPS Main Data + 3-byte GPS Time Data
     * See: docs/RaceChrono_BLE_Protocol.md
     */
    ```

### 2.5 新增 `RaceChronoParserInternalStateTest`

- [ ] 2.5.1 **新建文件**：`core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserInternalStateTest.kt`（独立文件理由同 R1：避免 legacy `@IgnoreFormatCheck` 污染新契约测试）

- [ ] 2.5.2 **测试 1**：`parserClass_doesNotDeclareRemovedTrackingFields`（Spec Scenario 1）

    ```kotlin
    @Test
    fun parserClass_doesNotDeclareRemovedTrackingFields() {
        val fieldNames = RaceChronoParser::class.java.declaredFields.map { it.name }.toSet()

        val forbidden = setOf(
            "totalDistance",
            "hasStartedTracking",
            "startTime",
            "lastLatitude",
            "lastLongitude"
        )
        val intersection = fieldNames intersect forbidden
        assertTrue(
            "A41: RaceChronoParser 不得维护已删的 tracking 死状态字段，但发现: $intersection",
            intersection.isEmpty()
        )

        // 活字段仍在（防止 R2 误删 frequency / 时间同步相关字段）
        assertTrue("frequency 活字段 gpsFrequency 必须保留", "gpsFrequency" in fieldNames)
        assertTrue("frequency 活字段 gpsDataTimestamps 必须保留", "gpsDataTimestamps" in fieldNames)
        assertTrue("时间同步活字段 protocolTimeReference 必须保留", "protocolTimeReference" in fieldNames)
    }
    ```

- [ ] 2.5.3 **测试 2**：`parseGpsData_100Frames_noTrackingSideEffect`（Spec Scenario 2）

    ```kotlin
    @Test
    fun parseGpsData_100Frames_noTrackingSideEffect() {
        val parser = RaceChronoParser()
        var data = GpsData()
        val packet = buildMainPacket(satellites = 8, hdop = 1.5, lat = 60.1725, lon = 24.9375)

        repeat(100) {
            data = parser.parseGpsData(packet, data)
        }

        assertEquals(8, data.satelliteCount)
        // 反射再次验证字段仍未被偷偷加回
        val fieldNames = RaceChronoParser::class.java.declaredFields.map { it.name }.toSet()
        assertFalse("totalDistance" in fieldNames)
        assertFalse("hasStartedTracking" in fieldNames)
    }
    ```

- [ ] 2.5.4 **测试 3**：`reset_stillClearsFrequencyAndTimeSync_butNoDeadState`（Spec Scenario 3）

    ```kotlin
    @Test
    fun reset_stillClearsFrequencyAndTimeSync_butNoDeadState() {
        val parser = RaceChronoParser()
        var data = GpsData()
        val packet = buildMainPacket(satellites = 8, hdop = 1.5)
        val timePacket = byteArrayOf(0x20.toByte(), 0x12.toByte(), 0x34.toByte())
        repeat(30) { data = parser.parseGpsData(packet, data) }
        data = parser.parseGpsTimeData(timePacket, data)

        parser.reset()

        val gpsFrequencyField = RaceChronoParser::class.java.getDeclaredField("gpsFrequency").apply { isAccessible = true }
        val timestampsField = RaceChronoParser::class.java.getDeclaredField("gpsDataTimestamps").apply { isAccessible = true }
        val referenceField = RaceChronoParser::class.java.getDeclaredField("protocolTimeReference").apply { isAccessible = true }

        assertEquals(0.0, gpsFrequencyField.getDouble(parser), 0.0)
        assertEquals(0, (timestampsField.get(parser) as List<*>).size)
        assertNull(referenceField.get(parser))
    }
    ```

### 2.6 R2 独立 commit

- [ ] 2.6.1 Commit message：
    ```
    refactor(bluetooth): 战役 H 一期 A41 parser 删除 totalDistance/hasStartedTracking 死状态

    Change: openspec/changes/refactor-parser-internal-state-cleanup (R2)
    - RaceChronoParser.kt:36-41 删除 5 个内部死字段
      (startTime/totalDistance/lastLatitude/lastLongitude/hasStartedTracking)
    - reset() 对应 5 行重置删除
    - parseGpsData 内 245-282 "Tracking Calculation (Non-Critical)" 整块删除
      (25Hz × Location.distanceBetween JNI 开销 + parser 依赖系统时钟的错误暗示)
    - class KDoc "and tracking (distance/time)" 删除
    - 新增 RaceChronoParserInternalStateTest 3 条反射断言 (硬区分 v1 字段存在)
    - 下游审计零外部消费者 (rg 证实) + 现有 RaceChronoParserTest 零回归
    ```

---

## 3. 合流门槛

### 3.1 本地验收清单（实施方自测必过）

- [ ] 3.1.1 `openspec validate refactor-parser-internal-state-cleanup --strict` PASS
- [ ] 3.1.2 `./gradlew :core:bluetooth:testDebugUnitTest --tests "*RaceChronoParserTest*"` BUILD SUCCESSFUL
- [ ] 3.1.3 `./gradlew :core:bluetooth:testDebugUnitTest --tests "*RaceChronoParserTestReadyStateTest*"` BUILD SUCCESSFUL（R1 新增）
- [ ] 3.1.4 `./gradlew :core:bluetooth:testDebugUnitTest --tests "*RaceChronoParserInternalStateTest*"` BUILD SUCCESSFUL（R2 新增）
- [ ] 3.1.5 A8 既有契约零回归：`./gradlew :core:bluetooth:testDebugUnitTest --tests "*RaceChronoParserProtocolTimeTest*"` BUILD SUCCESSFUL（本 change **不得** 修改或覆盖 A8 既有 `RaceChronoParserProtocolTimeTest.kt`；此条合并 3.1.2 的 `RaceChronoParserTest` 全量，但单独列出提醒）
- [ ] 3.1.6 下游端到端零回归：
    ```bash
    ./gradlew :feature:test:testDebugUnitTest \
      --tests "*EndToEndLapTimingContractTest*" \
      --tests "*LapTimingEngineTest*" \
      --tests "*TestSessionViewModelTrackLapTest*"
    ```
    BUILD SUCCESSFUL
- [ ] 3.1.7 `rg -n "totalDistance|hasStartedTracking|startTime|lastLatitude|lastLongitude" core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt` 零命中
- [ ] 3.1.8 `rg -n "isTestReady\s*=\s*true" core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt` 零命中（主包 L311 的 `isTestReady = satellites >= 6 && hdop < 2.0` 不是字面量赋值 true，不会命中）
- [ ] 3.1.9 两个新 test 文件（`RaceChronoParserTestReadyStateTest.kt` + `RaceChronoParserInternalStateTest.kt`）**不含** `// @IgnoreFormatCheck` 头（新建文件严守格式规则）

### 3.2 评审方最终核销（commit diff 级）

- [ ] 3.2.1 审 R1 commit diff：确认仅删 L111-115 的分支，不触碰 `protocolTimeReference` / `errorMessage` / catch 分支
- [ ] 3.2.2 审 R2 commit diff：确认仅删 5 字段 + tracking 块 + KDoc 一句，不触碰 frequency 块 / 时间同步块 / altitude 块（A16b 已核销不得回改）
- [ ] 3.2.3 审新增测试：确认 Spec Scenario 1-4（R1）+ Scenario 1-3（R2）硬区分 v1，无"重复断言 impl"的套娃
- [ ] 3.2.4 审 R1 commit 内对 `RaceChronoParserTest.parseGpsTimeData_successPathExplicitlyClearsErrorMessage_sourceAssertion` 的源码断言修订：确认新断言"≥ 1 次 + NOT 含 `isTestReady = true` / `if (!currentData.isTestReady)`"能正确反映 R1 实现目标，且不与 A25 契约冲突

### 3.3 状态更新

- [ ] 3.3.1 评审方核销通过后，在 `attack-backlog.md` 把 A26 / A41 从第一节 🔴 pending 迁入第四节 ✅ resolved + 状态总表对应行改 ✅
