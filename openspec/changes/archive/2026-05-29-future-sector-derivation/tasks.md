# Tasks: future-sector-derivation

> 复杂度 small（< 200 行 + 1 module + 无 schema 改），但因 **#16 共享契约填充语义扩展**（sectorBoundaries 恒单段→可多段）+ user 显式要求，**保留一轮 L1 adversarial review**（其余按 road-test-first：FileLogger 兜底 + 真机攒批 / SKIP）。
> apply 启动前 MUST 跑 v3 盲点 #3（grep 锚点对齐）/ #14（fake DAO 漏 abstract）/ #16（跨 round 共享字段 drift）三项自查（见 §1）。

## 1. 协同看板 + apply 期自查（#3/#14/#16）

- [x] 1.1 看板 §5/§6 核对：本 round 独占路径 = `core/data/.../repository/TelemetryRepository.kt:getLapTelemetry`（L284-321，仅第 317 行 sectorBoundaries 派生段）+ `core/data/.../repository/LapTelemetryReadersTest.kt`（补 case）。确认与未闭环 round **函数级不交叉**：`getLapTelemetry` 与 endSession（`unify-lap-count` 已合回）/ deleteSession（J round 已合回）函数级不重叠；detail 屏 round（`lap-detail-screen-with-cursor`，未启动）若已开始须 §6 登记 sectorBoundaries 消费耦合（路线图 §3 标"本 round 宜先于或并入 detail 屏"）。§6 登记本 round 占用 `getLapTelemetry`。done condition：看板登记完成 + 函数级无重叠确认
- [x] 1.2 **#3 grep 锚点对齐自查**（实测，行号以实测为准）：
  - `grep -n "fun getLapTelemetry\|crossingDao.queryBySessionId\|sectorBoundaries = listOf\|crossingWallClockTimestampMs\|gateType" core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt` —— 核实 (a) `crossings = crossingDao.queryBySessionId(sessionId)` 当前在 L286；(b) StartFinish filter 在 L288（`it.gateType.equals("StartFinish", ignoreCase = true) && it.accepted`）；(c) lap 窗口 lapStartWallClock/lapEndWallClock 在 L291-292；(d) `sectorBoundaries = listOf(lapStartWallClock)` 在 L317
  - done condition：实测行号与本 tasks 描述一致（偏移则更新本 tasks 锚点）
- [x] 1.3 **#14 fake DAO 漏 abstract 自查**：本 round **不改** `CrossingEventDao` / `TelemetrySessionDao` 接口签名（不新增 @Query / abstract 方法——sector 派生复用既有 `queryBySessionId`）。`grep -n "interface CrossingEventDao" core/data/src/main/java/com/blazepush/core/data/local/dao/CrossingEventDao.kt` 确认接口未变。done condition：确认无 DAO 接口签名变化 → #14 不触发（既有 fake DAO 无需补 stub）
- [x] 1.4 **#16 跨 round 共享字段 drift 自查**（本 round 核心）：
  - 重跑 `grep -rn "sectorBoundaries" feature/ core/`（排除 `.worktrees/`），复核消费方表与 design Decision 4 一致，**无新增消费方**在本 round 立项后冒出（重点查 detail 屏 round 是否已开始消费）
  - 逐项 verify design Decision 4 表：SectorBar（W2 fc0afc1，多段设计，单段是退化）/ LapAlignment（W3 a0cbfb7，grep `sectorBoundaries` 在 `LapAlignment.kt` 0 命中，不读该字段）/ MockTelemetry（W2，已 3 元素多段）
  - done condition：确认本 round 是 #16 良性扩展（无 round 假设 size==1）；若发现新消费方假设单段 → 暂停 apply 走 design 修订（F1 #17）

## 2. 实现层：getLapTelemetry 派生多段 sectorBoundaries

- [x] 2.1 在 `core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt` 的 `getLapTelemetry`（L284-321），在 `LapTelemetry(...)` 构造前（L310 `return LapTelemetry(` 之前）插入 sector 派生（复用 L286 已读的 `crossings` + L291-292 的 `lapStartWallClock`/`lapEndWallClock`）：
  ```kotlin
  val sectorWallClocks = crossings
      .filter {
          it.gateType.equals("Sector", ignoreCase = true) &&
              it.accepted &&
              it.crossingWallClockTimestampMs != null
      }
      .mapNotNull { it.crossingWallClockTimestampMs }
      .filter { it >= lapStartWallClock && it < lapEndWallClock && it != lapStartWallClock }
      .sorted()
  val sectorBoundaries = listOf(lapStartWallClock) + sectorWallClocks
  ```
  把 L317 `sectorBoundaries = listOf(lapStartWallClock)` 改为 `sectorBoundaries = sectorBoundaries`（用上面派生的变量）。done condition：派生段对应 spec Requirement「数据源/filter/时钟域/窗口/去重首项/排序组装/首元素不变式/回退」8 条；空 sectorWallClocks 时退化为 `listOf(lapStartWallClock)`（回退单段）
- [x] 2.2 更新 `getLapTelemetry` 上方 KDoc（L275-283）：补一句 sectorBoundaries 派生语义 = "lap 窗口 [lapStart, lapEnd) 内 accepted Sector 过线 wallClock 升序前置 lapStart；无 sector 过线回退单段 listOf(lapStart)"。done condition：KDoc 与实现一致 + 与 `LapTelemetry.kt:26` 头注释"首项 == lapStart"不冲突
- [x] 2.3 确认 `LapTelemetry.sectorBoundaries` 字段类型（`core/domain/.../model/LapTelemetry.kt:39` `List<Long>`）不需改动（仍是 wallClock 列表，只是填充值从单段变多段）。done condition：core/domain 零改动

## 3. 单元测试：sector 派生（既有套件扩展）

- [x] 3.1 在 `core/data/src/test/java/com/blazepush/core/data/repository/LapTelemetryReadersTest.kt` 的 `crossingEvent` helper（L67-68，当前签名 `gateType="StartFinish"` 写死）**扩展**支持 sector：加 `gateType: String = "StartFinish"` 参数（默认 StartFinish 保持既有 case 不破）。done condition：既有 case A-L 全绿不受影响（helper 默认值向下兼容）
- [x] 3.2 新增 case `case M - multi sector derives multi-element sectorBoundaries`：构造 1 圈窗口（2 个 accepted SF wallClock）+ 2 个 accepted Sector crossing wallClock 落窗口内 + binary 覆盖窗口。断言 `getLapTelemetry(sid, 0).sectorBoundaries == listOf(lapStart, s1, s2)`（3 元素，升序，首项==lapStart）。对应 spec「完整圈派生多段」scenario。done condition：case 绿
- [x] 3.3 新增 case `case N - no sector falls back to single element`：1 圈窗口 + **无** Sector crossing。断言 `sectorBoundaries == listOf(lapStart)`（单元素回退）+ `getLapTelemetry` 非 null。对应 spec「无 sector 回退单段」scenario。done condition：case 绿
- [x] 3.4 新增 case `case O - out of window sector excluded`：2 圈窗口（圈0 [w0,w1] / 圈1 [w1,w2]）+ 4 个 accepted Sector（2 个落圈0 / 2 个落圈1）。断言 `getLapTelemetry(sid, 0).sectorBoundaries` 仅含圈0 窗口内 sector（圈1 的 2 个 MUST NOT 混入）+ `getLapTelemetry(sid, 1)` 仅含圈1 sector。对应 spec「反例——窗口外 sector MUST NOT 混入」scenario。done condition：case 绿且误取全部 sector 时 fail
- [x] 3.5 新增 case `case P - rejected and null-wallClock sector excluded`：窗口内 3 个 Sector：accepted+wallClock 有效 / accepted=false（rejected）/ wallClock=null。断言 `sectorBoundaries == listOf(lapStart, validSectorWc)`（仅有效项）。对应 spec「rejected / null-wallClock 被排除」scenario。done condition：case 绿
- [x] 3.6 新增 case `case Q - sector wallClock equal to lapStart deduped`：窗口内 2 个 accepted Sector，1 个 wallClock==lapStart（退化）/ 1 个正常。断言 `sectorBoundaries == listOf(lapStart, normalSectorWc)`（无重复 lapStart）。对应 spec「sector wallClock 恰等于 lapStart 去重」scenario。done condition：case 绿
- [x] 3.7 新增 case `case R - clock domain guard - uses wallClock not gps clock`：窗口内 1 个 accepted Sector，`crossingWallClockTimestampMs` 落窗口内但 `crossingTimestampMs` 落窗口外。断言 sector 仍被纳入（用 wallClock 判定）+ `sectorBoundaries == listOf(lapStart, sectorWc)`。对应 spec「反例——MUST NOT 用 crossingTimestampMs」scenario（锁死跨时钟域）。done condition：case 绿且误用 crossingTimestampMs 判窗口时 fail（sector 被错排除）
- [x] 3.8 既有 `case A`（L84 `assertEquals(r.lapStartWallClock, r.sectorBoundaries.first())`）**MUST 继续绿**——本 round 派生后首元素仍 == lapStart。若 case A 当前无 sector crossing，派生后仍是单元素 `[lapStart]`，断言不变。done condition：case A 不需修改即绿
- [x] 3.9 更新 `LapTelemetryReadersTest` 顶部 KDoc 覆盖契约列表，加"sector 派生：多段 / 回退单段 / 窗口排除 / rejected+null 排除 / 去重 / 跨时钟域 guard"。done condition：KDoc 与新增 case 一致

## 4. grep gate（防回归 · 锁死 MUST 用 wallClock + accepted）

- [x] 4.1 sector 派生用 wallClock 时钟域：`grep -nE 'gateType.equals\("Sector"' core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt` 命中 1 次（新增派生段）；且该派生段附近 `grep -nE 'crossingWallClockTimestampMs' ...` 命中（sector filter + 窗口判定用 wallClock）。done condition：sector 派生段用 `crossingWallClockTimestampMs`，不用 `crossingTimestampMs` 做 sector 窗口判定/取值
- [x] 4.2 跨文件逃逸 gate（v3 #8）：`grep -rn 'gateType.equals("Sector"' core/data/src/main --include=*.kt | grep -v '/.worktrees/'` 仅命中 `TelemetryRepository.kt` 1 文件 1 次（防扫错路径假绿 + 防 sector 派生散落多处）。done condition：恰 1 文件 1 命中
- [x] 4.3 回退语义 gate：派生段 `grep -nE 'listOf\(lapStartWallClock\)' core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt` 命中（回退路径保留 `listOf(lapStartWallClock)` 作为 sectorBoundaries 首元素前置）。done condition：命中（确认回退/前置语义存在）

## 5. 编译 + 单测

- [x] 5.1 `./gradlew :core:data:testDebugUnitTest --tests "*LapTelemetryReadersTest*" --offline` 全绿（含既有 case A-L + 新增 case M-R）。done condition：全部 case 绿，0 fail
- [x] 5.2 `./gradlew :core:data:compileDebugKotlin --offline` 编译通过（core/domain 零改动，无需 recompile domain）。done condition：编译通过
- [x] 5.3 全链路回归：`./gradlew :core:data:testDebugUnitTest --offline` 全套（确认 sector 派生不破坏 endSession / getDataPointsForResult / 其他 reader case）。done condition：core/data 全套 0 fail

## 6. 真机 gate（攒批 · road-test-first）

- [x] 6.1 本 round 是纯 core/data 数据层 reader，**无独立 UI**，真机验证**攒批**到 `lap-detail-screen-with-cursor` 落地后一起验证"SectorBar 画多段"端到端（detail 屏才有 SectorBar 渲染）。本 round 阶段：真机 **SKIP**（纯数据层；逻辑由 §3 单测锁死）。done condition：明确 SKIP 理由（纯数据层无 UI），detail 屏 round 承接 SectorBar 多段真机验证（含小屏 vivo V2405A gate——SectorBar 是 V2 视觉组件）
- [ ] 6.2（攒批 · deferred 到 detail 屏 round）detail 屏落地后：真机在 TFIC 跑含完整 sector 的 session → detail 屏点圈 → 验证 SectorBar 画 3 段（Sector 1/2/3）+ 游标落段高亮正确。done condition：见 §10.1 backlog

## 7. metrics.yaml（归档前）

- [ ] 7.1 归档时写 `openspec/changes/archive/<date>-future-sector-derivation/metrics.yaml`：`complexity: small` / `review_mode: "road-test-first"`（注：含一轮 L1 adversarial review 因 #16 契约改 + user 显式要求）/ `review_rounds_l1: 1` / `review_rounds_l2: 0` / `codex_l1_findings: []` / `codex_l2_findings: []`（road-test-first 去 Codex）/ `review_findings_l1: [...]`（填 L1 子 agent 发现）/ `design_decisions_diverged_during_apply: []`（透明声明无 drift）/ `cross_round_field_drift_resolved: ['LapTelemetry.sectorBoundaries (lap-data-readers→future-sector-derivation)']`（#16 本 round 解决的共享字段填充语义扩展）/ FileLogger 埋点锚点摘要（本 round reader 无 FileLogger，注模块边界 + 诊断策略 = 返回值 self-describing）。done condition：metrics.yaml 字段齐全

## 8. （预留空白，对齐 mirror 结构编号）

## 9. （预留空白，对齐 mirror 结构编号）

## 10. Follow-up backlog

- **10.1 lap-detail-screen-with-cursor 消费 + 埋点 + 真机**：detail 屏 `LaunchedEffect` 调 getLapTelemetry 后 MUST 埋 `FileLogger.v("SectorDeriv", "lap=$lapIndex sectorBoundaries.size=${lt.sectorBoundaries.size}")`（本 round reader 因 core/data 模块边界无法埋，诊断埋点 deferred 到 feature/test 的 detail 屏 round）；并真机在 TFIC 验证 SectorBar 画多段 + 小屏 vivo V2405A 不换行。link：本 round design「FileLogger 计划」段。
- **10.2 getLapTelemetry 缺 live spec**：getLapTelemetry 的 normative 仅存在于 archived `lap-data-readers` / `unify-perftest-anchor-cross-clock` 的 delta（`openspec/specs/` 无 `lap-telemetry-readers` live capability）。本 round 新建 `lap-telemetry-sector-derivation` capability 描述 sectorBoundaries 派生契约——future 若 sync getLapTelemetry 主契约进 live spec（`/opsx:sync`），MUST 把本 capability 与 getLapTelemetry 主 spec 关联（sectorBoundaries 是 getLapTelemetry 返回字段）。建议 follow-up `sync-lap-telemetry-readers-to-live-spec`（trivial）。与 `unify-lap-count` round §10.2 同一缺口，合并处理。
- **10.3 博裕 loop / 未来赛道 sector 门定义**：当前仅 TFIC 预置赛道定义 sector 门（s1/s2）；博裕 loop（extraPresetTracks）若未定义 sector 门 → 该赛道所有圈回退单段（design Decision 5 已声明，数据真实情况非 bug）。future 若需博裕 loop 也分段，需在 `extraPresetTracks` 给它定义 sectorGates（属赛道资源扩展，与路线图 §7 `track-source-expansion` future 占位关联）。本 round 不补造赛道 sector 门。
- **10.4 sector boundary 同时携带 sectorId/sectorName（可选 P3）**：当前 sectorBoundaries 仅 wallClock 列表，SectorBar 画段但段无名字（Sector 1/2/3 是位置序号非门名）。future 若需 SectorBar 显示门名（"S1 弯前" 等），需把 sectorBoundaries 从 `List<Long>` 升级为 `List<SectorBoundary>`（含 gateId + wallClock）——这是 `LapTelemetry` 公共契约的类型级改动（命中 #16 + 强制升级 medium），MUST 独立立项。本 round 仅做 wallClock 列表（与现有 SectorBar 契约对齐，不改类型）。
