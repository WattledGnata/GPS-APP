# fix-parser-signed-int-decoding

战役 D 尾巴 A16 子项：RaceChronoParser 的经度 / 纬度字段**违反协议 int32 签名
约定**（当前实现 `toLong() and 0xFFFFFFFFL` 把 signed int32 抹成 uint32），导致
所有南纬 / 西经坐标被解成错误正值。本 change 仅修 lat/lon signed decoding + 去
RP16 / RP19 `@Ignore` 测试封条，RP22 / altitude 的**四方不一致**问题拆到独立
高优先级 change `fix-altitude-encoding-contract-alignment`（见 § Non-goals）。

核心决策摘要：

- **Scope 严格收敛**（评审方 2026-04-24 codex 复核 § Q2 批准方案 2）：本 change
  只处理 A16 核销条件 (1)(2) 的 **lat/lon 部分**；(3) "海拔 overflow 边界测试"
  显式移交 altitude 独立 change。RP22 `@Ignore` 本 change 不处理
- **真相源**（评审方 codex 复核 § Q1 拍板）：RaceChrono 官方 BLE DIY API +
  ESP32 ino 实际发送端实现。官方 README 明确 lat/lon signed two's complement，
  parser 当前 unsigned 解码违反此契约
- **不改协议 / ino / 数据模型**：本 change 纯 parser 解码修复，删两行 mask，无
  新增代码，无字段级协议变更

## Why

### A16 来源 + 复核过程

`docs/superpowers/reviews/attack-backlog.md` § A16（战役 D 尾巴）指出 parser
测试 RP16 / RP19 / RP22 三条 `@Ignore` 封条揭示了 parser 实际 bug。D 战役
commit `f869f27` 的 @Ignore 说明 "测试断言正确，parser 实现有 bug"，但当时未
修。本 change 起草阶段（2026-04-24）经 codex 独立复核（见
`docs/superpowers/reviews/2026-04-24-a16-altitude-encoding-tri-party-audit.md`）
确认：

- **lat/lon signed int32**（本 change 处理）：纯 parser 解码 bug，协议 + ino 都
  正确，parser 违反 signed 约定
- **altitude（RP22）**：协议文档 + parser 双方错公式自洽、ESP32 ino 是对的，
  三方不一致 + ino 自身在 [2776.8m, 6053.4m] 区间有截断 bug —— **四方对齐
  问题**，另起独立 change

### lat/lon 签名错解的机理（§ 2 of audit 文档 + codex 手算验证）

**协议文档**（`docs/RaceChrono_BLE_Protocol.md:32-33, 129-141`）与 **ESP32 ino**
（`docs/RaceChrono_ESP32_M9N.ino:37-38, 291-292, 333-340`）都明确：

- 纬度 / 经度字段是 **signed int32 big-endian**，按度 × 10,000,000 编码
- ino 编码：`gpsData.latitude = (int32_t)(parsedGpsData.latitude * 10000000.0)`
- 打包 4 字节：`(gpsData.latitude >> 24) & 0xFF` 等（C 的 signed int32 二进制
  补码 big-endian 打包）

**parser 解码**（`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt:178, 185`）：

```kotlin
// line 178（纬度）
val currentLatitude = (latInt.toLong() and 0xFFFFFFFFL) / 10000000.0

// line 185（经度）
val currentLongitude = (lonInt.toLong() and 0xFFFFFFFFL) / 10000000.0
```

`.toLong() and 0xFFFFFFFFL` 把 Kotlin Int（与 C int32 等价、signed）的最高位
当无符号处理，**负值被错解成 +2^31 以上的大数**。

### 验证（codex 手算确认）

`lat = -33.8688°`：

- ino 编码：`(int32_t)(-33.8688 × 10_000_000) = -338_688_000` 的二进制补码
  = `0xEBD00800`（big-endian 4 字节 `EB D0 08 00`）
- parser 当前解码：
  - `latInt = 0xEBD00800`（Kotlin Int 直接表示为 `-338_688_000`）
  - `latInt.toLong() and 0xFFFFFFFFL = 3_956_279_296L`（高 32 位被 mask 清零）
  - `3_956_279_296L / 10_000_000.0 = 395.6279296`
  - **输出 +395.6279296°** ❌（期望 -33.8688°）
- parser 正解（修复后）：
  - `latInt / 10_000_000.0 = -338_688_000 / 10_000_000.0 = -33.8688`
  - **输出 -33.8688°** ✓

`lon = -122.4194°`：

- ino 编码：`(int32_t)(-122.4194 × 10_000_000) = -1_224_194_000` → 补码
  `0xB7084830`
- parser 当前解码：`0xB7084830 and 0xFFFFFFFFL = 3_070_773_296L` →
  `3_070_773_296 / 10_000_000.0 = 307.0773296` ❌（期望 -122.4194°）
- parser 正解：`-1_224_194_000 / 10_000_000.0 = -122.4194` ✓

### 生产影响范围

受影响路径：**全部南半球（南纬） + 全部西半球（西经）坐标**。示例：

| 实际坐标 | 当前 parser 解码 |
|---|---|
| 悉尼（-33.87°, 151.21°）| +395.63°, 151.21°（纬度错） |
| 旧金山（37.77°, -122.42°）| 37.77°, +307.08°（经度错） |
| 布宜诺斯艾利斯（-34.60°, -58.38°）| +395.39°, +301.61°（**两轴都错**）|

本项目当前定位在**成都 TFIC（30.xx°N, 104.xx°E）**，全部在北半球东半球，没有
立即 production crash。但：

- 海外用户 / 出海测试 / 南半球赛道（澳洲、南非、阿根廷等）一切停摆
- 西半球赛道（美洲、英国以西）一切停摆
- 未来业务扩展不可能承受

**RP16 / RP19 测试**（`RaceChronoParserTest.kt:278-335`）早已写好断言（南纬
-33.8688° 期望 -33.8688°、西经 -122.4194° 期望 -122.4194°），D 战役用
`@Ignore` 封条留给独立战役修。本 change **就是** D 战役留的那个 "独立战役"。

### 为什么不顺路把 altitude 一起修

RP22 / altitude 涉及四方对齐（官方协议 BLE DIY API / `docs/RaceChrono_BLE_Protocol.md`
/ ino / parser / test helper），以及 ino 自身在 [2776.8m, 6053.4m] 的截断
bug —— **数量级大得多、决策面宽得多**：

- bit15=0 / bit15=1 两分支公式都要改（parser + 协议文档）
- RP22 测试数据是按"错公式自洽"反推构造，要重构
- test helper（`createValidGpsData20(altitude=...)`）也按错公式编码，要改
- simulator `GpsDataGenerator` 发送端也要对齐
- [2776.8m, 6053.4m] 截断是 ino 单边 bug，parser 解不出（需要 ino 改或协议
  接受丢失精度契约）
- 下游消费审计（已完成，见 § 10.2 of audit：`DataSmoothing.kt:38` +
  `TestSessionViewModel.kt:405`，只在透传 / 平滑 / 展示，不影响判圈逻辑）

把这些都塞进 A16 会让本 change 变成 altitude change 的附庸，违反"独立功能点一
commit / 一 change"原则。**拆成独立 change 更干净**：

- 本 change（`fix-parser-signed-int-decoding`）：scope 最小，2 行代码 + 1 行
  `@Ignore` 删除 × 2，可以快速闭环给 production 解锁
- altitude change（`fix-altitude-encoding-contract-alignment`）：四方对齐 +
  历史数据标注 + 截断契约决策，独立走 spec 级讨论

## What

### R1：parser lat/lon signed int32 解码修复

**`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt:178`**：

```kotlin
BEFORE:
val currentLatitude = (latInt.toLong() and 0xFFFFFFFFL) / 10000000.0

AFTER:
// A16: 协议 / ESP32 ino 明确 lat 是 signed int32。`(latInt.toLong() and 0xFFFFFFFFL)`
//      把 signed 抹成 unsigned 会让所有南纬解成 +[180°, 400°] 大数，是严重的
//      signed-vs-unsigned 解码 bug。Kotlin Int / Double 直接相除会自动保留符号位。
val currentLatitude = latInt / 10_000_000.0
```

**`RaceChronoParser.kt:185`**（对称修复经度）：

```kotlin
BEFORE:
val currentLongitude = (lonInt.toLong() and 0xFFFFFFFFL) / 10000000.0

AFTER:
// A16: 协议 / ESP32 ino 明确 lon 是 signed int32。参考 latitude 同源修复。
val currentLongitude = lonInt / 10_000_000.0
```

**关键点**：

1. Kotlin `Int / Double` 会先把 Int 扩展为 Double（**保留符号**），不会走 unsigned
   展开路径，这是 Kotlin 语言规范保证的（与 Java `int / double` 一致）
2. 删除 `.toLong() and 0xFFFFFFFFL` 两行 mask，不改任何其他行（sync 字段 / fix
   quality / altitude / speed / bearing 都不动）
3. 10_000_000.0 改用下划线分隔提升可读性（Kotlin 数字字面量惯用），非必须但与修改
   同步带出

### R2：解除 RP16 / RP19 `@Ignore` + 补南纬 / 西经边界测试

**`core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt:278-335`**：

```kotlin
BEFORE:
@Test
@Ignore("暴露 parser 实际 bug：parseGpsData L164 `latInt.toLong() and 0xFFFFFFFFL` 把"
       + " signed int32 抹成 unsigned，负纬度解不回来。测试断言正确，实现有 bug。"
       + " 不在本次战役（时间戳可信性 + detector 量纲 + 测试迁移）范围，待独立战役修 parser 字段解析。")
fun RP16_parseLatitude_negative() {
    val data = createValidGpsData20(latitude = -33.8688)
    val result = parser.parseGpsData(data, createTestData())
    assertEquals("南纬应为负值", -33.8688, result.latitude, 0.0001)
}

@Test
@Ignore("暴露 parser 实际 bug：parseGpsData L171 `lonInt.toLong() and 0xFFFFFFFFL` 与 RP16 同源，"
       + "signed int32 被 unsigned 抹掉。测试断言正确。待独立战役修。")
fun RP19_parseLongitude_negative() { ... }

AFTER:
@Test
fun RP16_parseLatitude_negative() { /* 断言不变 */ }

@Test
fun RP19_parseLongitude_negative() { /* 断言不变 */ }
```

**新增 2 条边界测试**（补 A16 核销条件 (3) 的 lat/lon 部分；海拔 overflow 边界
测试不在本 change）：

```kotlin
@Test
fun parseGpsData_southernHemisphereAndWesternHemisphere_decodeBothNegativeCorrectly() {
    // 布宜诺斯艾利斯 (-34.6037°, -58.3816°) —— 两轴都是负值
    val data = createValidGpsData20(latitude = -34.6037, longitude = -58.3816)
    val result = parser.parseGpsData(data, createTestData())
    assertEquals("南纬", -34.6037, result.latitude, 0.0001)
    assertEquals("西经", -58.3816, result.longitude, 0.0001)
}

@Test
fun parseGpsData_extremeBoundaryValues_nearPolesAndAntimeridian() {
    // 南极附近 (-89.9999°) 与西半球接近反子午线 (-179.9999°) 边界值
    val data = createValidGpsData20(latitude = -89.9999, longitude = -179.9999)
    val result = parser.parseGpsData(data, createTestData())
    assertEquals("接近南极纬度", -89.9999, result.latitude, 0.00001)
    assertEquals("接近反子午线经度", -179.9999, result.longitude, 0.00001)
}
```

## Impact

### 协议与数据模型

- **不改** BLE 协议格式（Service UUID / Characteristic UUID / 20 字节主包结构）
- **不改** `GpsData` 数据类字段
- **不改** `docs/RaceChrono_BLE_Protocol.md` 协议文档（本 change 是让 parser
  与协议文档 + ino 对齐，不修改它们）
- **不改** `docs/RaceChrono_ESP32_M9N.ino` ESP32 固件
- **不改** parser 其他字段（sync / time / fix quality / satellites / altitude / speed / bearing / hdop / vdop 都不动）

### 受影响模块

| 模块 | 文件 | 动作 |
|---|---|---|
| parser | `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt` | 删 line 178 + 185 的 `.toLong() and 0xFFFFFFFFL` mask；改为 Int → Double 直接除法（保留符号） |
| 测试 | `core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt` | 去 RP16 / RP19 的 `@Ignore` 注解；新增 2 条南半球 + 西半球 + 极地边界测试 |

### 行为变更

| 场景 | Before | After |
|---|---|---|
| 北半球东半球（TFIC 等 +lat +lon）| 正确 | 不变（Int 正值除法结果相同） |
| 南半球（-lat）| +395° 大数 ❌ | 正确负纬度 ✓ |
| 西半球（-lon）| +300° 大数 ❌ | 正确负经度 ✓ |
| 南半球西半球（南美 / 南非西部）| 两轴都错 ❌ | 两轴都正确为负值 ✓ |
| 赤道 (lat=0) / 本初子午线 (lon=0) | 正确 | 不变 |

### 风险与缓解

| 风险 | 缓解 |
|---|---|
| Kotlin `Int / Double` 是否真的保留符号位（vs 某种隐式 unsigned 展开）| Kotlin 语言规范：数值类型转换时 Int → Double 走 IEEE 754 双精度，保留符号位。等价于 Java `int / double`。新增 RP16/RP19 + 2 条边界测试直接验证这个行为 |
| `10_000_000` vs `10_000_000.0` 分母精度 | `.0` 强制 Double 运算，Int 除法截断风险不存在。`_` 下划线是 Kotlin 字面量分隔惯用，不改数值 |
| 已有上层调用依赖"负纬度一定是 bug 数据 → 过滤掉"的假设 | grep `GpsDataFilter` / `GpsDataViewModel` / `LapTimingEngine` 等下游 —— 已确认无此假设（纬度 / 经度在 filter / engine 里只作为位置参考、距离计算的输入，没有 "负值 = 异常" 的判定）。本 change 作用域内执行此 grep 验证（见 tasks §1.1） |
| 简仿真 `GpsDataGenerator` 侧是否也要对齐 | simulator 目前只生成 TFIC 正坐标，暂无 bug 显形。但修 parser 后 simulator 里若有对称 encode 路径也应同步检查（见 tasks §2.4 作为 non-blocking 自检） |

### 回归保护要求

硬区分 v1 / v2 行为：

- **RP16** `RP16_parseLatitude_negative`（已有）：v1 输出 `+395.63` 触发 assertEquals FAIL；v2 输出 `-33.8688` pass
- **RP19** `RP19_parseLongitude_negative`（已有）：v1 输出 `+307.08`；v2 输出 `-122.4194`
- **新增** `parseGpsData_southernHemisphereAndWesternHemisphere_decodeBothNegativeCorrectly`：两轴同时负
- **新增** `parseGpsData_extremeBoundaryValues_nearPolesAndAntimeridian`：极地 + 反子午线附近边界值
- **已有通过测试**（RP15 北纬 + RP17 赤道 + RP18 东经）：本 change 不改变其行为，作为 **正向回归保护**锁定"正值路径不回归"

## Alternatives

### A：把 altitude 三方对齐一起做

**拒收理由**：altitude 涉及官方协议 BLE DIY API / 协议文档 / ino / parser / test helper / simulator 五方对齐，ino 自身 [2776.8m, 6053.4m] 截断 bug 需要单独决策（改 ino 还是接受精度丢失），下游消费者审计也要单独做。五方对齐远比 2 行 parser 修复复杂，强塞进 A16 会让本 change 变成 altitude change 的附庸。codex 复核明确拆开（§10.1 Q2 of audit 文档）。

### B：只改 parser 不去 `@Ignore`

**拒收理由**：A16 核销条件 (2) 明确要求"三条 `@Ignore` 去掉，断言全绿"（RP22 虽然拆出去，但 RP16/RP19 是本 change 范围）。不去 `@Ignore` = parser 修了但测试不跑 = 没有回归保护 = 未来可能又被误改回 unsigned。

### C：用 Kotlin `UInt` / `unsigned-kotlin` 库显式区分

**拒收理由**：引入新类型 / 依赖就是过度设计。协议契约是 signed int32，Kotlin `Int` 就是 signed int32，直接用即可。新增 `UInt` 只会让 parser 里大量类型转换复杂化。

### D：用 `latInt.toDouble() / 10_000_000.0`

**可接受但非必需**。`Int / Double` 已经隐式把 Int 转 Double 再除，`toDouble()` 显式转是冗余（但不出错）。本 change 选隐式转（更简洁），文档注释说明此语义。

### E：保留原 mask 但改除数

**拒收理由**：有人可能想 `(latInt.toLong() and 0xFFFFFFFFL) / 10_000_000.0` 对负值输出大数后，再做 "如果 > 180 则减 360" 的后处理。这是**伪修复**（仅对纬度 / 经度接近 ±90 / ±180 的值才偶然对齐，其他值全错），且背离协议 signed 契约。

## Non-goals

### 不改的代码

- **不改** parser 其他字段（sync / time / fix quality / satellites / altitude bit15 两分支公式 / speed / bearing / hdop / vdop）
- **不改** `docs/RaceChrono_BLE_Protocol.md` 协议文档
- **不改** `docs/RaceChrono_ESP32_M9N.ino` ESP32 固件
- **不改** `GpsData` 数据类 / 任何下游消费者

### 不做的功能

- **不修** RP22 `@Ignore`（与 altitude 绑定，随 altitude change 一起）
- **不处理** altitude bit15=0 / bit15=1 公式修正（协议 + parser 四方对齐，altitude change 范围）
- **不处理** ESP32 ino 在 [2776.8m, 6053.4m] 区间的截断 bug（altitude change 范围）
- **不追加** altitude 边界测试（海拔 > 3276.7m 等 —— A16 核销条件 (3) 的海拔部分移交 altitude change）
- **不处理** A17（`DomainModuleKoinTest` DI fallback runCatching 范围收窄 —— 战役 D 尾巴另一条，独立 change）
- **不审计** 下游对 altitude 字段的消费（已在 audit 文档 § 10.2 完成：无判定依赖）
- **不回溯** 历史 session 持久化数据（无持久化历史则无需迁移；若未来有历史则按 "不建议静默批量改" 策略单独处理）

### 不做的文档修订

- **不修** `docs/superpowers/reviews/attack-backlog.md` 中 A16 条目的核销条件
  (3) "海拔 overflow 边界测试" 的原文字 —— 在本 change 核销时**迁档**时显式移交
  altitude change，而不是修改原始条目（保留历史）
- **不修** 任何 review 文档（无与本 change 相关的错误叙述需要修订）
