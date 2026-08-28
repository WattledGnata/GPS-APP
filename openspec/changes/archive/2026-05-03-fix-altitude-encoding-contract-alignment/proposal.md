# fix-altitude-encoding-contract-alignment

A16b altitude 四方契约对齐，闭环 attack-backlog **A16b** 条目。把 `parser 解码 /
RaceChrono 协议文档 / ESP32 ino 编码 / RaceChronoParserTest helper / simulator
GpsDataGenerator` 五方在 altitude 字段上对齐到**同一真相源**；RP22 `@Ignore` 去掉 +
测试数据按对称公式重构；`[2776.7m, 6053.5m]` 区间 ino 自身截断 bug 作为已知精度丢失
契约显式声明（Non-goal：不改 ino 固件）。

依赖关系：
- 已闭环 `fix-parser-signed-int-decoding`（A16a lat/lon signed int32，Session 2 并行完成）
- 不依赖 A26 / A41（parser 另外两条清理独立做）

**决策面**：全部由 `docs/superpowers/reviews/2026-04-24-a16-altitude-encoding-tri-party-audit.md`
第 10 节 codex 复核拍板，本 proposal 不再重新开决策，只把结论落到代码 + 文档 +
测试 + simulator。

## Why

对抗 review `docs/superpowers/reviews/2026-04-22-lap-timing-and-gps-adversarial-review.md`
+ audit § 3 / § 6 / § 10 揭示 altitude 字段在四方（parser / 协议文档 / ino / test
helper）公式全部不一致 + simulator 第五方也不对齐，且**当前生产链路 altitude 全部
被错误解码**：

### 问题 1：parser bit15=0 / bit15=1 两分支公式都错（RaceChronoParser.kt:193-199）

当前实现：

```kotlin
val altitudeMeters = if ((altRaw and 0x8000) == 0) {
    // bit15=0: alt = raw / 100 - 500 (精度 0.01m, 范围 -500 ~ 277.67m)
    (altRaw and 0x7FFF) / 100.0 - 500.0
} else {
    // bit15=1: alt = (raw & 0x7FFF) * 10 / 100 - 500 (精度 0.1m, 扩展范围到 6052.7m)
    ((altRaw and 0x7FFF) * 10.0) / 100.0 - 500.0
}
```

与 ino 真实编码（`docs/RaceChrono_ESP32_M9N.ino:294-298`）不对称：

- ino bit15=0：`raw = ((int)((alt + 500.0) * 10.0)) & 0x7FFF` → 逆运算应为 `alt = raw / 10 - 500`
- ino bit15=1：`raw = (((int)(alt + 500.0)) & 0x7FFF) | 0x8000` → 逆运算应为 `alt = (raw & 0x7FFF) - 500`

**数值验证**（audit § 6.2）：

| 输入 alt | ino 编码 raw | 当前 parser 解码 | 正解 |
|---|---|---|---|
| -500m | 0 | -500 ✓ | -500 ✓ |
| 0m | 5000 | **-450m ❌** | 0m |
| 100m | 6000 | **-440m ❌** | 100m |
| 277.67m | 7776 | **-422.24m ❌** | 277.6m |
| 1600m | 21000 | **-290m ❌** | 1600m |
| 6054m | 0x999A（bit15=1 分支，`6054 >= 6053.5`） | **155.4m ❌** | 6054m |

**后果**：生产中 app 显示的 altitude 全部错误（正常海拔场景解成 -500m 附近的负值）。
audit § 10.2 已确认下游仅透传 / 平滑 / 展示，不影响判圈判定，所以未引发功能性故障，
但语义错误仍是严格生产 bug。

### 问题 2：协议文档 § 3.4 公式与 ino 不对称（RaceChrono_BLE_Protocol.md:94-99）

协议文档写：

```
Bit 15 = 0（无溢出）: alt = raw / 100.0 - 500.0
  raw 范围: 0-32767, 对应 -500.0m 到 277.67m
Bit 15 = 1（有溢出）: alt = ((raw & 0x7FFF) * 10) / 100.0 - 500.0
  raw 范围: 32768-65535, 对应 277.68m 到 6052.7m
```

与 ino 真实编码不对称（同问题 1 公式）。parser 照抄协议文档，所以两方错得一样。

**真相源拍板**（audit § 10.1 Q1）：以 **RaceChrono 官方 BLE DIY API + ino 实际发送端
实现**为准（ino 是该协议真实生效的发送端；官方 RaceChrono BLE DIY API 文档印证
对称公式）。协议文档应改为与 ino 一致。

### 问题 3：RaceChronoParserTest RP22 测试数据按错公式反推，形成自洽闭环

`RaceChronoParserTest.kt:370` RP22 `@Ignore` 测试：

```kotlin
// 1600m + 500 = 2100, 2100 * 10 = 21000 = 0x5208
// overflow: 0xD208 (overflow bit + high byte 0xD2, low byte 0x08)
data[12] = 0xD2.toByte()
data[13] = 0x08.toByte()
val result = parser.parseGpsData(data, createTestData())
assertEquals("海拔溢出时应为1600", 1600.0, result.altitude, 0.1)
```

测试数据 `0xD2 0x08`（= 0xD208 = 0x5208 | 0x8000）是按**当前错公式**反推的字节：
`(0xD208 & 0x7FFF) * 10 / 100 - 500 = 21000 / 10 - 500 = 1600`。所以按当前 parser
解码是 1600m，和期望一致 —— 但这是"错数据 + 错公式" 互相自洽。

真实 ino 对 1600m 的编码字节是 `0x52 0x08`（不带 overflow bit，走 bit15=0 分支，因为
`alt < 6053.5`）。改正后 parser 解码：`21000 / 10 - 500 = 1600m` ✓。

**修订**：RP22 字节重构为 `0x52 0x08`（ino 真实编码），去 `@Ignore`。同时新增
两条高海拔 bit15=1 分支测试（alt=6054 + alt=10000）硬区分两分支（6054m 是 ino 判定 `alt < 6053.5` 为 false 后的最小精确整数边界；注意 **6053m 仍走 bit15=0 截断区间**不适合做 bit15=1 边界）。

### 问题 4：simulator `GpsDataGenerator` 编码不与 ino 对齐（GpsDataGenerator.kt:85-102）

simulator 编码：

```kotlin
val altRaw = ((altMeters + 500.0) * 10.0).toInt()
val altEncoded = if (altRaw <= 32767) {
    altRaw and 0x7FFF  // bit15 = 0
} else {
    (altRaw and 0x7FFF) or 0x8000  // bit15 = 1
}
```

与 ino 两处差异：

1. **判定条件**：ino 按 `alt < 6053.5` 阈值判定；simulator 按 `raw <= 32767`（即 `alt < 2776.7`）判定 → simulator 在 [2776.7m, 6053.5m] 区间会走 bit15=1，ino 走 bit15=0
2. **bit15=1 公式**：ino 是 `(alt + 500) & 0x7FFF | 0x8000`（不乘 10）；simulator 是 `((alt+500)*10 & 0x7FFF) | 0x8000`（乘 10）→ 同一高海拔在 simulator 和 ino 下编出不同字节

simulator 不对齐 ino 让"离线回放 + 单测覆盖的 parser"与"真机接收 ESP32 数据的 parser"
语义分裂，违反"真相源一致"契约。

### 问题 5：ino 自身在 [2776.7m, 6053.5m] 区间有编码截断 bug（audit § 6.4）

ino bit15=0 分支 `((int)((alt + 500.0) * 10.0)) & 0x7FFF`：当 `alt > 2776.7m`（即
raw 中间结果 > 32767）时被 `& 0x7FFF` 截断 15 位，高位丢失、**不可逆**。但 ino 判定
条件是 `alt < 6053.5m`，所以在 `[2776.7m, 6053.5m]` 区间 ino 选了 bit15=0 却编码
被截断，这是 ino 自身 bug。

**决策**（audit § 10.1 Q4）：**纳入本 change 作为 Non-goal 明确声明**，不改 ino 固件
（外部依赖，改固件需刷机工作）。parser 对该区间按 bit15=0 公式解码得到截断后的错值，
但不报错（保持兼容）。

## What

### R1 parser altitude 解码对称于 ino 编码

`RaceChronoParser.kt:193-199` 两分支公式改为与 ino 编码对称的逆运算：

```kotlin
BEFORE:
val altitudeMeters = if ((altRaw and 0x8000) == 0) {
    (altRaw and 0x7FFF) / 100.0 - 500.0            // 错
} else {
    ((altRaw and 0x7FFF) * 10.0) / 100.0 - 500.0   // 错
}

AFTER:
val altitudeMeters = if ((altRaw and 0x8000) == 0) {
    // bit15=0 (低海拔)：ino 编码 raw = (alt+500)*10，逆运算 alt = raw / 10 - 500
    // 精度 0.1m，理论范围 -500 ~ 2776.7m（超此范围 ino 内部 & 0x7FFF 截断，见 R5 Non-goal）
    (altRaw and 0x7FFF) / 10.0 - 500.0
} else {
    // bit15=1 (高海拔)：ino 编码 raw = alt + 500（不乘 10），逆运算 alt = raw - 500
    // 精度 1m，范围 6053 ~ 33267m（高海拔场景，赛车通常不涉及）
    (altRaw and 0x7FFF).toDouble() - 500.0
}
```

### R2 协议文档 § 3.4 altitude 公式与 ino 对齐

`docs/RaceChrono_BLE_Protocol.md:94-99` altitude 分支公式改为：

```markdown
BEFORE:
- Bit 15 = 0（无溢出）: alt = raw / 100.0 - 500.0
  - raw 范围: 0-32767, 对应 -500.0m 到 277.67m
- Bit 15 = 1（有溢出）: alt = ((raw & 0x7FFF) * 10) / 100.0 - 500.0
  - raw 范围: 32768-65535, 对应 277.68m 到 6052.7m

AFTER:
- Bit 15 = 0（低海拔）: alt = raw / 10.0 - 500.0
  - raw 范围: 0-32767, 对应 -500.0m 到 2776.7m（精度 0.1m）
  - 注：ESP32 ino 判定 `alt < 6053.5` 时走本分支，但 [2776.7m, 6053.5m] 区间 raw 会被 & 0x7FFF 截断（精度不保证，见附录）
- Bit 15 = 1（高海拔）: alt = (raw & 0x7FFF) - 500.0
  - raw 范围: 低 15 位 0-32767；发送端 `alt >= 6053.5m` 触发 bit15=1；解码值精度 1m，最小可回读整数 6053m（量化回读）
```

### R3 `RaceChronoParserTest` helper altitude 编码与 ino 对齐 + RP22 重构

`RaceChronoParserTest.kt` 的 `createValidGpsData20` helper（或等效构造函数）altitude
编码 MUST 按 ino 真实公式生成字节。RP22 `@Ignore` 去掉，测试数据重构为 ino 对 1600m
的真实编码：

```kotlin
// RP22 (bit15=0 分支 alt=1600m)：ino 编码 raw = (1600+500)*10 = 21000 = 0x5208
// 字节：0x52 0x08（最高位 0 → bit15=0）
data[12] = 0x52.toByte()
data[13] = 0x08.toByte()
```

新增两条 bit15=1 分支测试：

- **RP22b** `parseAltitude_highAltitudeBit15One_6054m`（bit15=1 最小整数边界，`alt >= 6053.5`）
- **RP22c** `parseAltitude_highAltitudeBit15One_10000m`（高山场景）

两条的 ino 编码字节按真实公式 `((alt+500) & 0x7FFF) | 0x8000` 生成。

### R4 simulator `GpsDataGenerator` altitude 编码与 ino 对齐

`simulator/src/main/java/com/blazepush/simulator/data/GpsDataGenerator.kt:85-102` 两处改：

1. **判定条件**：从 `raw <= 32767` 改为 `altMeters < 6053.5`（与 ino 一致）
2. **bit15=1 公式**：从 `(altRaw and 0x7FFF) or 0x8000`（altRaw 是 `(alt+500)*10`）改为 `(((altMeters + 500).toInt()) and 0x7FFF) or 0x8000`（不乘 10，与 ino 一致）

```kotlin
BEFORE:
val altRaw = ((altMeters + 500.0) * 10.0).toInt()
val altEncoded = if (altRaw <= 32767) {
    altRaw and 0x7FFF
} else {
    (altRaw and 0x7FFF) or 0x8000
}

AFTER:
val altEncoded = if (altMeters < 6053.5) {
    // bit15=0：与 ino 一致 (alt+500)*10 & 0x7FFF
    (((altMeters + 500.0) * 10.0).toInt()) and 0x7FFF
} else {
    // bit15=1：与 ino 一致 (alt+500) & 0x7FFF | 0x8000（不乘 10）
    ((((altMeters + 500.0).toInt()) and 0x7FFF)) or 0x8000
}
```

simulator 字节级 altitude 编码单测由本 change 新增 `GpsDataGeneratorTest.generatesBytes_altitudeWithInoCompatibleEncoding`
必做（覆盖 100m / 10000m / 4000m 截断区间三组字节，硬区分 v1/v2 编码；见 tasks §2.2.2）。
E2E 仅做跨管道回归兜底，不替代字节级硬断言。

**往返契约边界**：simulator → parser 在**截断区间外**（alt < 2776.7m 或 alt > 6053.5m）
精确往返；在 `[2776.7m, 6053.5m]` 区间 simulator 字节与 ino 字节一致，但 parser 解码值
不承诺还原原 alt（R1 Scenario 5 / R5 Non-goal 契约）。

### R5 `[2776.7m, 6053.5m]` 区间 ino 编码截断 Non-goal + 契约注释

本 change **不改 ino 固件**（外部依赖，需刷机；且该区间对赛车场景非高频）。但 parser
/ 协议文档 / helper 三方 MUST 在相关位置加注释明确：

> **精度契约**：alt ∈ [2776.7m, 6053.5m] 区间，ESP32 ino 选择 bit15=0 编码但 raw
> 中间结果 > 32767 会被 `& 0x7FFF` 截断 15 位，**不可逆**。parser 按 bit15=0 公式
> 解码得到的是截断后的值（数值不精确、不保证恢复原 alt）。该区间是 ino 自身的已知
> bug，不在本 change scope；未来改固件时统一修复。

对应行为：parser 对该区间的解码**不报错**，但返回值与真实 alt 不一致。测试 MUST
用 `assertNotNull` 而非具体数值断言（或用 TODO 标注跳过数值断言）。

## Impact

### 数据模型变更

无。`GpsData.altitude: Double` 字段定义不变；改的是 parser 对同一字节的解码逻辑。

### 受影响模块

| 模块 | 文件 | 动作 |
|---|---|---|
| parser | `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt:193-199` | 两分支公式改为对称 ino 编码的逆运算（R1） |
| 协议文档 | `docs/RaceChrono_BLE_Protocol.md:94-99` | § 3.4 altitude 公式 + 精度契约注释（R2 + R5） |
| 测试 | `core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt` | `createValidGpsData20` helper altitude 编码按 ino 真实公式；RP22 重构 + 去 @Ignore；新增 RP22b / RP22c 高海拔分支（R3） |
| simulator | `simulator/src/main/java/com/blazepush/simulator/data/GpsDataGenerator.kt:85-102` | altitude 编码两处改（判定条件 + bit15=1 公式）与 ino 对齐（R4） |
| ino（固件） | `docs/RaceChrono_ESP32_M9N.ino` | **不改**（Non-goal，R5 声明精度契约） |

### 行为变更

| 场景 | Before | After |
|---|---|---|
| 真实 GPS 海拔 100m（ino 发 `0x17 0x70`） | parser 解 **-440m** ❌ | parser 解 **100m** ✓ |
| 真实 GPS 海拔 1600m（ino 发 `0x52 0x08`） | parser 解 **-290m** ❌ | parser 解 **1600m** ✓ |
| 真实 GPS 海拔 6054m（ino bit15=1，发 `0x99 0x9A`） | parser 解 **155.4m** ❌ | parser 解 **6054m** ✓ |
| 真实 GPS 海拔 4000m（ino [2776.7, 6053.5] 区间） | parser 解出 **被截断的错值** | parser 解出**被截断的错值**（精度丢失契约，R5 Non-goal） |
| simulator 发海拔 1600m（v1: raw=21000 走 bit15=0 ✓） | parser v1 解 -290m ❌，v2 解 1600m ✓ | 与 ino 对齐，同等行为 |
| simulator 发海拔 7000m（v1: raw=75000 > 32767 走 bit15=1，公式 `(alt+500)*10 & 0x7FFF | 0x8000`） | parser v1 按错公式解，可能得到正确数值（错公式自洽）；v2 按正解 parser 会得到错值（simulator 与 ino 不对齐） | **simulator 改 R4 后与 ino 一致**，parser v2 正解 7000m ✓ |

### 下游审计（audit § 10.2 已完成）

| 下游 | 是否读 altitude | 判圈依赖 | 本 change 影响 |
|---|---|---|---|
| `DataSmoothing.kt:38` | ✅ 透传 | ❌ | 显示/记录数值从错变对 |
| `TestSessionViewModel.kt:405` | ✅ 传 lap sample altitude 字段 | ❌ | 历史 lap 持久化数据中 altitude 错，不回溯（生产决策见 audit Q3） |
| `LapDebugExecutionScreen.kt` / UI 层 | 未直接消费 | — | 无 |
| `LapTimingEngine` / `GpsDataFilter` / 判圈链路 | ❌ | — | 无（altitude 只被平滑/展示，不进判圈算法） |

### 风险与缓解

| 风险 | 缓解 |
|---|---|
| 协议文档修订属跨项目文档（可能被外部消费） | `RaceChrono_BLE_Protocol.md` 是本 repo `docs/` 下自维护文档，不对外发布 API；修订只影响本项目内 review / 新人上手 |
| RP22 测试数据重构后历史 @Ignore 注释含旧公式推导可能让维护者混淆 | RP22 去 @Ignore 同时把注释里"1600m + 500 = 2100, 2100*10 = 21000 = 0x5208"错推导删除；新注释按 ino 真实公式写 |
| simulator 改编码后，已有 replay fixture（`tianfu_track_replay_5hz.json` 等）的 altitude 字段是否需要重编码？ | replay fixture 存 **Double 数值**（不是编码字节），simulator 从 replay 读 Double 后按新编码公式打包 —— fixture 不需要改 |
| [2776.7m, 6053.5m] 区间精度丢失契约可能被未来用户场景触发 | TFIC / 国内赛道 altitude 基本 < 2000m，该区间低频；R5 Non-goal 注释显式提示"未来改 ino 再统一修"。本 change 不承诺该区间解码精度 |
| E2E 测试 altitude 断言与新公式不一致 | 全仓 grep altitude 相关断言；`EndToEndLapTimingContractTest` altitude 相关测试少（audit 未列出），按 R4 simulator 重编码后数值应自动对齐 |

### 回归保护清单

- **R1 × 5**：bit15=0 四个典型值（0m / 100m / 277.6m / 2776.7m 边界）+ bit15=1 两个典型值（6054m / 10000m；6053m 走 bit15=0 截断）
- **R3 × 3**：RP22 重构（1600m bit15=0）+ RP22b（6054m bit15=1 最小整数边界）+ RP22c（10000m bit15=1 典型高海拔）
- **R4 × 3**：simulator 编码 bit15=0（100m）+ bit15=1（10000m）+ Non-goal 截断区间（4000m `0x2F 0xC8`）字节正确性（**GpsDataGeneratorTest 字节级单测必做**，E2E 仅兜底）
- **R5 × 1**：[2776.7m, 6053.5m] 截断契约测试（`parseAltitude_inoTruncationRange_returnsValueWithoutAssertion`）—— 验证 parser 不抛异常但不断言精确数值

## Alternatives

### 方案 A：不改 parser，只改协议文档 + simulator 与当前 parser 对齐（"接受 altitude 字段错解"）

**拒绝理由**：ino 是真正的硬件发送端，生产中真实 GPS 字节按 ino 编码。若 parser 不改，真机 altitude 永远错。audit § 10.2 审计也明确"展示/记录数值错误"仍是严格 bug，只是下游判圈不受影响，不能以"无功能影响"为由保留错公式。

### 方案 B：改 ino 固件把编码改成与协议文档 / parser 一致

**拒绝理由**：（1）ino 是外部设备固件，需要刷机；（2）官方 RaceChrono BLE DIY API 契约是 ino 现有公式的真相源（audit § 10.1 Q1），改 ino 等于与官方协议断开；（3）市面上已发货的 ESP32 RaceChrono 板子都在用 ino 公式，改 ino 会导致与商用 RaceChrono app 不兼容。保留 ino，parser / 协议文档 / test helper / simulator 对齐 ino 是唯一正解。

### 方案 C：parser 对 [2776.7m, 6053.5m] 区间做防御性检测（返回 null / 抛异常 / 打 qualityFlag）

**拒绝理由**：当前 parser 签名是 `fun parseGpsData(bytes, currentData): GpsData`，没有异常分支；改返回类型引入上游适配成本。该区间对赛车场景（TFIC < 2000m）低频，R5 Non-goal + 注释提示已足够。未来若真遇到该区间用户场景再补。

### 方案 D：把 A16b 的五方对齐拆成多个独立 change

**拒绝理由**：parser + 协议文档 + test helper + simulator 四方的契约是**强耦合**（任一改另外三方必须同步，否则生产/测试/模拟三套数据互相矛盾）。拆多 change 会导致中间态不一致。ino 是 Non-goal 不改，保留"改 parser + 协议 + helper + simulator = 单 change"合适。

### 方案 E：[2776.7m, 6053.5m] 区间 parser 层做"修复性"解码尝试（例如通过其他字段推断高位）

**拒绝理由**：raw 被 `& 0x7FFF` 截断后高位信息**不可逆丢失**，parser 无法单边恢复。方案过度工程。audit § 10.1 Q4 已拍板"Non-goal 声明契约"。

## Non-goals

- **不改** ESP32 `docs/RaceChrono_ESP32_M9N.ino` 固件（R5 已声明）
- **不修复** [2776.7m, 6053.5m] 区间 ino 截断 bug 的数值精度（Non-goal，靠 R5 注释契约化）
- **不做** 历史持久化 session 的 altitude 数据回溯（audit § 10.2 已审计下游仅透传，
  无判圈依赖；历史数据若用户用到展示层"看错"就一次性标注"旧数据不可信"即可）
- **不改** `GpsData.altitude` 字段类型 / 精度 / 单位
- **不接入** WGS84 椭球高度 vs 海平面高度的语义澄清（本 change 只对齐编码公式）
- **不做** A26（`parseGpsTimeData` 不写 isTestReady）/ A41（totalDistance 死状态清理）—— Session 1 A16b 之后独立 change
- **不做** A16a lat/lon signed int32（已由 `fix-parser-signed-int-decoding` 闭环）
