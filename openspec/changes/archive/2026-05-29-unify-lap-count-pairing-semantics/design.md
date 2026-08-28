# Design: unify-lap-count-pairing-semantics

## 背景与数据派生链路（证据）

本节把 proposal 的"三套配对 + 一套计数"用代码证据展开，作为后续决策的依据。所有行号已 grep 核实（2026-05-30，分支 `feature/track-tech-v2`）。

### 站点 A · endSession 持久化 lapCount

`core/data/.../repository/TelemetryRepository.kt:164-168`：

```kotlin
val acceptedSF = crossings
    .filter { it.gateType.equals("StartFinish", ignoreCase = true) && it.accepted }
    .sortedBy { it.crossingTimestampMs }                                  // ← GPS 协议时钟
val durations = acceptedSF.zipWithNext { a, b -> b.crossingTimestampMs - a.crossingTimestampMs }  // ← GPS 协议时钟
Triple(topSpeed, durations.size, durations.minOrNull())                   // lapCount = durations.size
```

### 站点 B · deriveDetailMetrics 圈列表（用户点击源）

`feature/test/.../ui/tracktech/LapSessionDetailScreen.kt:471-495`：

```kotlin
val sf = crossings
    .filter { it.gateType.equals("StartFinish", ignoreCase = true) }
    .sortedBy { it.crossingTimestampMs }                                  // ← GPS 协议时钟
val acceptedSF = sf.filter { it.accepted }
val durations = acceptedSF.zipWithNext { a, b -> b.crossingTimestampMs - a.crossingTimestampMs }  // ← GPS 协议时钟
...
durations.forEachIndexed { idx, dur -> records += UiLapRecord(lapNumber = idx + 1, ...) }  // 1-based 行号
```

UI 渲染 `Text(text = "Lap ${record.lapNumber}", ...)`（`LapSessionDetailScreen.kt:349`）。

### 站点 C · getLapTelemetry 单圈切片（点击打开源）

`core/data/.../repository/TelemetryRepository.kt:275-280`：

```kotlin
val acceptedSF = crossings
    .filter { it.gateType.equals("StartFinish", ignoreCase = true) && it.accepted }
    .sortedBy { it.crossingWallClockTimestampMs ?: Long.MAX_VALUE }        // ← 接收侧真壁钟
if (lapIndex < 0 || lapIndex + 1 >= acceptedSF.size) return null
val lapStartWallClock = acceptedSF[lapIndex].crossingWallClockTimestampMs ?: return null     // ← wallClock 窗口
val lapEndWallClock = acceptedSF[lapIndex + 1].crossingWallClockTimestampMs ?: return null
```

切片窗口 `readLapSamples(file, lapStartWallClock, lapEndWallClock)` 用 wallClock 截 binary samples（binary `absoluteTsMs = entity.startTs + tsDeltaMs`，亦是接收侧壁钟域，见 `getLapTelemetry` L288）。

### 站点 D · finishActiveLapSession Snackbar 计数（仅显示）

`feature/test/.../viewmodel/TestSessionViewModel.kt:567-568`：

```kotlin
val validLaps = sessionSnapshot?.completedLaps.orEmpty().filter { it.qualityFlags.isEmpty() }
val lapCount = validLaps.size
```

`completedLaps: List<LapRecord>`（`feature/test/.../model/laptiming/LapSession.kt:12`），`LapRecord.qualityFlags: List<LapQualityFlag>`（`LapRecord.kt:14`）。这是 in-memory 实时态，不读 Room crossing 表，不参与"点哪一圈"导航。

### 分歧本质

**两个时钟域不单调一致**（两者关系见 archived `lap-data-readers` spec line 166-168）：
- `crossingTimestampMs`：GPS 协议时间，`RaceChronoParser` 解码 = `mod 3,600,000`（每小时回绕）+ `hourStartMillis` 切换；失锁/重同步会让 `hourStartMillis` 跳变。
- `crossingWallClockTimestampMs`：接收侧 `System.currentTimeMillis()`（`TestSessionViewModel.kt:925` 写入），与 binary samples `absoluteTsMs` 同时钟域；可能为 null（§8.3 migration 之前旧 row）。

数据完整且不跨整点回绕时，两套排序产生同一顺序（收敛）。但跨整点回绕 / 失锁重同步 / 旧 row null wallClock 混入时，两套排序产生**不同的圈顺序与配对**。此时：
- B（GPS 时钟排序）给用户展示的 "Lap N" 行
- C（wallClock 排序）按 `lapIndex = N - 1` 取到的切片

**指向不同物理圈** → 点击打开错圈。这就是本 round 要根除的 production correctness bug。

---

## Decision 1：选 `crossingWallClockTimestampMs` 作为 A/B/C 三站点唯一权威圈配对 key（而非 `crossingTimestampMs`）

### 选择

A/B/C 三站点全部按 `crossingWallClockTimestampMs` 排序 + 配对（C 不变；A、B 从 GPS 时钟改为 wallClock）。复用 C 已确立的 `?: Long.MAX_VALUE`（null 排末尾）+ "前缀连续 null 段 / 后缀连续非空段"假设。

### Alternatives

**方案 A（选中）· 统一到 wallClock。** A/B 对齐到 C 的 wallClock key。
- rationale：
  1. **C 不能改成 GPS 时钟**——getLapTelemetry 的窗口 `lapStartWallClock/lapEndWallClock` 必须用 wallClock 去截 binary samples（samples `absoluteTsMs` 是接收侧壁钟）。若 C 改用 `crossingTimestampMs` 截窗，archived `lap-data-readers` spec line 182-184 已证明会"跨时钟域必命中 0 帧"，让 telemetry 切片空。所以 C 的 key 是**被 binary 时钟域硬约束锁死的**，不可移动。
  2. 既然 C 不可移动，唯一能产生"同源"的方向就是把 A、B 移到 C 的 wallClock key。
  3. wallClock 是单调实时壁钟，**没有 GPS 协议时钟的整点回绕（mod 3,600,000）问题**，作为圈编号排序键更稳健（GPS 时钟跨整点会让相邻圈的 `crossingTimestampMs` 出现 `b < a` 的负 duration，archived spec / 历史 round 已多次踩此坑）。
- 拒绝其他方案的理由见下。

**方案 B（拒绝）· 统一到 `crossingTimestampMs`（GPS 协议时钟）。** 把 C 改成用 GPS 时钟排序 + 截窗。
- 拒绝理由：直接破坏 getLapTelemetry 的 binary 窗口截取（跨时钟域 0 帧，archived `lap-data-readers` spec line 182-184 已锁死此为"MUST NOT 实现形态"）。这会让本 round 反而引入一个比当前更严重的回归（telemetry 切片全空），且违反公共数据契约稳定性。**直接出局**。

**方案 C（拒绝）· 引入新的"配对 ID"字段（如 `CrossingEventEntity.lapPairingId`）让三站点共享。**
- 拒绝理由：(1) 需要 Room schema migration（加字段 + version bump），直接命中"强制升级 medium 流程"的 5 个例外场景之一，违背本 round small/加速通道定位；(2) 配对 ID 仍需一个排序 key 来生成，问题只是被推后，没有消除时钟域选择；(3) 既有数据没有该字段，历史 session 仍需 fallback 派生 → 复杂度爆炸。过度工程。

**方案 D（拒绝）· 维持现状 + 仅在 UI 层做"行号→lapIndex 映射表"补偿。** detail 屏渲染时记录每行对应的 wallClock，点击时按 wallClock 反查 getLapTelemetry 的 lapIndex。
- 拒绝理由：(1) 不解决 A（持久化 lapCount）与 B/C 的配对口径分歧，Records 列表数字仍可能错；(2) 把"同源"变成 UI 层易碎的间接映射，未来任何新消费方（多圈比较屏 / 视频叠加）都要重新实现这套映射，**违背"单一权威配对"的根治目标**——这正是 proposal 要消除的"多套独立实现"病根。half-fix。

### 取舍

方案 A 是唯一同时满足 (a) 不破坏 binary 窗口截取 (b) 不引入 schema migration (c) 真正单源 三条的方案。代价：A、B 站点的 lapCount/圈编号语义从 GPS 时钟改为 wallClock，**在 null wallClock 旧 row 上口径会变化**——但这是正确方向（C 已是 wallClock，A/B 跟上才同源），且旧 row（§8.3 migration 之前）已是历史尾部数据，新 session 全部写非空 wallClock。

---

## Decision 2：null wallClock 处理规则——A/B 完全复用 C 的"排末尾 + 不参与有效配对"

### 选择

A（endSession）、B（deriveDetailMetrics）排序时用 `sortedBy { it.crossingWallClockTimestampMs ?: Long.MAX_VALUE }`（null 排末尾），与 C 完全一致。配对时，当某 crossing 的 wallClock 为 null，则它落在排序末尾，其参与的相邻 duration 会跨越 null（与 C 的 `acceptedSF[lapIndex].crossingWallClockTimestampMs ?: return null` 语义保持一致：null 圈在 C 中返回 null = "该圈不可读"）。

为保证 A/B/C 三站点对"可读圈"集合的判断完全一致，A、B 在 duration 派生时 **MUST** 仅对"起止 crossing 的 wallClock 均非空"的相邻对计 duration；任一端为 null 的相邻对不计入有效圈（与 C 的 early-return null 行为收敛）。

### Alternatives

**方案 A（选中）· 完全复用 C 的 null 处理（排末尾 + null 端不计有效圈）。**
- rationale：C 是权威站点，A/B 跟随才能保证"B 第 N 行存在 ⟺ C 第 N 圈可读"。统一 null 语义是"同源"的必要条件，否则 B 可能列出一个 C 取不到的圈（点击空白）。
- 实现：A/B 排序后，对 zipWithNext 的每对 `(a, b)`，仅当 `a.crossingWallClockTimestampMs != null && b.crossingWallClockTimestampMs != null` 才计 duration（用 wallClock 减法）。

**方案 B（拒绝）· A/B 对 null wallClock fallback 到 `crossingTimestampMs`。**
- 拒绝理由：违反 archived `lap-data-readers` spec line 161-168 锁定的"NULL wallClock MUST NOT fallback 到协议时间"跨时钟域安全约束。会让 A/B 把一个 C 取不到（返回 null）的圈也算进圈列表/lapCount → 用户点击空圈，且重新引入跨时钟域污染。直接违反既有 normative。

**方案 C（拒绝）· A/B 遇到任何 null wallClock 就整体回退到 GPS 时钟排序。**
- 拒绝理由：(1) "混合 null"场景下整批退回 GPS 时钟会让 B/C 在同一 session 上分歧（C 永远 wallClock，B 退回 GPS），正是要消除的分歧；(2) archived `lap-data-readers` spec line 30-32 已锁定本数据契约的 wallClock 模式假设是"前缀连续 null 段 + 后缀连续非空段"，无需整批退回。over-engineering 且制造新分歧。

### 取舍

方案 A 保证三站点对"圈集合"判断逐圈一致。代价：纯 null wallClock 的历史 session（§8.3 migration 之前），A/B 的 lapCount/圈列表会变为 0（与 C 一致——C 本就对全 null 返回 null）。这是**正确的对齐**（旧数据本就无法打开 telemetry），不是回归；且通过 risks R3 的真机/日志兜底监控。

---

## Decision 3：站点 D（Snackbar qualityFlags 计数）的处置——保留计算、加 normative 约束其用途边界（不纳入 pairing 统一）

### 选择

**不改** `finishActiveLapSession` 的 qualityFlags 计数计算逻辑。但在 spec 中加 normative：D 的输出是"实时显示计数"（display count），**MUST NOT** 被用作"打开第 N 圈"的 `lapIndex` 来源；任何圈导航（detail 屏点击 / 未来多圈比较）的 lapIndex **MUST** 来自 B（同 A/C 的 wallClock 配对身份）。

### Alternatives

**方案 A（选中）· 保留 D 计算 + normative 约束用途边界。**
- rationale：
  1. D 用 qualityFlags 过滤是**实时反馈的合理设计**——用户跑完一圈，Snackbar 立即告诉他"几个有效圈"，排除作废圈是有用的实时语义。强行让 D 也走 crossing 表 wallClock 配对，会丢失 qualityFlags 信息（crossing 表无该字段），降级实时反馈质量。
  2. 真正的 bug 是"点击打开错圈"，根因在 B/C 排序分歧，**与 D 无关**。D 是计数差异（显示数字不同），不是配对身份差异。
  3. 用 normative 显式锁死"D 不得作为 lapIndex 来源"可关闭 half-closure（proposal 承诺统一"圈语义"，但若完全不提 D，子 agent/Codex review 会质疑"Snackbar 数字与列表数字仍不一致算不算半闭环"）。本决策把 D 的角色明确为"display-only count"，让 scope 完整闭合而非假装统一。

**方案 B（拒绝）· 把 D 也改成读 crossing 表按 wallClock 配对（与 A/B/C 完全统一一个数字）。**
- 拒绝理由：(1) `finishActiveLapSession` 是 in-memory 实时路径，此时 crossing 可能尚未全部 flush 到 Room（endSession 才落库扫描），改成查 Room 会引入"Snackbar 等 IO"的时序耦合与延迟；(2) 丢失 qualityFlags 过滤 → 作废圈会被算进 Snackbar，实时反馈变差；(3) 显著扩大改动面（ViewModel 数据流 + 时序），违背 small 定位。得不偿失。

**方案 C（拒绝）· 让 A/B 也加 qualityFlags 过滤向 D 对齐（统一到"有效圈"口径）。**
- 拒绝理由：crossing 表**没有 qualityFlags 字段**（`CrossingEventEntity` 仅有 accepted/reason/directionScore），A/B 在 Room 侧无法做 qualityFlags 过滤。要做必须给 crossing 表加字段 → Room schema migration → 强制升级 medium，违背 small 定位。且 archived `persisted-session-summary` spec line 232 已 normative 锁定"endSession 实现内 qualityFlags 零命中"——改 A 加 qualityFlags 会直接违反既有 normative。

### 取舍

方案 A 把 scope 收口为"配对身份统一（A/B/C）+ D 用途边界 normative"，既根治了点击错圈，又不破坏实时反馈、不引入 schema 改动。代价：Snackbar 计数与 Records 列表 lapCount 在"有作废圈"时仍可能显示不同数字——这是**有意保留的设计**（实时有效圈数 vs 持久化 accepted 配对数），并由 normative 明确两者用途不同，不是遗漏。

---

## Decision 4：B（deriveDetailMetrics）是 private 函数，测试可达性方案

### 选择

`deriveDetailMetrics` 当前为 `LapSessionDetailScreen.kt` 内的 `private fun`（L471）。为让本 round 的"B 用 wallClock 配对"可被单测锁死，将其改为 `internal fun`（仅放宽到 module 内可见，不暴露为 public API），在 `feature/test` module 的 test source 下加纯函数测试。

### Alternatives

**方案 A（选中）· `private` → `internal`，加 module 内单测。**
- rationale：`deriveDetailMetrics` 是纯函数（输入 `List<TelemetryCrossingEvent>` → `DetailMetrics`），无 Compose/Android 依赖，天然可单测。`internal` 是最小可见性放宽（同 module 可测，不污染公共 API）。与既有惯例一致（`TestSessionViewModel.lapIndexForCrossing` 即 `internal fun`，见 ViewModel L146）。
- caveat：`DetailMetrics` / `UiLapRecord` / `UiLapStatus` 也是 private，测试需访问。可将这几个数据类同步放宽到 `internal`，或测试仅断言 `lapRecords.map { it.lapNumber to it.timeMs }` 等可经 internal 访问的字段。

**方案 B（拒绝）· 把 deriveDetailMetrics 抽到独立 top-level 纯函数文件（如 `LapDetailMetricsDeriver.kt`）。**
- 拒绝理由：(1) 抽函数会牵动 import + 调用点重构，超出 small 改动面；(2) 增加新文件 + 新 kt-format 逃课注释负担；(3) `internal` 已足够可测，无需搬家。over-refactor，违反 scope boundary（不顺手重构）。

**方案 C（拒绝）· 不测 B，仅靠真机路测兜底 B 的排序键。**
- 拒绝理由：本 round 核心交付就是"B/C 同源"，若 B 的排序键改动无单测锁死，road-test-first 模式下唯一防线只剩真机——而"跨时钟域排序分歧"恰恰是真机偶发、难复现的场景，无单测则回归无法被持续捕获。违反 v3 盲点 #2（假绿门槛）。MUST 有单测。

### 取舍

方案 B（`internal` + 纯函数测试）保证 B 排序键改动有断言锁死，可见性放宽最小。代价：需把少量 private 数据类放宽到 internal——可接受（仍不出 module）。

---

## Risks

| 风险 | 说明 | Mitigation |
|---|---|---|
| **R1 · 改 A/B 排序键引入新跨时钟域回归** | 把 endSession/deriveDetailMetrics 从 `crossingTimestampMs` 减法改成 `crossingWallClockTimestampMs` 减法，若漏掉某处仍用旧 key（如 duration 减法用 wallClock 但排序用 GPS），会在 per-pair 上退化成跨时钟域减法（v3 盲点 #9） | spec 加 grep gate：`endSession`/`deriveDetailMetrics` 函数体内对 accepted SF 配对路径 **MUST NOT** 出现裸 `crossingTimestampMs`（仅 wallClock）。tasks 加实测 grep 验证。**注意 endSession 仍保留 `crossingTimestampMs` 字段用于其他用途的可能性需逐行确认**——实测确认 endSession 配对路径内仅 wallClock。 |
| **R2 · null wallClock 端的有效圈判断与 C 不一致** | 若 A/B 对 null 端的相邻对仍计 duration（而 C 对 null 端 return null），B 会列出 C 取不到的圈 → 点击空圈 | Decision 2 锁定 A/B 仅对"起止均非空"的相邻对计 duration；spec 加反例 scenario：含 null wallClock 旧 row 时 B 列出的圈数 == C 可读圈数。 |
| **R3 · 纯 null wallClock 历史 session 的 lapCount 归零** | §8.3 migration 之前的旧 session 全 null wallClock，改后 A/B 的 lapCount/圈列表变 0（与 C 一致） | 这是**正确对齐**（旧数据本就无法打开 telemetry），非回归。FileLogger 在 endSession 派生路径埋点记录 "wallClock null count / accepted SF count / 最终 lapCount"，真机路测可 adb pull 核对旧 session 行为；spec 透明声明此为 expected 行为。 |
| **R4 · deriveDetailMetrics 可见性放宽影响编译** | `private` → `internal` 若漏改关联数据类可见性导致测试访问不到 | tasks 明确列出需同步放宽到 internal 的数据类（DetailMetrics / UiLapRecord / UiLapStatus），编译通过即验证。 |
| **R5 · D（Snackbar）计数差异被误判为本 round 未闭环** | review 时可能质疑"为什么 Snackbar 数字仍和列表不一致" | Decision 3 + spec normative 显式声明 D 是 display-only count、不参与圈导航 lapIndex；scenario 锁死"D 计数 != A/B 配对数"在有作废圈时为 expected 而非 bug。 |
| **R6 · 改 endSession 影响既有持久化测试 baseline** | `TelemetryRepositoryEndSessionPersistTest` 现有 case 用 `crossingTimestampMs`（无 wallClock）喂数据，改 key 后这些 case 的 lapCount 可能变 0（因 wallClock 全 null） | tasks 要求：既有 case **MUST** 补 `crossingWallClockTimestampMs` 字段（与 crossingTimestampMs 同序），保持原断言意图；新增 case 单独锁 wallClock 配对 + null 反例。避免既有 case 静默退化成"全 null → lapCount 0"假绿。 |

---

## FileLogger 埋点计划（road-test-first 强制）

> **模块边界约束（apply 期 #11 自查已坐实）**：`FileLogger` 是 `com.blazepush.feature.test.FileLogger`（`feature/test` module）。`core/data` module 仅 `implementation(project(":core:domain"))`，**依赖方向是 `feature/test → core/data`，core/data 无法 import feature/test**。因此站点 A（`TelemetryRepository.endSession`，在 `core/data`）**不能直接埋 `FileLogger.d`**。埋点 MUST 落在 `feature/test` 层（`TestSessionViewModel` 已大量使用 `FileLogger`，见 ViewModel L16 import + L398/L442/L452）。

本 round 在以下"数据派生 / 降级路径"埋点（落 `filesDir/debug_log.txt`），供真机攒批路测诊断：

1. **endSession 派生结果**（埋在 `feature/test` 的 `TestSessionViewModel.finishActiveLapSession`，它在 L572 调 `telemetryRepository.endSession(sessionId)` 落库）：endSession 返回后读回 `telemetryRepository.getSession(sessionId)` 的 `lapCount`（或在 finishActiveLapSession 已有上下文）记 `FileLogger.d("LapPairing", "finishLap sid=$sessionId snackbarValid=$lapCount persistedLapCount=$persisted key=wallClock")` —— 同时记录站点 D（Snackbar valid 计数）与站点 A（持久化 wallClock 配对数），真机路测核对两口径差异 + 旧 session null wallClock 行为（R3 / R5）。
2. **detail 屏配对派生**（埋在 `feature/test` 的 `LapSessionDetailScreen` `LaunchedEffect(crossings)` 派生 derived 后）：`FileLogger.d("LapPairing", "detail sid=$sessionId validLaps=${derived.validLaps} wallClockNull=${crossings.count { it.accepted && it.gateType=='StartFinish' && it.crossingWallClockTimestampMs == null }} key=wallClock")` —— 记录 B 站点圈列表有效圈数与 null wallClock 数，真机点击前核对列表与可读圈一致（R2）。

注：站点 A（`core/data` endSession）本身**不埋** FileLogger（模块边界），其派生正确性由 `feature/test` 层日志（读回 persistedLapCount）+ §4/§5 单测双重兜底。具体埋点 call site 在 apply 期落地，本 design 列锚点。

---

## 与当前路线图的协同关系

- 本 round 是路线图 §1.2 集成点 2 + 交织线 2 明确标注的 "lapIndex 语义对齐风险" 的根治，是 `lap-detail-screen-with-cursor`（M2 里程碑）的**前置硬依赖**：detail 屏点击 "Lap N" 调 `getLapTelemetry(sessionId, N-1)` 必须取到同一圈。
- 看板 §6 协同：本 round 改 `TelemetryRepository.endSession` + `LapSessionDetailScreen.deriveDetailMetrics`。路线图 §4 标注本 round 与线 A `redesign-realtime-delta-projection-search` 都涉及 session lifecycle / ViewModel 区域，**但函数级实际不交叉**（本 round 不碰 `RealtimeDeltaCalculator` / `LapLiveScreen` / ViewModel 的 delta 路径；redesign-delta 不碰 endSession / deriveDetailMetrics）。开工前 MUST 看板 §6 实测确认无函数级重叠（本 design 的证据：本 round 独占路径 = `endSession` L154-178 + `deriveDetailMetrics` L471-515，均不在 redesign-delta 触碰集内）。
- 本 round **不**预言 detail 屏点击如何把 lapNumber 映射到 lapIndex 的 UI 实现（那是 `lap-detail-screen-with-cursor` 的 scope）；本 round 仅保证"B 的圈编号定义与 C 的 lapIndex 定义同源"这一数据契约，使 detail 屏可安全地用 `lapIndex = lapNumber - 1`。

## 不并入其他 round 的理由

- **不并入 `lap-detail-screen-with-cursor`**：该 round 是 UI 组屏（medium），若把"配对同源"塞进去，会让一个 UI round 同时改 `core/data` 持久化派生语义 + UI 组屏，混合两个不相关 functional unit，违反"按独立功能点隔离"。且配对同源是 detail 屏的**前置正确性基础**，应先独立 land + 测试锁死。
- **不并入 `redesign-realtime-delta-projection-search`**：D（Snackbar）与 delta 计算都在 ViewModel，但本 round 不改 D 计算、不碰 delta；两者功能点正交。
