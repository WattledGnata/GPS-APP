# Tasks: unify-lap-count-pairing-semantics

> 复杂度 small · 加速通道（road-test-first 模式：CC 单遍自审 + FileLogger 埋点 + 真机攒批；去 Codex / 去 Opus 子 agent）。
> apply 启动前 MUST 跑 v3 盲点 #3（grep 锚点对齐）/ #14（fake DAO 漏 abstract）/ #16（跨 round 共享字段 drift）三项自查（见 §1）。

## 1. 协同看板 + apply 期自查（#3/#14/#16）

- [x] 1.1 看板 §5/§6 核对：本 round 独占路径 = `core/data/.../TelemetryRepository.kt:endSession`（L154-178）+ `feature/test/.../ui/tracktech/LapSessionDetailScreen.kt:deriveDetailMetrics`（L471-515）。确认与线 A `redesign-realtime-delta-projection-search` **函数级不交叉**（redesign-delta 改 `RealtimeDeltaCalculator/RealtimeDeltaState/LapLiveScreen` DELTA tile，不碰 endSession/deriveDetailMetrics；本 round 不碰 delta 路径）。§6 登记本 round 占用。done condition：看板登记完成 + 函数级无重叠确认
- [x] 1.2 **#3 grep 锚点对齐自查**（worktree 内实测，行号以实测为准）：
  - `grep -n "fun endSession\|sortedBy\|zipWithNext\|crossingTimestampMs\|crossingWallClockTimestampMs\|durations" core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt` —— 核实 endSession 配对路径当前在 L164-168（`sortedBy { it.crossingTimestampMs }` + `zipWithNext { ... crossingTimestampMs }`）
  - `grep -n "fun deriveDetailMetrics\|sortedBy\|zipWithNext\|crossingTimestampMs\|lapNumber" feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapSessionDetailScreen.kt` —— 核实 deriveDetailMetrics 在 L471-515（`sortedBy { it.crossingTimestampMs }` L474 + `zipWithNext` L478-480 + `lapNumber = idx + 1` L489）
  - done condition：实测行号与本 tasks 描述一致（偏移则更新本 tasks 锚点）
- [x] 1.3 **#14 fake DAO 漏 abstract 自查**：本 round **不改** `CrossingEventDao` / `TelemetrySessionDao` 接口签名（无新 abstract 方法）。`grep -n "interface CrossingEventDao\|interface TelemetrySessionDao" core/data/src/main/java/com/blazepush/core/data/local/dao/*.kt` 确认接口未变。done condition：确认无 DAO 接口签名变化 → #14 不触发（所有既有 fake DAO 无需补 stub）
- [x] 1.4 **#16 跨 round 共享字段 drift 自查**：本 round **不新增/不修改** `LapTelemetrySample` / `CrossingEventEntity` / `TelemetrySessionEntity` 任何共享 entity 字段（仅改派生路径排序键）。done condition：确认无共享字段扩展 → #16 不触发；本 round 是消费既有 `crossingWallClockTimestampMs`（W1 fix-lap-crossing-clock-hygiene round 已加的 v4→v5 字段）的对齐 round，不扩展字段

## 2. 实现层 A：endSession 改 wallClock 配对

- [x] 2.1 在 `core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt` 的 `endSession`（L154-169 withContext 块内）把 acceptedSF 排序 + durations 派生从 `crossingTimestampMs` 改为 `crossingWallClockTimestampMs`：
  ```kotlin
  val acceptedSF = crossings
      .filter { it.gateType.equals("StartFinish", ignoreCase = true) && it.accepted }
      .sortedBy { it.crossingWallClockTimestampMs ?: Long.MAX_VALUE }
  val durations = acceptedSF.zipWithNext { a, b -> a to b }
      .mapNotNull { (a, b) ->
          val sa = a.crossingWallClockTimestampMs
          val sb = b.crossingWallClockTimestampMs
          if (sa != null && sb != null) sb - sa else null
      }
  Triple(topSpeed, durations.size, durations.minOrNull())
  ```
  done condition：endSession 配对路径 0 引用裸 `crossingTimestampMs`（仅 wallClock）；spec「lapCount 派生用 wallClock 配对」scenario + 两个反例 scenario 对应的单测（§4）绿
- [x] 2.2 更新 `endSession` 上方 KDoc（L120-142）：把"lapCount 派生语义：accepted=true && gateType=StartFinish 的 crossing 相邻配对数量"补一句配对键已统一为 `crossingWallClockTimestampMs`（与 getLapTelemetry 同源）+ 删除/修订旧注释里"与 Snackbar in-memory 路径语义差异作为 follow-up unify-lap-count-semantics"（本 round 即该 follow-up，改为指向本 round + 链 spec ADDED requirement）。done condition：KDoc 与实现一致，无悬空 follow-up 引用
- [x] 2.3 **不在 `core/data` endSession 内埋 FileLogger**（模块边界硬约束：`FileLogger` 是 `com.blazepush.feature.test.FileLogger`，`core/data` 不能 import `feature/test`，依赖方向是 `feature/test → core/data`）。endSession 派生正确性由 §2.4 的 `feature/test` 层日志 + §4/§5 单测兜底。done condition：确认 endSession 实现内 0 引用 FileLogger（不制造非法反向依赖）
- [x] 2.4 埋 FileLogger 于 `feature/test` 层（road-test-first 强制 · 落在可见模块）：
  - 在 `feature/test/.../viewmodel/TestSessionViewModel.kt:finishActiveLapSession`（L561-586，已有 `import com.blazepush.feature.test.FileLogger` L16）：`telemetryRepository.endSession(sessionId)`（L572）返回后，读回持久化 lapCount（`telemetryRepository.getSession(sessionId)?.lapCount`）记 `FileLogger.d("LapPairing", "finishLap sid=$sessionId snackbarValid=$lapCount persistedLapCount=$persisted key=wallClock")`
  - 在 `feature/test/.../ui/tracktech/LapSessionDetailScreen.kt` 的 `LaunchedEffect(crossings)` 派生 `derived` 后（L83 附近）记 `FileLogger.d("LapPairing", "detail sid=$sessionId validLaps=${derived.validLaps} key=wallClock")`（确认该屏可引用 FileLogger——同 module 可用；若该屏当前无 FileLogger import 则加 import）
  done condition：finishActiveLapSession + detail 屏派生路径各有 FileLogger.d 记录配对 key + 站点 A/B/D 计数，真机可 adb pull 诊断 R2/R3/R5

## 3. 实现层 B：deriveDetailMetrics 改 wallClock 配对 + 可见性放宽

- [x] 3.1 在 `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapSessionDetailScreen.kt` 的 `deriveDetailMetrics`（L471-495）把 `sortedBy { it.crossingTimestampMs }`（L474）改为 `sortedBy { it.crossingWallClockTimestampMs ?: Long.MAX_VALUE }`，并把 durations 减法（L478-480）改为仅对"起止 wallClock 均非空"的相邻对用 wallClock 减法（同 §2.1 mapNotNull 形态）。done condition：deriveDetailMetrics accepted SF 配对路径 0 引用裸 `crossingTimestampMs`；`lapNumber = idx + 1` 基于 wallClock 排序的有效相邻对
- [x] 3.2 `deriveDetailMetrics` 从 `private fun` 改为 `internal fun`（L471）；同步把 `DetailMetrics`（L463）/ `UiLapRecord` / `UiLapStatus`（L461）从 `private` 放宽到 `internal`（仅 module 内可见，测试可达；不暴露 public API）。done condition：编译通过 + `feature/test` test source 可访问 deriveDetailMetrics 与返回类型字段
- [x] 3.3 确认 rejected SF 圈编号顺延逻辑（L496-504 `lapNumber = durations.size + idx + 1`）在 durations 改为 wallClock 有效对后仍正确（durations.size 现为 wallClock 有效配对数）。done condition：rejected 圈编号紧接有效圈之后，无重号/跳号

## 4. 单元测试 A：endSession wallClock 配对（既有套件扩展）

- [x] 4.1 在 `core/data/src/test/java/com/blazepush/core/data/repository/TelemetryRepositoryEndSessionPersistTest.kt` **修订既有 case**（防 R6 假绿）：`endSession derives lapCount from accepted SF crossing pairs`（L124-166）+ `endSession derives bestLapMs as min duration`（L168-196）的 `TelemetryCrossingEvent` 构造 **MUST 补 `crossingWallClockTimestampMs`** 字段（与 `crossingTimestampMs` 同序同值，保持原断言意图：lapCount=3 / bestLapMs=1100）。done condition：既有 case 喂 wallClock 非空数据，断言不变仍绿（否则改 key 后 wallClock 全 null → lapCount 退化为 0 假绿）
- [x] 4.2 新增 case `endSession lapCount uses wallClock ordering not gps clock`：构造 3 个 accepted SF crossing，`crossingTimestampMs` 序与 `crossingWallClockTimestampMs` 序**不同**（GPS 序 c1<c2<c3，wallClock 序 c2<c3<c1，对应 spec「反例——MUST NOT 用 crossingTimestampMs」scenario）。断言 endSession 派生的 bestLapMs == 按 wallClock 排序手算 min（若误用 crossingTimestampMs 排序则 fail）。done condition：case 绿且在误用 GPS 时钟排序时 fail
- [x] 4.3 新增 case `endSession null wallClock pairs not counted`：5 个 accepted SF，前 2 个 wallClock=null、后 3 个非空（5000/6100/7200）。断言 `entity.lapCount == 2`（仅后 3 非空 zipWithNext 2 对；对应 spec「反例——含 null wallClock 不计有效圈」scenario）。done condition：case 绿
- [x] 4.4 同步更新 `TelemetryRepositoryEndSessionPersistTest` 顶部 KDoc 覆盖契约列表（L30-36）加"6. endSession lapCount 用 wallClock 排序配对（跨时钟域分歧 + null wallClock）"。done condition：KDoc 与新增 case 一致

## 5. 单元测试 B：deriveDetailMetrics + 跨站点同源

- [x] 5.1 新建测试文件 `feature/test/src/test/java/com/blazepush/feature/test/ui/tracktech/LapDetailMetricsDeriveTest.kt`（首行预加 `// @IgnoreFormatCheck`）。测纯函数 `deriveDetailMetrics`（已放宽 internal）：
  - case `lap numbers use wallClock ordering`：4 个 accepted SF wallClock 升序 → 圈列表 Lap 1/2/3，lapNumber 基于 wallClock 序
  - case `gps clock divergence does not change lap pairing`：GPS 序 ≠ wallClock 序时圈列表按 wallClock 序配对（对应 spec「跨时钟域排序分歧」scenario）
  - case `null wallClock pairs excluded from lap list`：含 null wallClock crossing 时有效圈列表 == 非空 wallClock 配对数
  done condition：3 case 绿，断言 `lapRecords.filter { it.status in [VALID, BEST] }.map { it.lapNumber }` 序列与 wallClock 配对一致
- [x] 5.2 新建跨站点同源测试 `core/data/src/test/java/com/blazepush/core/data/repository/LapPairingCrossSiteConsistencyTest.kt`（首行 `// @IgnoreFormatCheck`）：用 fake DAO + 真 BinaryTelemetryWriter（同 `TelemetryRepositoryEndSessionPersistTest` 套件惯例 L43-60）。构造一个 session（accepted SF wallClock 配对 N 圈 + binary 覆盖整 session），断言：
  - case `endSession lapCount equals getLapTelemetry readable count`：`entity.lapCount`（站点 A）== 依次调 `getLapTelemetry(sid, 0..)` 直到首次 null 的可读圈数（站点 C）
  - case `getLapTelemetry lapIndex maps to same physical lap as endSession pairing`：`getLapTelemetry(sid, k)` 的 (lapStartWallClock, lapEndWallClock) == endSession 配对路径手算第 k 对的 (wallClock_k, wallClock_{k+1})（A/C 同源，对应 spec「圈配对身份同源不变式」）
  - case `cross clock divergence A and C still agree`：GPS 序 ≠ wallClock 序时 A/C 仍指向同一圈（反例锁死：若 A 用 GPS 时钟则 A/C 分歧 → fail）
  done condition：3 case 绿
  - **测试边界 caveat（v3 盲点 #11/#10）**：`deriveDetailMetrics` 在 `feature/test` module、`getLapTelemetry/endSession` 在 `core/data` module，跨 module 不能同一 test class 同时引用两边。故 B 站点（deriveDetailMetrics）测试放 `feature/test` test source（§5.1）；A/C 同源测试放 `core/data` test source（§5.2）。"B 与 C 同源"由"B 与 A 用同一排序键（spec normative + §5.1 验 B 排序键）+ A 与 C 同源（§5.2 验）"传递保证，spec「圈编号 ↔ lapIndex 映射契约」+ 「圈配对身份同源不变式」normative 锁死三站点统一 key

## 6. grep gate（防回归 · 锁死 MUST 用 wallClock）

- [x] 6.1 endSession 配对路径无裸 GPS 时钟：`grep -nE 'crossingTimestampMs' core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt` 命中行 **MUST NOT** 落在 `endSession` 函数体（L154-178）内的 accepted SF 排序/duration 路径（其他位置如 `getLapTelemetry` 的反例约束、`toDomain` 映射的字段透传允许保留）。done condition：endSession withContext 块内对 accepted SF 配对 0 引用裸 `crossingTimestampMs`
- [x] 6.2 deriveDetailMetrics 配对路径无裸 GPS 时钟：`grep -nE 'crossingTimestampMs' feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapSessionDetailScreen.kt` —— deriveDetailMetrics（L471-515）函数体内对 accepted SF 排序/duration 0 引用裸 `crossingTimestampMs`。done condition：deriveDetailMetrics 0 引用裸 `crossingTimestampMs`
- [x] 6.3 wallClock 排序键命中：`grep -nE 'crossingWallClockTimestampMs \?: Long.MAX_VALUE' core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt` 命中 **≥ 2 次**（getLapTelemetry L277 既有 1 次 + endSession 新增 1 次）。done condition：≥ 2 次命中

## 7. 编译 + 单测

- [x] 7.1 `./gradlew :core:data:testDebugUnitTest --tests "*TelemetryRepositoryEndSessionPersistTest*" --tests "*LapPairingCrossSiteConsistencyTest*"` 全绿。done condition：含 §4 / §5.2 全部 case 绿
- [x] 7.2 `./gradlew :feature:test:testDebugUnitTest --tests "*LapDetailMetricsDeriveTest*"` 全绿。done condition：§5.1 case 绿
- [x] 7.3 全 module 编译不破（可见性放宽 internal 后 `LapSessionDetailScreen` 调用点不受影响）：`./gradlew :core:data:compileDebugKotlin :feature:test:compileDebugKotlin`。done condition：编译通过

## 8. 真机 gate（攒批 · road-test-first）

- [ ] 8.1 本 round 无独立可见 UI 改动（圈编号配对是数据正确性，UI 渲染形态不变）；真机验证**攒批**到 `lap-detail-screen-with-cursor` 落地后一起验证"点击 Lap N 打开正确圈"端到端。本 round 阶段：adb pull `debug_log.txt` 核对 endSession `LapPairing` 日志（acceptedSF / wallClockNull / lapCount / key=wallClock）。done condition：真机跑一个含多圈 session，日志显示 lapCount 配对 key=wallClock 且与圈列表行数一致；告知 user 当前 round/apk/场景等授权 install
- [ ] 8.2（攒批）detail 屏落地后：真机点击 Records 列表某 session → detail 屏点 "Lap 2" → 验证打开的曲线/轨迹是第 2 圈（与圈列表对应）。done condition：跨时钟域真实数据下点击不开错圈

## 9. metrics.yaml（归档前 · road-test-first 模式字段）

- [ ] 9.1 归档时写 `openspec/changes/archive/<date>-unify-lap-count-pairing-semantics/metrics.yaml`：`complexity: small` / `review_mode: "road-test-first"` / `review_rounds_l1: 0` / `review_rounds_l2: 0` / `codex_l1_findings: []` / `codex_l2_findings: []`（注 road-test-first 去 Codex）/ `design_decisions_diverged_during_apply: []`（透明声明无 drift）/ `cross_round_field_drift_resolved: []` / FileLogger 埋点锚点摘要（endSession LapPairing 日志）。done condition：metrics.yaml 字段齐全

## 10. Follow-up backlog

- **10.1 lap-detail-screen-with-cursor 消费本 round 契约**：detail 屏点击 "Lap N" MUST 用 `lapIndex = lapNumber - 1` 调 `getLapTelemetry`（本 round spec「圈编号 ↔ lapIndex 映射契约」normative）。本 round 不实现 UI 点击映射，仅锁数据契约同源。link：本 round `specs/persisted-session-summary/spec.md` ADDED requirement「detail 屏圈列表按 wallClock 配对」。
- **10.2 getLapTelemetry 缺 live spec**：getLapTelemetry 的 normative 仅存在于 archived `lap-data-readers` / `unify-perftest-anchor-cross-clock` 的 delta（`openspec/specs/` 无 `lap-telemetry-readers` live capability）。本 round 在 `persisted-session-summary` 加跨站点同源不变式时引用了 getLapTelemetry 的 wallClock 排序契约——future 若 sync getLapTelemetry delta 进 live spec（`/opsx:sync`），MUST 保持本 round 同源不变式与 getLapTelemetry spec 一致。建议 follow-up `sync-lap-telemetry-readers-to-live-spec`（trivial）。
- **10.3 Snackbar vs 持久化计数 UI 提示（可选 P3）**：当前 Snackbar valid 计数与 Records lapCount 在有作废圈时不同（本 round normative 明确为 expected）。若 user 反馈"两个数字不一致让人困惑"，可 future round 在 UI 加提示文案（如 Snackbar "2 valid / 3 recorded laps"）。本 round 不做（scope = 配对身份同源，非 UI 文案）。
- **10.4 null wallClock 交错模式（非前缀/后缀）revisit**：本 round 与 getLapTelemetry 同款假设 wallClock 模式为"前缀连续 null + 后缀连续非空"。若 future 引入"writeCrossing wallClock 写失败但 crossingTimestampMs 成功"路径让生产数据出现交错 null（[w1, null, w3, null]），需 revisit 三站点 sort 策略（参 archived `lap-data-readers` spec tasks §10.8）。本 round 不处理交错（生产 trigger guard 前提下罕见）。
