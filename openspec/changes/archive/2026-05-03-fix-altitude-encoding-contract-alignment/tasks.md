# 实施任务（依赖顺序）

本 change 合并 A16b altitude 四方契约对齐，按 3 个 Requirement 组织。三方改动强耦合：
改 parser 必须同步改 test helper（否则现有 altitude 相关测试对 v2 parser 不通过）；
改 simulator 不依赖 parser 改动（编码是独立 pipeline），但语义上强相关。

建议施工顺序：

1. **R1 + R3**（parser + test helper + RP22 重构）同 commit —— parser 解码改动必须
   伴随 test helper 编码改动，否则现有测试 helper 生成的字节 v2 parser 解码得到不同
   数值，测试 batch fail
2. **R2**（simulator 编码）独立 commit —— simulator 发送端是独立模块，E2E 测试验证
3. **协议文档修订**（proposal R2 文档部分）并入 R1 commit

合流门槛集中在第 4 节。

---

## 1. R1 + R3 parser 解码 + test helper 编码 + 协议文档修订（合一 commit）

### 1.1 parser 两分支公式改对称 ino

- [ ] 1.1.1 **代码改动**：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt:193-199` 改两分支公式：
    ```kotlin
    BEFORE:
    val altitudeMeters = if ((altRaw and 0x8000) == 0) {
        (altRaw and 0x7FFF) / 100.0 - 500.0                // 错
    } else {
        ((altRaw and 0x7FFF) * 10.0) / 100.0 - 500.0       // 错
    }

    AFTER:
    val altitudeMeters = if ((altRaw and 0x8000) == 0) {
        // bit15=0 (低海拔)：ino 编码 raw = ((alt+500)*10) & 0x7FFF，逆运算 alt = raw/10 - 500
        // 精度 0.1m，范围 -500m ~ 2776.7m；[2776.7m, 6053.5m] 区间 ino 自身 & 0x7FFF 截断
        // 不可逆（A16b R1 Scenario 5 Non-goal 契约），parser 解得截断值不报错
        (altRaw and 0x7FFF) / 10.0 - 500.0
    } else {
        // bit15=1 (高海拔)：ino 编码 raw = (alt+500) & 0x7FFF | 0x8000（不乘 10），
        // 逆运算 alt = (raw & 0x7FFF) - 500。发送端 `alt >= 6053.5m` 触发 bit15=1；
        // 解码值精度 1m，最小可回读整数为 6053m（alt=6053.5m 量化回 6053m，alt=6054m 精确回 6054m）
        (altRaw and 0x7FFF).toDouble() - 500.0
    }
    ```

### 1.2 RaceChronoParserTest helper altitude 按 ino 编码

- [ ] 1.2.1 **代码改动**：`core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt` 的 `createValidGpsData20` helper（altitude 字节 12-13 构造处）改为：
    ```kotlin
    // 按 ino 真实编码 (A16b R3)
    val altEncoded = if (altitude < 6053.5) {
        (((altitude + 500.0) * 10.0).toInt()) and 0x7FFF
    } else {
        ((((altitude + 500.0).toInt()) and 0x7FFF)) or 0x8000
    }
    data[12] = ((altEncoded shr 8) and 0xFF).toByte()
    data[13] = (altEncoded and 0xFF).toByte()
    ```
- [ ] 1.2.2 **grep 核查其他 altitude 字节构造**：
    ```bash
    grep -n "altitude\|altRaw\|data\[12\]" core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt
    ```
    确认除 `createValidGpsData20` 外无其他独立字节构造路径（若有需一并改）。

### 1.3 RP22 重构 + 去 @Ignore + 新增 RP22b / RP22c

- [ ] 1.3.1 **RP22 修订**：
    - 去掉 `@Ignore` 注解
    - `data[12] = 0x52.toByte(); data[13] = 0x08.toByte()`（ino 对 1600m 的真实 bit15=0 编码）
    - 注释更新："1600m 走 bit15=0：raw = (1600+500)*10 = 21000 = 0x5208"
    - 断言修订（P3-1 文案纠偏）：`assertEquals("bit15=0 低海拔分支应为 1600m（ino 真实编码 0x52 0x08）", 1600.0, result.altitude, 0.1)`
- [ ] 1.3.2 **新增 RP22b** `parseAltitude_highAltitudeBit15One_6054m`（**注意不是 6053m**；ino 判定 `6053 < 6053.5` 仍走 bit15=0 截断区间，非 bit15=1）：
    - `data[12] = 0x99.toByte(); data[13] = 0x9A.toByte()`（ino 对 6054m 的 bit15=1 编码：raw = (6054+500) | 0x8000 = 6554 | 0x8000 = 0x999A）
    - 断言 `assertEquals(6054.0, result.altitude, 0.1)`（精度 1m 精确整数 round-trip，无舍入损失）
- [ ] 1.3.3 **新增 RP22c** `parseAltitude_highAltitudeBit15One_10000m`：
    - `data[12] = 0xA9.toByte(); data[13] = 0x04.toByte()`（ino 对 10000m 编码：raw = 10500 | 0x8000 = 0xA904）
    - 断言 `assertEquals(10000.0, result.altitude, 0.5)`
- [ ] 1.3.4 **新增截断区间测试** `parseAltitude_inoTruncationRange_doesNotThrow_nonGoalContract`（**必做**，锁定 R5 Non-goal 机器锚点）：
    - `alt=4000m` 对应 ino bit15=0 编码：`((4000+500)*10) & 0x7FFF = 45000 & 0x7FFF = 45000 - 32768 = 12232`，字节 `0x2F 0xC8`
    - `data[12] = 0x2F.toByte(); data[13] = 0xC8.toByte()`
    - 断言 1：`parser.parseGpsData(data, createTestData())` 不抛异常（`assertDoesNotThrow` 或按 JUnit 4 的 `@Test` 无 `expected`）
    - 断言 2：解码值约 `12232 / 10 - 500 = 723.2m`（`assertEquals(723.2, result.altitude, 0.1)`），**等于截断后的错值**
    - 断言 3：`assertNotEquals(4000.0, result.altitude)` —— 显式声明**不恢复**真实高度（Non-goal 契约不允许精确往返）
    - 测试注释明确："ino 自身 [2776.7m, 6053.5m] 区间 `& 0x7FFF` 截断不可逆，parser 单边无法恢复；本 change Non-goal 区间机器锚点"

### 1.4 协议文档修订（§3.4 文字公式 + 下方 Kotlin 解析示例代码块同步）

- [ ] 1.4.1 **文档改动 A**：`docs/RaceChrono_BLE_Protocol.md:94-99` altitude §3.4 章节改写：
    ```markdown
    BEFORE:
    - Bit 15 = 0（无溢出）: alt = raw / 100.0 - 500.0
      - raw 范围: 0-32767, 对应 -500.0m 到 277.67m
    - Bit 15 = 1（有溢出）: alt = ((raw & 0x7FFF) * 10) / 100.0 - 500.0
      - raw 范围: 32768-65535, 对应 277.68m 到 6052.7m

    AFTER:
    - Bit 15 = 0（低海拔）: alt = raw / 10.0 - 500.0
      - raw 范围: 0-32767, 对应 -500.0m 到 2776.7m（精度 0.1m）
      - **精度契约**：ESP32 ino 按 alt<6053.5m 判定走本分支，但 alt ∈ [2776.7m, 6053.5m] 区间
        ino 编码时 `raw = ((alt+500)*10) & 0x7FFF` 会被 & 0x7FFF 截断丢失高位，parser 单边
        无法恢复原 alt（已知 ino 自身 bug，不在 A16b 本 change 修复 scope；改 ino 固件后统一对齐）
    - Bit 15 = 1（高海拔）: alt = (raw & 0x7FFF) - 500.0
      - raw 范围: 低 15 位 0-32767；**发送端** `alt >= 6053.5m` 触发 bit15=1；**解码值**精度 1m，最小可回读整数为 6053m（量化回读），典型高海拔场景至 33267m
      - ino 编码：raw = ((int)(alt + 500) & 0x7FFF) | 0x8000（不乘 10）
    ```
- [ ] 1.4.2 **文档改动 B**（P2-1）：`docs/RaceChrono_BLE_Protocol.md:143-148` 同文件下方的 `GPS 主数据解析` Kotlin 示例代码块 altitude 部分同步修订：
    ```kotlin
    BEFORE:
    val altitude = if ((altRaw and 0x8000) == 0) {
        (altRaw and 0x7FFF) / 100.0 - 500.0
    } else {
        ((altRaw and 0x7FFF) * 10) / 100.0 - 500.0
    }

    AFTER:
    val altitude = if ((altRaw and 0x8000) == 0) {
        (altRaw and 0x7FFF) / 10.0 - 500.0
    } else {
        (altRaw and 0x7FFF).toDouble() - 500.0
    }
    ```
    注意：同文件的 speed 字段公式（§3.4 速度段 + 示例代码块 speed 行）**不在本 change
    scope**，保持原样；grep 门槛过滤时需避免误伤 speed 公式。

### 1.5 回归 + commit

- [ ] 1.5.1 `./gradlew :core:bluetooth:testDebugUnitTest --tests "*RaceChronoParserTest*"` 全绿（含 RP22 / RP22b / RP22c）
- [ ] 1.5.2 grep A16b 相关注释点验证 parser R5 截断契约注释已落（`core/bluetooth/.../RaceChronoParser.kt` 含 `2776.7` / `6053.5` / `Non-goal` 相关 KDoc 关键字）
- [ ] 1.5.3 **commit 1**：`fix(bluetooth): 战役 D 尾巴 A16b altitude 编码契约对齐（R1/R3）parser 解码对称 ino + RP22 重构 + 协议文档修订`

## 2. R2 simulator 发送端编码对齐 ino

### 2.1 GpsDataGenerator altitude 编码改

- [ ] 2.1.1 **代码改动**：`simulator/src/main/java/com/blazepush/simulator/data/GpsDataGenerator.kt:85-102` 改两处：
    ```kotlin
    BEFORE (v1):
    val altMeters = altitude.toDouble()
    val altRaw = ((altMeters + 500.0) * 10.0).toInt()
    val altEncoded = if (altRaw <= 32767) {
        altRaw and 0x7FFF  // bit15 = 0
    } else {
        (altRaw and 0x7FFF) or 0x8000  // bit15 = 1（v1 错：仍保留 *10）
    }

    AFTER (v2，与 ino 完全对齐):
    // A16b R2：按 alt 阈值 6053.5m 判定（与 ino 一致，不按 raw 溢出判定）
    val altMeters = altitude.toDouble()
    val altEncoded = if (altMeters < 6053.5) {
        // bit15=0：ino 公式 raw = ((alt+500)*10) & 0x7FFF
        (((altMeters + 500.0) * 10.0).toInt()) and 0x7FFF
    } else {
        // bit15=1：ino 公式 raw = (alt+500) & 0x7FFF | 0x8000（不乘 10）
        ((((altMeters + 500.0).toInt()) and 0x7FFF)) or 0x8000
    }
    ```
- [ ] 2.1.2 **清理注释**：85-93 行的 v1 错误推导注释整段删除，替换为 R2 对齐 ino 的新注释。

### 2.2 E2E 截断区间外往返一致 + 截断区间字节对齐验证

- [ ] 2.2.1 `./gradlew :feature:test:testDebugUnitTest --tests "*EndToEndLapTimingContractTest*"` 全绿
    （E2E 管道 simulator → parser → engine 链路：**截断区间外** altitude 精确往返；
    截断区间 `[2776.7m, 6053.5m]` 只验证 simulator 字节与 ino 一致，不承诺解码值回到原 alt）
- [ ] 2.2.2 **新增 simulator 字节级单测**（**必做**，P2-2 修订；仓库已有
    `simulator/src/test/java/com/blazepush/simulator/data/GpsDataGeneratorTest.kt` 测试基础设施）
    `GpsDataGeneratorTest.generatesBytes_altitudeWithInoCompatibleEncoding`：
    - 至少断言以下 3 组 `alt → byte[12], byte[13]` 映射：
      - **bit15=0 典型**：`alt=100m` → `data[12]=0x17, data[13]=0x70`（raw=6000, bit15=0）
      - **bit15=1 最小整数边界**：`alt=10000m` → `data[12]=0xA9, data[13]=0x04`（raw=10500|0x8000=0xA904）
      - **Non-goal 截断区间锁定**：`alt=4000m` → `data[12]=0x2F, data[13]=0xC8`（raw=(4000+500)*10 & 0x7FFF = 12232 = 0x2FC8；ino 自身截断，simulator 行为与 ino 一致）
    - 测试 MUST 硬区分 v1 simulator：v1 对 `alt=10000m` 会生成 `0x9A 0x28`（`((10000+500)*10)&0x7FFF=105000&0x7FFF=39464|0x8000=0x9A28`），本 scenario 断言 `0xA9 0x04` 能 fail v1

### 2.3 commit

- [ ] 2.3.1 **commit 2**：`fix(simulator): 战役 D 尾巴 A16b altitude 编码对齐 ino（R2）GpsDataGenerator 判定条件 + bit15=1 公式修复`

## 3. Non-goal 显式声明（文档级，无代码改动）

- [ ] 3.1 确认 ESP32 `docs/RaceChrono_ESP32_M9N.ino` **不改**。ino 在 [2776.7m, 6053.5m] 区间的 `& 0x7FFF` 截断 bug 作为已知精度契约，由协议文档 § 3.4 的精度契约注释承载（任务 1.4.1 已落）。
- [ ] 3.2 确认生产历史 lap 持久化数据 altitude 字段**不回溯**（audit § 10.2 下游审计：altitude 仅被 DataSmoothing / TestSessionViewModel 透传，不参与判圈/加速判定；历史数据 altitude 错值一次性标注"v1 数据不可信"即可，无数据迁移动作）。

## 4. 合流门槛 + 自洽审计

- [ ] 4.1 `openspec validate fix-altitude-encoding-contract-alignment --strict` 通过。
- [ ] 4.2 `./gradlew :core:bluetooth:testDebugUnitTest --tests "*RaceChronoParserTest*"` 全绿（RP22 去 @Ignore 后断言 pass + RP22b/RP22c 新测试 pass）。
- [ ] 4.3 `./gradlew :core:bluetooth:testDebugUnitTest` 全绿（跨模块零回归）。
- [ ] 4.4 `./gradlew :feature:test:testDebugUnitTest --tests "*EndToEndLapTimingContractTest*"` 全绿（E2E simulator → parser 往返）。
- [ ] 4.5 `./gradlew :feature:test:testDebugUnitTest --tests "*LapTimingEngineTest*" --tests "*TestSessionViewModelTrackLapTest*"` 全绿（altitude 非判圈字段，零下游回归）。
- [ ] 4.6 `./gradlew :core:domain:test` 全绿（domain 层无 altitude 编解码依赖）。
- [ ] 4.7 **RP16/RP19/RP22 @Ignore 全仓 grep 零残留**（A16a + A16b 合计闭环 3 条 @Ignore）：
    ```bash
    grep -n "@Ignore" core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt
    ```
    期望：无 RP16 / RP19 / RP22 命中（A16a commit `f097478` 已闭 RP16/RP19；本 change 闭 RP22）。
- [ ] 4.8 **simulator 字节级测试**（P2-2 新增门槛）：
    ```bash
    ./gradlew :simulator:testDebugUnitTest --tests "*GpsDataGeneratorTest*"
    ```
    期望全绿，含 `generatesBytes_altitudeWithInoCompatibleEncoding` 3 组断言（100m / 10000m / 4000m 截断）。
- [ ] 4.9 **协议文档 altitude 旧公式零残留 grep**（P2-1 新增门槛）：
    ```bash
    # 期望：协议文档 altitude 旧公式零残留（§3.4 文字 + 下方 Kotlin 解析示例）
    # 注意：grep pattern 只匹配 altitude 相关的旧公式，避免误伤 speed（speed 公式保留）
    grep -nE "altRaw\s+and\s+0x7FFF.*/\s*100\.0\s*-\s*500|\(altRaw.*\)\s*\*\s*10\s*\)\s*/\s*100" docs/RaceChrono_BLE_Protocol.md
    ```
    期望空输出。若命中需核对是否确实是 altitude 旧公式（非 speed）。
- [ ] 4.10 **altitude 编解码四方一致性审计**：
    ```bash
    # 期望：四方（parser 解码 / ino 编码 / test helper 编码 / simulator 编码）
    # 对 alt=100m 都映射到字节 0x17 0x70
    # 对 alt=10000m 都映射到字节 0xA9 0x04
    # 对 alt=6054m 都映射到字节 0x99 0x9A（bit15=1 最小整数边界）
    # 对 alt=4000m 都映射到字节 0x2F 0xC8（Non-goal 区间，四方行为一致"错"但对齐）
    ```
    人工对照 audit § 3.2 / § 6 表格（audit 中的 6053.5m / 0x8CA3 需按本轮 P1-1 修订结果更正为 6054m / 0x999A）验证。
- [ ] 4.11 **回执更新** `docs/superpowers/reviews/attack-backlog.md`：A16b 条目状态从 🔴 `pending` 迁到 🟢 `pending_review`，附 commit 链（R1/R3 commit 1 + R2 commit 2）。

## 5. Commit 策略

按 proposal § Impact 模块边界拆 **2 个 commit**：

1. **commit 1 — R1 + R3 + 协议文档**（parser 解码端 + 测试重构 + 协议文档）
   - `core/bluetooth/.../RaceChronoParser.kt` 两分支公式
   - `core/bluetooth/.../RaceChronoParserTest.kt` helper 编码 + RP22 重构 + RP22b/c
   - `docs/RaceChrono_BLE_Protocol.md` § 3.4 改写
   - 建议消息：`fix(bluetooth): 战役 D 尾巴 A16b altitude 编码契约对齐（R1/R3）parser 解码对称 ino + RP22 重构 + 协议文档修订`

2. **commit 2 — R2 simulator**（发送端模拟器）
   - `simulator/.../GpsDataGenerator.kt` 判定条件 + bit15=1 公式
   - 建议消息：`fix(simulator): 战役 D 尾巴 A16b altitude 编码对齐 ino（R2）GpsDataGenerator 判定条件 + bit15=1 公式修复`

若评审方倾向单 commit 合一（scope 小 + 四方强耦合），可合并；默认按上述 2 拆。

backlog 迁档（任务 4.9）在评审方核销后做，不在本 change 代码 commit 内。
