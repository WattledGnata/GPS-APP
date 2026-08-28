# A16 战役 D 尾巴：altitude 编解码三方不一致复核申请

- **日期**：2026-04-24
- **申请方**：haozhang93 session（claude）
- **复核方**：codex session
- **起因**：准备起草 A16 的 change proposal 时，深挖 RP22 @Ignore 注释的合理性，意外发现 altitude 字段在 **ESP32 ino 编码 / 协议文档 / parser 解码**三方不一致，且**当前生产环境实际在用错误公式**。申请 codex 独立复核，拍板真相源与 scope 决策。

---

## 0. 快速 TL;DR

| 子问题 | 类型 | 是否 production bug |
|---|---|---|
| **RP16 / RP19 (lat/lon 负值)** | 纯 parser 解码 bug（协议 + ino 都对，parser 错把 signed int32 当 uint32） | ✅ 影响南纬/西经 |
| **RP22 (altitude overflow)** | 协议文档 + parser 两方错公式（互相自洽），ino 是对的 | ✅ 影响**所有** altitude 字段 |

**待确认的核心问题**：

1. **altitude 三方不一致谁是真相源**？我的判断是 ESP32 ino（实际发送端），但希望 codex 独立验证后确认
2. **A16 scope 应如何切分**？全修 / 拆 parser 和 altitude 两个 change / 其他
3. **生产数据审计**：之前 app 看到的 altitude 都被错误解码，有无下游依赖需要回溯

---

## 1. 原始 attack-backlog A16 条目

文件：`docs/superpowers/reviews/attack-backlog.md`

```markdown
### A16：`RaceChronoParserTest` RP16 / RP19 / RP22 @Ignore 的 parser 真实 bug （战役 D 尾巴）

- 来源：D 战役 f869f27 commit message 自述
- 证据：RaceChronoParserTest.kt 中 RP16 / RP19 / RP22 三条 @Ignore
- 攻击点：signed int32 被 `toLong() and 0xFFFFFFFFL` 抹成 unsigned（经纬度负值场景）、
  altitude overflow bit 编码差异。测试断言正确、parser 实现有 bug。
- 核销条件：
  - (1) 独立 change（建议命名 `fix-parser-signed-int-decoding`）
  - (2) RP16 / RP19 / RP22 三条 @Ignore 去掉，断言全绿
  - (3) 新增若干边界测试：南纬、西经、海拔 > 3276.7m（overflow 分支）
```

---

## 2. RP16 / RP19 分析（lat/lon 负值）

### 2.1 测试代码

文件：`core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt:278-335`

```kotlin
@Test
@Ignore("暴露 parser 实际 bug：parseGpsData L164 `latInt.toLong() and 0xFFFFFFFFL` 把"
       + " signed int32 抹成 unsigned，负纬度解不回来。测试断言正确，实现有 bug。")
fun RP16_parseLatitude_negative() {
    val data = createValidGpsData20(latitude = -33.8688)  // 南纬
    val result = parser.parseGpsData(data, createTestData())
    assertEquals("南纬应为负值", -33.8688, result.latitude, 0.0001)
}

@Test
@Ignore("暴露 parser 实际 bug：parseGpsData L171 ... 与 RP16 同源，signed int32 被 unsigned 抹掉。")
fun RP19_parseLongitude_negative() {
    val data = createValidGpsData20(longitude = -122.4194)  // 西经
    val result = parser.parseGpsData(data, createTestData())
    assertEquals("西经应为负值", -122.4194, result.longitude, 0.0001)
}
```

### 2.2 三方对比

| 层 | 源码路径:行号 | 公式 / 表示 |
|---|---|---|
| ESP32 ino | `docs/RaceChrono_ESP32_M9N.ino:37-38, 291-292, 333-340` | `int32_t latitude`；编码：`(int32_t)(alt * 10000000.0)`；big-endian 4 字节 |
| 协议文档 | `docs/RaceChrono_BLE_Protocol.md:32-33, 47-48, 129-141` | int32 signed；解码：`alt = latInt / 10000000.0`（直接 Int 除法） |
| **parser 解码** | `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt:178, 185` | **`(latInt.toLong() and 0xFFFFFFFFL) / 10000000.0`** ❌ |

### 2.3 手算验证（alt = -33.8688°）

ino 编码：
- `(int32_t)(-33.8688 * 10_000_000) = -338_688_000` = `0xEBD8B9C0`（二进制补码）
- big-endian 字节序列：`EB D8 B9 C0`

parser 解码（当前 bug）：
- `latInt = 0xEBD8B9C0`（Kotlin Int 直接表示 `-338_688_000`）
- `latInt.toLong() = -338_688_000L`
- `-338_688_000L and 0xFFFFFFFFL = 3_956_279_296L`（Long 位掩码把负 signed 抹成正 unsigned）
- `3_956_279_296L / 10_000_000.0 = 395.6279`
- **结果：+395.63°** ❌（期望 -33.8688°）

parser 正解（修复后）：
- 删掉 `.toLong() and 0xFFFFFFFFL`
- `latInt / 10_000_000.0` 直接 Int→Double 除法保留符号
- `-338_688_000 / 10_000_000.0 = -33.8688`
- **结果：-33.8688°** ✓

### 2.4 结论

**RP16 / RP19 = 纯 parser 解码 bug**，ino 和协议文档都正确。修复：删掉 `toLong() and 0xFFFFFFFFL`，两行改动。

**请 codex 验证**：
1. ino 编码是否确实按 signed int32（二进制补码）big-endian 打包字节
2. parser 的 `(latInt.toLong() and 0xFFFFFFFFL)` 对负 latInt 的计算结果是否真的会丢符号

---

## 3. RP22 分析（altitude overflow）—— 三方不一致

### 3.1 测试代码

文件：`RaceChronoParserTest.kt:363-383`

```kotlin
@Test
@Ignore(
    "暴露 parser altitude overflow bit 编码的解析差异 (bit15=1 高位分支)。" +
        "当前 parseGpsData L175-181 实现与测试期望的 `raw/10` 约定不完全一致 " +
        "(代码是 `((raw & 0x7FFF) * 10) / 100`)。实际应该按哪个约定由协议文档认定。"
)
fun RP22_parseAltitude_overflow() {
    // Given: 海拔溢出场景 (altitude > 327.675m 需要 overflow bit)
    // 1600m + 500 = 2100, 2100 * 10 = 21000 = 0x5208
    // overflow: 0xD208 (overflow bit + high byte 0xD2, low byte 0x08)
    val data = createValidGpsData20(altitude = 100.0)  // 先创建基础数据
    data[12] = 0xD2.toByte()
    data[13] = 0x08.toByte()

    val result = parser.parseGpsData(data, createTestData())
    assertEquals("海拔溢出时应为1600", 1600.0, result.altitude, 0.1)
}
```

测试注释说 "1600 + 500 = 2100, 2100 * 10 = 21000 = 0x5208"—— 这是**按 parser/协议文档的错误公式**反推出来的 raw。

### 3.2 三方公式对比

#### bit15 = 0 分支（低海拔）

| 层 | 源码路径:行号 | 公式 |
|---|---|---|
| ESP32 ino 编码 | `docs/RaceChrono_ESP32_M9N.ino:294-295` | `raw = ((int)((alt + 500.0) * 10.0)) & 0x7FFF` |
| 协议文档解码 | `docs/RaceChrono_BLE_Protocol.md:96-97` | `alt = raw / 100.0 - 500.0` |
| parser 解码 | `RaceChronoParser.kt:189-191` | `(altRaw and 0x7FFF) / 100.0 - 500.0` |

#### bit15 = 1 分支（高海拔）

| 层 | 源码路径:行号 | 公式 |
|---|---|---|
| ESP32 ino 编码 | `docs/RaceChrono_ESP32_M9N.ino:296-297` | `raw = (((int)(alt + 500.0)) & 0x7FFF) \| 0x8000` |
| 协议文档解码 | `docs/RaceChrono_BLE_Protocol.md:98-99` | `alt = ((raw & 0x7FFF) * 10) / 100.0 - 500.0` |
| parser 解码 | `RaceChronoParser.kt:192-194` | `((altRaw and 0x7FFF) * 10.0) / 100.0 - 500.0` |

### 3.3 手算验证（alt = 100m，bit15=0 分支）

ino 编码：
- `raw = ((int)((100 + 500) * 10)) & 0x7FFF = 6000 & 0x7FFF = 6000`
- 字节：`0x17 0x70`

协议文档解码：
- `alt = 6000 / 100 - 500 = 60 - 500 = -440m` ❌（期望 100m）

parser 解码（跟协议文档一致）：
- `alt = (6000 & 0x7FFF) / 100 - 500 = 60 - 500 = -440m` ❌

**正确对称解码**（与 ino 编码逆运算）：
- `raw = (alt + 500) * 10 → alt = raw / 10 - 500`
- `alt = 6000 / 10 - 500 = 600 - 500 = 100m` ✓

### 3.4 手算验证（alt = 1600m，bit15=1 分支）

ino 编码：
- `raw_inner = (int)(1600 + 500) = 2100`
- `raw = (2100 & 0x7FFF) | 0x8000 = 0x834 | 0x8000 = 0x8834`
- 字节：`0x88 0x34`

协议文档解码：
- `alt = (0x8834 & 0x7FFF) * 10 / 100 - 500 = 2100 * 10 / 100 - 500 = 210 - 500 = -290m` ❌（期望 1600m）

parser 解码：
- 同上 `-290m` ❌

**正确对称解码**：
- `raw_inner = alt + 500 → alt = (raw & 0x7FFF) - 500`
- `alt = 2100 - 500 = 1600m` ✓

### 3.5 为什么 RP22 测试"看起来应该 pass"？

RP22 测试输入字节 `0xD2 0x08`，即 `altRaw = 0xD208`。按当前（错误的）parser 公式：
- `(0xD208 & 0x7FFF) * 10 / 100 - 500 = 0x5208 * 10 / 100 - 500 = 21000 * 10 / 100 - 500 = 2100 - 500 = 1600m`

测试期望也是 1600m —— 所以**看起来 pass**。

但这是**数据和公式都错，形成闭环自洽**。实际 ESP32 发 1600m 时字节是 `0x88 0x34`（不是 `0xD2 0x08`），parser 按错公式解会得到 `-290m`。

### 3.6 数学形式等价性证明（为什么 `raw*10/100` ≡ `raw/10`）

对任意整数 raw：
- `(raw * 10) / 100` 在 Double 运算下 = `raw / 10`
- 所以 parser 的 `* 10 / 100 - 500` 与协议文档的 `raw/10 - 500`（如果协议原本要写的是这个）数学等价

但**两者都和 ino 编码不对称**。ino bit15=1 编码是 `alt + 500`（不乘 10），解码应该直接 `raw - 500`，不是 `raw / 10 - 500`。

### 3.7 结论

**RP22 / altitude 两分支都是三方不一致 bug**：
- ESP32 ino 编码是**真相源**（实际发送端）
- 协议文档 + parser 都错了同一个公式（`/100` vs 应该 `/10`，`*10/100` vs 应该什么都不除）
- 生产影响：**所有 altitude 值都被错误解码**

正确解码公式（与 ino 对称）：
```kotlin
val altitudeMeters = if ((altRaw and 0x8000) == 0) {
    (altRaw and 0x7FFF) / 10.0 - 500.0     // bit15=0：raw = (alt+500)*10
} else {
    (altRaw and 0x7FFF).toDouble() - 500.0  // bit15=1：raw = alt+500
}
```

---

## 4. 核心待确认问题（请 codex 拍板）

### Q1：altitude 三方不一致，**真相源是谁**？

**我的判断**：ESP32 ino 是真相源（实际发送端）。协议文档应改为与 ino 一致，parser 应按 ino 编码对称解码。

**备选观点**：
- 也许协议文档是先写（作为 spec），ino 和 parser 都没按协议文档实现，两者碰巧错得不同方向
- 可能 ino 是最早按 RaceChrono 商业 app 的实际协议逆向实现的，本来就是真相
- 或者协议文档是后补的，写的时候作者推错公式

**请 codex 验证**：
- 查 `git log docs/RaceChrono_BLE_Protocol.md` 和 `git log docs/RaceChrono_ESP32_M9N.ino` 的时间顺序 + 关联 commit message
- 对照 RaceChrono 商业 app 的实际协议（有文档能查吗？）
- 看 parser 早期 commit 是否曾经按 ino 的正确公式，后来误改（git blame parser altitude 两分支）

### Q2：A16 scope 如何切分

| 方案 | 内容 | 规模 | 风险 |
|---|---|---|---|
| **1. 完整修** | lat/lon signed（2 行）+ altitude 两分支（2 行）+ 协议文档修订 + RP22 重构 + 生产数据审计 | 中 | altitude 行为变更影响下游，需要审计下游 |
| **2. 只修 lat/lon** | RP16/RP19 修 parser 2 行 + 去 @Ignore；altitude 拆到新 change 独立处理 | 小 | A16 核销条件 (3) "海拔 > 3276.7m 边界测试" 未完成，需要在新 change 补 |
| **3. 只修 altitude** | 只修 altitude 三方对齐（先），lat/lon 推后 | 中 | 南纬/西经 production bug 继续存在 |
| **4. 完整拆三个 change** | parser-lat-lon / altitude-alignment / rp22-reconstruct 各自 | 大 | 开销大，但每个 change scope 最小 |

**我的建议**：方案 **2 + 独立 change**。

### Q3：altitude 生产数据审计

需要查：
1. `GpsData.altitude` 字段有哪些下游消费者（grep）
2. LapRecord 是否存 altitude、trajectory 是否有 altitude
3. UI 是否展示 altitude
4. 有无基于 altitude 的判定逻辑（例如 "speed vs altitude 约束"）

**请 codex 决定**：如果下游确实用到 altitude，修 parser 公式后，历史持久化数据（如果有）需不需要一起改？

---

## 5. 参考资料清单（按复核路径）

### 5.1 代码文件

| 文件 | 用途 |
|---|---|
| `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt` | parser 解码实现（lat/lon: line 178, 185；altitude: line 187-195） |
| `core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt` | RP16 (line 284) / RP19 (line 326) / RP22 (line 370) @Ignore 测试 |
| `docs/RaceChrono_ESP32_M9N.ino` | ESP32 固件，lat/lon 编码: line 291-292 + 333-340；altitude 编码: line 294-298 |
| `docs/RaceChrono_BLE_Protocol.md` | 协议文档，lat/lon: line 32-33 + 47-48 + 129-141；altitude: line 94-99 |

### 5.2 相关 attack-backlog 条目

`docs/superpowers/reviews/attack-backlog.md`：
- A16（本条目）—— line 133-142
- A17（战役 D 尾巴另一条，DI fallback，与本复核**独立**）

### 5.3 D 战役背景

- commit `f869f27`（D 战役）—— parser 40 测试回挖 + RP16/RP19/RP22 @Ignore 初始化
- D 战役评审 review：`docs/superpowers/reviews/2026-04-22-lap-timing-review.md`（如存在）

### 5.4 本次深挖的推导链

- 我的推理路径：先质疑 RP22 @Ignore 注释 → 验证 parser 数学 → 和测试期望自洽 → 疑 "D 战役误判"
- 然后深挖 ino → 发现 ino 编码与协议文档 + parser 公式不对称 → altitude 公式都错

---

## 6. 附录：数字化验证清单（codex 独立重算）

请 codex 独立代入以下数字，验证是否与我得到同样的结论：

### 6.1 lat/lon signed int32

| 输入 | ino 编码 | 当前 parser 解码 | 正解 parser 解码 |
|---|---|---|---|
| alt=0° | `0x00000000` | 0° | 0° |
| alt=-33.8688° | `0xEBD8B9C0` | **+395.63°** ❌ | -33.8688° ✓ |
| alt=-122.4194° | `0xB7EC2BA0` | **+304.22°** ❌ | -122.4194° ✓ |

### 6.2 altitude bit15=0（低海拔）

| alt | ino 编码 raw | 当前 parser 解码 | 正解 |
|---|---|---|---|
| -500m | 0 | -500 ✓ | -500 ✓ |
| 0m | 5000 | -450 ❌ | 0 ✓ |
| 100m | 6000 | -440 ❌ | 100 ✓ |
| 277.67m | 7776 | -422.24 ❌ | 277.6 ✓ |

（bit15=0 分支当前公式**完全错**，parser 对几乎所有海拔都返回负值）

### 6.3 altitude bit15=1（高海拔，仅 alt ≥ 6053.5m 触发）

| alt | ino 编码 raw | 当前 parser 解码 | 正解 |
|---|---|---|---|
| 6053.5m | 0x8CA3 | -156.57 ❌ | 6053 ✓ |
| 10000m | 0xA904 | -225.0 ❌ | 10000 ✓ |

### 6.4 奇怪观察

注意 ino line 294：`if (parsedGpsData.altitude < 6053.5) { bit15=0 编码 }`

这意味着**实际 ESP32 只对 alt < 6053.5m 才用 bit15=0**。但 bit15=0 的编码 raw 最大值 = (alt+500)*10 = (6053.5+500)*10 = 65535 > 32767（0x7FFF）。

所以当 alt 接近 6053.5m 时 raw 会溢出 0x7FFF 被 mask 截断 —— **ino 自身在 2776.7m < alt < 6053.5m 区间有编码 bug**（raw 被截断丢高位）。

这是**第四个问题**（ino 自身 bug），超出 A16 原始 scope，但与 altitude 编码链路强相关，请 codex 一并评估是否纳入本轮。

---

## 7. 请求 codex 的复核动作

1. ✅ **验证 lat/lon signed int32 bug**：复核 RP16/RP19 的 parser bug 分析（§ 2），确认修复就是删 `toLong() and 0xFFFFFFFFL`
2. ✅ **验证 altitude 三方不一致**：独立重算 § 3 的公式对比，确认 ino 是真相源、协议文档 + parser 都错
3. ⚠️ **拍板真相源**（Q1）：是否以 ino 为准
4. ⚠️ **拍板 scope**（Q2）：选方案 1 / 2 / 3 / 4
5. ⚠️ **生产数据审计决策**（Q3）：下游 altitude 消费者需不需要回溯
6. ⚠️ **ino 自身 bug**（§ 6.4）：是否纳入本轮

---

## 8. 申请方自评的答案

> 以下是我自己的答案，供 codex 参考，但**以 codex 复核结论为准**。

| 问题 | 申请方答案 |
|---|---|
| Q1 真相源 | ESP32 ino（实际发送端，运行时事实） |
| Q2 scope | 方案 2：A16 只修 lat/lon，altitude 拆独立 change `fix-altitude-encoding-tri-party-alignment` |
| Q3 生产数据审计 | 下游若有 altitude 依赖，需要在 altitude change 里补回溯（historical data 若持久化则单独处理） |
| § 6.4 ino 自身 bug | 纳入 altitude change 一并解决（因为相关联，但是单独 R 或 Non-goal 需要再决策） |

---

## 9. 我作为申请方保留一手信息

以下信息**不直接写进决策**，但 codex 复核时如有需要我可以补充：
- parser 代码改动后的测试验证方式（已有 RaceChronoParserTest / RaceChronoParserProtocolTimeTest 体系）
- ino 源码和实际 ESP32 固件是否真的同步（hardware 不在我手，不能直接抓真机字节）
- RaceChrono 商业 app 协议是否真的与本项目协议对齐（名字叫 RaceChrono 暗示兼容，但无公开规范）

---

**复核流程建议**：codex 拿此文档独立走一遍 §2 / §3 / §6.4 的数字验证 → 回答 Q1 / Q2 / Q3 / §6.4 四个决策点 → 返回结论给申请方，申请方再起 change proposal。

---

## 10. codex 复核结论（2026-04-24 回复）+ 手算修正

codex 复核后**核心判断不变**：cc 报高危合理，lat/lon 是真 parser bug、altitude 是更大的协议/发送端/parser/测试 **四方不一致**问题，拆独立 change 正确。但审计文档 §6 的三处手算 / 分支判断被挑出错误，以 codex 结论为准修正如下：

### 10.1 拍板结论（以 codex 复核为准）

| 问题 | 结论 |
|---|---|
| **Q1 真相源** | 以 **RaceChrono 官方 BLE DIY API + 实际发送端实现** 为准（官方 README 明确 lat/lon signed two's complement，altitude 公式也与本地 ESP32 ino 方向一致）。参考：RaceChrono BLE DIY README |
| **Q2 scope** | **方案 2 + 更明确**：A16 只修 RP16/RP19 lat/lon signed int32；RP22/altitude 另起独立高优先级 change `fix-altitude-encoding-contract-alignment`（命名由 codex 建议）。A16 原核销条件里 RP22 要**从 A16 拆出**，不能在没修 altitude 时强行盖 A16 全闭环 |
| **Q3 下游审计** | altitude 未参与 lap / 加速 / 过滤判定逻辑；主要是透传 / 平滑 / 插值 / 结果字段 / lap sample 字段（`DataSmoothing.kt:38`、`TestSessionViewModel.kt:405`）。生产行为风险 = "展示/记录/导出数据错误"，不是判圈错误。无持久化历史 session 时无需迁移；有历史记录时应标注旧 altitude 不可信，**不建议静默批量改** |
| **Q4 §6.4 ino 自身截断** | **纳入 altitude 独立 change**。alt ∈ [2776.8m, 6053.4m] 区间按当前 ESP32 ino 会被 `& 0x7FFF` 截断、**不可逆、parser 单边救不了**。必须作为 altitude change 的独立 Requirement 或明确 Non-goal 拍板 |

### 10.2 手算修正（原 § 6 三处错误）

#### 修正 1：lat = -33.8688° 的 ino 字节

- **原文档误写**：`0xEBD8B9C0`
- **codex 正解**：`0xEBD00800`
- 验算：`-33.8688 × 10_000_000 = -338_688_000` → 补码 `0xEBD00800`
- parser 当前错解结果 `+395.6279296°` 仍正确（精度更严格）

#### 修正 2：lon = -122.4194° 当前 parser 错解结果

- **原文档误写**：`+304.22°`
- **codex 正解**：`+307.0773296°`
- 验算：`-122.4194 × 10_000_000 = -1_224_194_000` → 补码 `0xB7084830`；`0xB7084830 and 0xFFFFFFFFL = 3_070_773_296L`；`3_070_773_296 / 10_000_000.0 = 307.0773296`
- 原 `+304.22°` 是心算错，结论"parser 抹掉 signed 符号"未变

#### 修正 3：alt = 1600m 对应 ino 的实际字节分支

- **原文档误判**：alt=1600m 走 bit15=1 分支，raw=0x8834
- **codex 正解**：alt=1600m **走 bit15=0 分支**（因为 `1600 < 6053.5`），raw = `(1600+500)*10 = 21000 = 0x5208`
- 当前 parser 解 0x5208（bit15=0 公式）：`21000 / 100 - 500 = 210 - 500 = -290m` ❌
- **RP22 的 0xD208 = 0x5208 | 0x8000** 是**测试手工构造的"错公式自洽"数据**，不是真 ESP32 对 1600m 的输出
- 结论修正：alt=1600m 在真机上当前 parser 解成 -290m 仍是 bug，但触发的是 bit15=0 分支，不是我原来说的 bit15=1

### 10.3 最终建议（codex）

1. **先开小 change 修 A16 lat/lon**：删 `RaceChronoParser.kt:178` 和 `:185` 的 unsigned mask（`.toLong() and 0xFFFFFFFFL`），恢复 signed int32 解码，解除 RP16/RP19 `@Ignore`
2. **然后单独做 altitude change**（`fix-altitude-encoding-contract-alignment`），把 `docs/RaceChrono_BLE_Protocol.md`、parser、`RaceChronoParserTest` helper、simulator generator、ESP32 ino 的 [2776.8m, 6053.4m] 截断问题一起拍契约

### 10.4 申请方对照自评差异

| 项 | 申请方原答案 | codex 结论 | 调整 |
|---|---|---|---|
| Q1 真相源 | "ESP32 ino" | "RaceChrono 官方 BLE DIY API + ino 实际实现" | codex 结论更严谨（官方协议是契约根，ino 是实现） |
| Q2 scope | 方案 2 | 方案 2 + 显式 "RP22 从 A16 核销条件拆出" | 接受，A16 核销条件 (3) "海拔 overflow 边界测试" 要移到 altitude change |
| Q3 审计 | "需看下游消费者" | "已审计，无判定依赖，展示/记录层风险" | 完成，风险可控 |
| §6.4 截断 | "纳入 altitude change" | "纳入 altitude change 作为独立 R 或 Non-goal 拍板" | 接受，留给 altitude change 决策 |
| 手算 | 3 处错误 | 上述修正 | 接受 |

---

## 11. 下一步动作（基于 codex 结论）

1. A16 change 只含 lat/lon：
   - 命名 `fix-parser-signed-int-decoding`（attack-backlog 原建议名）
   - Requirement 1：parser lat/lon signed int32 正确解码
   - Requirement 2：RP16 / RP19 去 @Ignore + 新增南纬/西经边界测试
   - **Non-goal**：RP22 / altitude / §6.4 截断 不在本 change
   - backlog A16 核销条件 (3) "海拔 overflow 边界测试" 显式移交 altitude change
2. altitude change 作为**下一轮战役**（完成 A16 后起）：
   - 命名 `fix-altitude-encoding-contract-alignment`
   - 覆盖 parser / 协议文档 / test helper / simulator / ino 五方对齐 + [2776.8m, 6053.4m] 截断决策
