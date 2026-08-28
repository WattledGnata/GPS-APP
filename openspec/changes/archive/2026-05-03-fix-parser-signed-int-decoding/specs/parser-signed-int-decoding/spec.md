# Spec Delta: parser-signed-int-decoding

> Capability: **RaceChronoParser 的 lat/lon 字段按协议 signed int32 契约正确解码**。
> 涵盖 `parseGpsData` 对纬度（字节 4-7）+ 经度（字节 8-11）两个字段的**签名位保留**
> 解码路径，以及 RP16 / RP19 测试封条解除。
>
> 核心原则：
>
> 1. **signed int32 契约源**：RaceChrono 官方 BLE DIY API + ESP32 ino 固件
>    `docs/RaceChrono_ESP32_M9N.ino:37-38, 291-292, 333-340`（`int32_t latitude /
>    longitude`，二进制补码 big-endian 打包）+ 协议文档
>    `docs/RaceChrono_BLE_Protocol.md:32-33, 129-141`（明文 "int32 (big endian)"）
> 2. **parser 解码对称性**：Kotlin `Int` = Java `int` = C `int32_t`，均为 signed
>    32-bit 二进制补码。`Int / Double` 走 IEEE 754 双精度扩展，**保留符号位**。
>    任何 `.toLong() and 0xFFFFFFFFL` 的 mask 都会把 signed 错解为 uint32，违反
>    契约
>
> 本 spec 只覆盖 lat/lon 两字段。altitude bit15=0 / bit15=1 分支的公式错误、
> `docs/RaceChrono_BLE_Protocol.md § 3.4` 文档修订、RP22 测试封条解除、ESP32
> ino [2776.8m, 6053.4m] 截断 bug —— 全部由独立 change
> `fix-altitude-encoding-contract-alignment` 处理，不在本 spec 范围。

## ADDED Requirements

### Requirement: parser 纬度字段 MUST 按 signed int32 二进制补码解码

`RaceChronoParser.parseGpsData` 在读取字节 4-7 构造 `latInt: Int` 后，MUST 直接
执行 `latInt / 10_000_000.0` 类型的 Int → Double 除法，MUST NOT 先
`.toLong() and 0xFFFFFFFFL` 等价 mask，MUST NOT 使用 `UInt` / 其他 unsigned
类型。

正确语义：

- `latInt: Int` 从 4 字节 big-endian 组合后，是 Kotlin signed 32-bit 整型，最
  高 bit 直接表示符号（二进制补码）
- `Int / Double` 表达式由 Kotlin 编译器 / JVM 按 IEEE 754 把 Int 扩展为 Double
  （保留符号位），再做 Double 除法
- 结果 Double 的符号与 `latInt` 符号一致，数值 = `latInt 的 signed 值 /
  10_000_000`

关键属性：

- **与协议对齐**：协议文档（int32）+ ino 编码（`(int32_t)(latitude * 10000000.0)`
  二进制补码 big-endian 打包）都是 signed。parser 解码与它们对称
- **边界覆盖**：-90° ≤ lat ≤ +90° 的全范围（含负纬度）均正确解码
- **精度保持**：10,000,000 × 90 = 9e8，远小于 Int.MAX (2.1e9)，signed int32 能
  精确表达全范围纬度，不会溢出

#### Scenario: 南纬 -33.8688° MUST 解码为负纬度（硬区分 v1/v2）

- **GIVEN** ino 编码的 4 字节纬度 big-endian：`0xEB 0xD0 0x08 0x00`
  （= `(int32_t)(-33.8688 × 10_000_000) = -338_688_000` 二进制补码）
- **WHEN** `parser.parseGpsData(rawData, currentData)` 被调用
- **THEN** 返回 `gpsData.latitude == -33.8688`（误差 ≤ 0.0001）
- **AND 硬区分 v1**：v1（当前 `.toLong() and 0xFFFFFFFFL`）输出 `+395.6279296°`
  （`3_956_279_296L / 10_000_000.0`）→ assertion FAIL
- **AND 硬区分 v2**：v2（`latInt / 10_000_000.0`）输出 `-33.8688°` → assertion PASS

#### Scenario: 赤道 lat=0° MUST 解码为 0（回归保护）

- **GIVEN** ino 编码的 4 字节纬度：`0x00 0x00 0x00 0x00`
- **WHEN** `parser.parseGpsData(rawData, currentData)` 被调用
- **THEN** 返回 `gpsData.latitude == 0.0`
- **AND** v1 / v2 输出一致（0 的 Int / Long 无符号差异）
- **作用**：锁定本 change 不破坏已有正值 / 零路径，作为正向回归保护

#### Scenario: 正北纬 +60.1725897° MUST 解码不变（回归保护）

- **GIVEN** ino 编码纬度 `(int32_t)(60.1725897 × 10_000_000) = 601_725_897` =
  `0x23_DC_E2_09`，字节 `0x23 0xDC 0xE2 0x09`
- **WHEN** `parser.parseGpsData(rawData, currentData)` 被调用
- **THEN** 返回 `gpsData.latitude == 60.1725897`（误差 ≤ 0.000001）
- **AND 硬区分**：`601_725_897` 最高 bit = 0，v1 的 `.toLong() and 0xFFFFFFFFL`
  与 v2 的直接除法结果相同。本 Scenario 是**正向回归保护**，确保正北纬路径
  不因本 change 回归

---

### Requirement: parser 经度字段 MUST 按 signed int32 二进制补码解码

`RaceChronoParser.parseGpsData` 在读取字节 8-11 构造 `lonInt: Int` 后，MUST 直
接执行 `lonInt / 10_000_000.0`，MUST NOT 先 `.toLong() and 0xFFFFFFFFL` 等价
mask。语义与纬度 Requirement 1 完全对称（见上一 Requirement 的关键属性 + 对齐
契约）。

关键属性：

- **范围**：-180° ≤ lon ≤ +180°（协议内）
- **与纬度同源修复**：parser line 178 / line 185 两处 mask 是同源 bug，修复
  逻辑对称

#### Scenario: 西经 -122.4194° MUST 解码为负经度（硬区分 v1/v2）

- **GIVEN** ino 编码的 4 字节经度 big-endian：`0xB7 0x08 0x48 0x30`
  （= `(int32_t)(-122.4194 × 10_000_000) = -1_224_194_000` 二进制补码）
- **WHEN** `parser.parseGpsData(rawData, currentData)` 被调用
- **THEN** 返回 `gpsData.longitude == -122.4194`（误差 ≤ 0.0001）
- **AND 硬区分 v1**：v1 输出 `+307.0773296°`（`3_070_773_296L / 10_000_000.0`）
  → FAIL
- **AND 硬区分 v2**：v2 输出 `-122.4194°` → PASS

#### Scenario: 本初子午线 lon=0° MUST 解码为 0（回归保护）

- **GIVEN** ino 编码经度 `0x00 0x00 0x00 0x00`
- **WHEN** `parser.parseGpsData(rawData, currentData)` 被调用
- **THEN** 返回 `gpsData.longitude == 0.0`

#### Scenario: 正东经 +24.9376543° MUST 解码不变（回归保护）

- **GIVEN** ino 编码经度 `(int32_t)(24.9376543 × 10_000_000) = 249_376_543`
- **WHEN** `parser.parseGpsData(rawData, currentData)` 被调用
- **THEN** 返回 `gpsData.longitude == 24.9376543`（误差 ≤ 0.000001）
- **作用**：正向回归保护（正东经路径）

---

### Requirement: 南半球 + 西半球同时为负的复合场景 MUST 两轴都解码正确

当纬度和经度**同时为负**（南美 / 南非西部 / 大洋洲西南等），parser MUST 对两
个字段独立解码成负值，不得出现"某一轴错 + 某一轴对"的组合泄漏。

此 Requirement 是 R1 + R2 的组合边界，防止实施方只修一个字段的退化。

#### Scenario: 布宜诺斯艾利斯 (-34.6037°, -58.3816°) 两轴同时为负

- **GIVEN** ino 编码：lat 字节 `(int32_t)(-34.6037 × 10_000_000) = -346_037_000` 补码 big-endian；lon 字节 `(int32_t)(-58.3816 × 10_000_000) = -583_816_000` 补码 big-endian
- **WHEN** `parser.parseGpsData(rawData, currentData)` 被调用
- **THEN** 返回 `gpsData.latitude == -34.6037`（误差 ≤ 0.0001）
- **AND** 返回 `gpsData.longitude == -58.3816`（误差 ≤ 0.0001）
- **AND 硬区分 v1**：v1 在两轴上都输出 +[180°, 400°] 大数 → 双重 FAIL

#### Scenario: 极地 + 反子午线边界（接近 -90°, -180°）MUST 不精度退化

- **GIVEN** 极端接近理论边界的坐标：lat = -89.9999°, lon = -179.9999°
- **WHEN** `parser.parseGpsData(rawData, currentData)` 被调用
- **THEN** 返回 lat 在 [-89.99995, -89.99985] 内、lon 在 [-179.99995, -179.99985] 内
- **AND** 不得出现符号丢失、数值溢出、精度截断到整数等退化
- **作用**：证明 signed int32 解码在极端负值边界依然工作（补码表示能覆盖到
  ±214.7483648，> ±180 范围的两倍余量）

---

### Requirement: RP16 / RP19 测试解封

MUST 去除 `core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt`
中 `RP16_parseLatitude_negative` 与 `RP19_parseLongitude_negative` 两个测试
的 `@Ignore` 注解，恢复为活跃测试。

测试断言本身 MUST 不改动（原断言 `assertEquals("南纬应为负值", -33.8688, result.latitude, 0.0001)` 和 `assertEquals("西经应为负值", -122.4194, result.longitude, 0.0001)` 已正确）。

关键属性：

- **回归保护**：parser 修复后测试自然 pass，但 `@Ignore` 不删 = 测试跳过 = 未
  来可能再次退化到 unsigned 而无人发现
- **RP22 不在本范围**：RP22（altitude overflow @Ignore）属于 altitude 独立
  change，本 change MUST NOT 触碰

#### Scenario: RaceChronoParserTest 运行时 RP16 / RP19 MUST 参与执行

- **GIVEN** parser lat/lon 按 R1 + R2 修复完成
- **WHEN** `./gradlew :core:bluetooth:testDebugUnitTest --tests
  "*RaceChronoParserTest*"` 执行
- **THEN** 测试报告中 RP16_parseLatitude_negative **PASS**（不再显示 SKIPPED）
- **AND** RP19_parseLongitude_negative **PASS**（不再显示 SKIPPED）
- **AND** RP22_parseAltitude_overflow **仍显示 SKIPPED**（不在本 change 范围，
  留给 altitude change 解除）
- **AND** 其他已有测试（RP01~RP15 / RP17 / RP18 / RP20 / RP21 / RP23~RP40）
  全部 PASS

#### Scenario: 源码中 RP16 / RP19 的 `@Ignore` 注解 MUST 被删除（不只是"@Ignore 空括号"）

- **GIVEN** 修复后的 `RaceChronoParserTest.kt`
- **WHEN** 执行 `grep -c "^[[:space:]]*@Ignore\b" core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt`
  （pattern 仅匹配行首缩进后的 JUnit `@Ignore` 注解，排除文件头的
  `// @IgnoreFormatCheck` 格式 hook 豁免标记）
- **THEN** 输出 `1`（仅剩 RP22 一条 JUnit `@Ignore`，对应的 altitude overflow
  由独立 change 处理）
- **AND** 对应 `RP16_parseLatitude_negative` / `RP19_parseLongitude_negative`
  函数声明前无 `@Ignore` 注解行
- **反例**：裸 `grep -c "@Ignore"` 会把 `@IgnoreFormatCheck` 一起计入（当前
  输出 4），不能作为本 Scenario 的断言命令 —— codex 2026-04-24 P2-1 明确
