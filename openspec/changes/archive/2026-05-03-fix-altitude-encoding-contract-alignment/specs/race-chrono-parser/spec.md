# Spec Delta: race-chrono-parser

> Capability: **RaceChrono BLE 协议 altitude 字段编解码四方契约对齐（A16b）**。
> parser 两分支解码 MUST 对称于 ESP32 ino 编码；simulator 发送端编码 MUST 与 ino
> 使用相同的判定条件 + 相同的 bit15=1 公式；test helper MUST 按 ino 真实公式生成
> 字节（v1 "按错公式反推 + 错字节自洽"的 RP22 闭环修正）。
>
> 真相源拍板：`docs/superpowers/reviews/2026-04-24-a16-altitude-encoding-tri-party-audit.md`
> § 10.1 Q1 —— 以 **RaceChrono 官方 BLE DIY API + ESP32 ino 实际实现**为准。协议
> 文档 `docs/RaceChrono_BLE_Protocol.md` § 3.4 同步改写与 ino 对称的公式。
>
> Non-goals：
> - 不改 ESP32 ino 固件（外部依赖）
> - [2776.7m, 6053.5m] 区间 ino 自身 `& 0x7FFF` 截断丢信息的 bug 作为已知精度契约
>   声明，不在本 change 内修复（R1 Scenario 5 显式 assertion 不保证精确数值）

## ADDED Requirements

### Requirement: parser altitude 两分支解码对称 ino 编码（R1）

RaceChronoParser 在 `parseGpsData` 内解码 altitude 字段 MUST 使用与 ESP32 ino 编码
对称的两段式公式：

- **bit15 == 0（低海拔）**：`alt = (altRaw and 0x7FFF) / 10.0 - 500.0`
  - 对称 ino 编码 `raw = ((int)((alt + 500.0) * 10.0)) & 0x7FFF`
  - 精度 0.1m，理论范围 -500m ~ 2776.7m
- **bit15 == 1（高海拔）**：`alt = (altRaw and 0x7FFF).toDouble() - 500.0`
  - 对称 ino 编码 `raw = (((int)(alt + 500.0)) & 0x7FFF) | 0x8000`
  - 发送端 `alt >= 6053.5m` 触发 bit15=1；解码值精度 1m，最小可回读整数为 6053m（alt=6053.5m 量化回 6053m，alt=6054m 精确回读 6054m —— 这是**解码值**的最小整数，不是**发送端**触发条件）

parser 解码 MUST NOT 使用 v1 错公式 `raw / 100 - 500`（bit15=0）或 `raw * 10 / 100 - 500`
（bit15=1）—— 两者与 ino 编码不对称，导致生产中所有 altitude 字段被错解。

#### Scenario: bit15=0 典型低海拔 100m 正确解码

- **GIVEN** 一帧 ino 真实编码的字节 `data[12] = 0x17, data[13] = 0x70`（raw = 0x1770 = 6000）
- **AND** bit15 = 0（最高位为 0）
- **WHEN** `parser.parseGpsData(data, prev)` 解码
- **THEN** `result.altitude == 100.0`（`6000 / 10 - 500 = 100`）
- **AND 硬区分 v1**：v1 公式 `6000 / 100 - 500 = -440m` 误判（本断言证明 v2 公式生效）

#### Scenario: bit15=0 边界值 alt=0m 与 alt=-500m

- **GIVEN** alt=0m 的 ino 编码字节：raw = (0+500)*10 = 5000 = 0x1388，字节 `0x13 0x88`
- **WHEN** parser 解码
- **THEN** `result.altitude == 0.0`（`5000 / 10 - 500 = 0`）
- **AND** 对称：alt=-500m 的 raw = 0 → 字节 `0x00 0x00` → 解码 `0 / 10 - 500 = -500`

#### Scenario: bit15=1 最小整数边界 6054m 正确解码

- **GIVEN** alt=6054m 的 ino 编码：因 `6054 >= 6053.5` ino 走 bit15=1，raw_inner = 6054 + 500 = 6554，raw = 6554 | 0x8000 = 0x999A，字节 `0x99 0x9A`
- **WHEN** parser 解码
- **THEN** bit15 = 1 分支触发，`result.altitude == 6054.0`（`(0x999A & 0x7FFF) - 500 = 6554 - 500 = 6054`）
- **AND 硬区分 v1**：v1 公式 `(0x999A & 0x7FFF) * 10 / 100 - 500 = 65540 / 100 - 500 = 155.4` 误判
- **AND 边界说明**：选 6054m（非 6053m）是因 ino 判定 `if (alt < 6053.5) bit15=0`，`6053 < 6053.5` 仍走 bit15=0 截断分支（Non-goal 区间，见 Scenario 5），`6054 >= 6053.5` 才稳定触发 bit15=1

#### Scenario: bit15=1 高海拔 10000m 正确解码

- **GIVEN** alt=10000m 的 ino 编码：raw_inner = 10500，raw = 10500 | 0x8000 = 0xA904，字节 `0xA9 0x04`
- **WHEN** parser 解码
- **THEN** `result.altitude == 10000.0`（`(0xA904 & 0x7FFF) - 500 = 10500 - 500 = 10000`）

#### Scenario: ino 截断区间 [2776.7m, 6053.5m] parser 不报错但精度契约不保证

- **GIVEN** alt=4000m（落在 ino [2776.7m, 6053.5m] 截断区间）
- **AND** ino 按 bit15=0 编码：`((int)((4000+500)*10)) & 0x7FFF = 45000 & 0x7FFF = 45000 - 32768 = 12232`，字节 `0x2F 0xC8`
- **WHEN** parser 解码
- **THEN** parser MUST NOT 抛异常
- **AND** 解码结果（`12232 / 10 - 500 = 723.2`）与真实 alt=4000m **不一致**（精度丢失契约）
- **AND** 该场景属 ino 自身截断 bug 的 Non-goal 区间，parser 单边无法恢复（R5 R1 Scenario 级契约声明）
- **AND** 测试 MUST 使用 `assertNotNull(result.altitude)` 而非具体数值断言（v2 不承诺数值精度，只承诺不抛异常 + 签名稳定）

---

### Requirement: simulator 发送端 altitude 编码与 ino 对齐（R2）

simulator `GpsDataGenerator` 在构造 BLE 字节帧的 byte 12-13（altitude 字段）MUST 使用
与 ESP32 ino `RaceChrono_ESP32_M9N.ino:294-298` 完全相同的两段式编码：

- **判定条件**：MUST 按 **alt 阈值 6053.5m** 判定（`if (altMeters < 6053.5) bit15=0 else bit15=1`），
  MUST NOT 按 raw 是否溢出判定（v1 `raw <= 32767` 错）
- **bit15 == 0**：`raw = ((int)((alt + 500) * 10)) & 0x7FFF`（与 ino 一致）
- **bit15 == 1**：`raw = (((int)(alt + 500)) & 0x7FFF) | 0x8000`（**不乘 10**，与 ino 一致；v1 错误乘 10）

simulator 对 altitude 的编码 MUST 与 parser 解码**在截断区间外**形成精确往返（parser
解码 simulator 编码的字节还原原 alt）。对 **[2776.7m, 6053.5m] 截断区间**，simulator
MUST 与 ino 一致产生截断字节，parser 解码为截断后的值（如 4000m → `0x2F 0xC8` →
723.2m）；该区间**不承诺**还原原 alt，与 R1 Scenario 5 / R5 Non-goal 契约对齐。

#### Scenario: simulator 编码 alt=100m 走 bit15=0 分支

- **GIVEN** `altitude = 100.0f`
- **WHEN** `GpsDataGenerator.generateGpsMainData()` 构造字节
- **THEN** 判定条件 `100 < 6053.5` 为 true，走 bit15=0
- **AND** `raw = ((100 + 500) * 10).toInt() and 0x7FFF = 6000 and 0x7FFF = 6000 = 0x1770`
- **AND** `data[12] = 0x17, data[13] = 0x70`
- **AND** 该字节被 parser 解码后等于原始 `100.0`（往返一致）

#### Scenario: simulator 编码 alt=10000m 走 bit15=1 分支

- **GIVEN** `altitude = 10000.0f`
- **WHEN** `GpsDataGenerator.generateGpsMainData()` 构造字节
- **THEN** 判定条件 `10000 < 6053.5` 为 false，走 bit15=1
- **AND** `raw = (((10000 + 500).toInt()) and 0x7FFF) or 0x8000 = (10500 and 0x7FFF) or 0x8000 = 10500 or 0x8000 = 0xA904`
- **AND** `data[12] = 0xA9, data[13] = 0x04`
- **AND 硬区分 v1**：v1 bit15=1 公式 `((10500 * 10).toInt() and 0x7FFF) or 0x8000 = 105000 and 0x7FFF = 39464 or 0x8000 = 0x9A28` 字节 `0x9A 0x28` → 不同字节
- **AND** 该字节被 v2 parser 解码后等于原始 `10000.0`（往返一致）

#### Scenario: simulator → parser altitude 往返一致（E2E 契约）

- **GIVEN** `altitude` 取值域 `{-500, 0, 100, 277.6, 1600, 6054, 10000}`（覆盖 bit15=0 / bit15=1 两分支典型 + 边界；**6053 / 6053.5 刻意不纳入取值域**，前者走 bit15=0 截断区间 Non-goal，后者 bit15=1 精度 1m 舍弃小数导致 round-trip 不精确）
- **WHEN** simulator 编码每个 alt 值 → parser 解码得到 altDecoded
- **THEN** 对每个输入 `alt`，`Math.abs(altDecoded - alt) < 1.0`（bit15=1 精度 1m；bit15=0 精度 0.1m 更严）
- **AND** `[2776.7m, 6053.5m]` 区间不在本 Scenario 取值域内（R1 Scenario 5 已声明该区间 Non-goal）

---

### Requirement: test helper altitude 按 ino 真实公式生成字节 + RP22 去 @Ignore（R3）

测试层字节构造函数 MUST 按 ino 真实编码公式生成 byte 12-13，不得按 v1 错公式反推。
具体涉及 `createValidGpsData20` helper：该 helper MUST 使用与 R2 simulator 编码完全
相同的判定条件 + bit15 公式。RP22 测试的 `@Ignore` 注解 MUST 去掉，测试数据字节
MUST 用 ino 对 alt=1600m 的真实编码（`0x52 0x08` bit15=0 分支，而非 v1 的
`0xD2 0x08` 错公式反推）。

新增两条 bit15=1 分支测试覆盖 R1 Scenario 3/4：

- **RP22b** `parseAltitude_highAltitudeBit15One_6054m`：alt=6054m 走 bit15=1 最小整数边界（6053m 仍走 bit15=0 截断区间）
- **RP22c** `parseAltitude_highAltitudeBit15One_10000m`：alt=10000m 走 bit15=1 典型

#### Scenario: RP22 测试数据重构为 ino 真实编码 + 去 @Ignore

- **GIVEN** 原 RP22 测试字节 `data[12] = 0xD2, data[13] = 0x08`（= 0xD208，按 v1 错公式反推）
- **WHEN** 本 change 修订
- **THEN** RP22 字节 MUST 改为 `data[12] = 0x52, data[13] = 0x08`（= 0x5208，ino 对 1600m 的真实编码）
- **AND** RP22 `@Ignore` 注解 MUST 去掉
- **AND** 断言 `result.altitude == 1600.0`（v2 parser 公式：`(0x5208 and 0x7FFF) / 10 - 500 = 21000 / 10 - 500 = 1600`）
- **AND** v1 parser 解码同样字节得 `21000 / 100 - 500 = -290` 会失败 —— 证明本测试硬区分 v1/v2 parser 公式

#### Scenario: RP22b 新增高海拔 bit15=1 6054m 最小整数边界测试

- **GIVEN** 新测试方法 `parseAltitude_highAltitudeBit15One_6054m`
- **AND** 字节按 ino 编码 `raw = (6054+500) | 0x8000 = 0x999A`，`data[12] = 0x99, data[13] = 0x9A`
- **WHEN** parser 解码
- **THEN** `result.altitude == 6054.0`（精度 1m 精确整数 round-trip，无舍入损失）
- **AND** 测试未加 `@Ignore`，新增即通过
- **AND 硬区分 v1**：v1 bit15=1 公式对同一字节解出 `((0x999A & 0x7FFF) * 10) / 100 - 500 = 155.4m`（测试会 fail v1）

#### Scenario: RP22c 新增高海拔 bit15=1 10000m 典型测试

- **GIVEN** 新测试方法 `parseAltitude_highAltitudeBit15One_10000m`
- **AND** 字节按 ino 编码 `raw = (10000+500) | 0x8000 = 0xA904`，`data[12] = 0xA9, data[13] = 0x04`
- **WHEN** parser 解码
- **THEN** `result.altitude` 在 10000.0 ± 0.5 范围内

#### Scenario: createValidGpsData20 helper altitude 参数按 ino 公式生成字节

- **GIVEN** `createValidGpsData20(altitude = 100.0)` 构造 alt=100m 的字节流
- **WHEN** helper 计算 byte 12-13
- **THEN** helper MUST 按 ino 真实公式 `if (alt < 6053.5) raw = ((alt+500)*10).toInt() and 0x7FFF else raw = ((alt+500).toInt() and 0x7FFF) or 0x8000` 生成字节
- **AND** 100m 的字节 = `0x17 0x70`（与 R2 simulator 编码一致）
- **AND** 已有使用 `createValidGpsData20(altitude = X)` 的测试（非 RP22）MUST 在本 change 后仍通过（helper 行为对 `alt < 2776.7` 场景与 v1 不同，需检查回归）
