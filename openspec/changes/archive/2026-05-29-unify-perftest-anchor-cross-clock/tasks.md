## 1. 协同看板登记 + worktree 准备

- [x] 1.1 阅读看板 §5/§6 核对：`core/data/.../TestResultRepository.kt` + `LapTelemetryReadersTest.kt` 当前无并行 round 占用（H round 独占 `feature/test`，零交叉）
- [x] 1.2 看板 §5 登记本 round：`unify-perftest-anchor-cross-clock`（Phase 1 Tier1.5），状态"推进中"
- [x] 1.3 创建 worktree：`git worktree add .worktrees/unify-perftest-anchor-cross-clock -b feature/unify-perftest-anchor-cross-clock feature/track-tech-v2`
- [x] 1.4 **apply 期 #3 自查**：worktree 内实测 grep 锚点对齐——`grep -n "getDataPointsForResult\|dataFilePath.isEmpty\|testStartWallClock" core/data/src/main/java/com/blazepush/core/data/repository/TestResultRepository.kt`，确认 `getDataPointsForResult` 函数体 + `if (entity.dataFilePath.isEmpty()) return null` 行号（ff 期主区实测：函数 line 143、isEmpty guard line 145；worktree rebase 后可能偏移，以实测为准）

## 2. 实现层加 sentinel guard

- [x] 2.1 在 `TestResultRepository.kt:getDataPointsForResult` 的 `if (entity.dataFilePath.isEmpty()) return null` 之后、`telemetryRepository.readPerformanceSamples(...)` 调用之前，插入一行：
  ```kotlin
  if (entity.timestamp == Long.MIN_VALUE) return null
  ```
  done condition：guard 位置在 metadata 校验段（dataFilePath.isEmpty 之后），且在任何 binary IO（readPerformanceSamples）之前；不 per-sample 重复判断
- [x] 2.2 确认 guard 之后 `val testStartWallClock = entity.timestamp` 仍是原逻辑（guard 只是提前 return，不改 testStartWallClock 派生）

## 3. spec 增量同步

- [x] 3.1 本 round delta spec `specs/lap-telemetry-readers/spec.md` 已含更新后 Requirement「PERFORMANCE_TEST 完整 dataPoints 切片读取」（sentinel guard normative + invariant 三条款 + sentinel 反例 scenario）—— ff 期已写，apply 期 verify 与实现代码一致
- [x] 3.2 把同样的 normative 增量同步进归档 spec `openspec/changes/archive/2026-05-04-lap-data-readers/specs/lap-telemetry-readers/spec.md`：
  - 在 Requirement 2 实现 MUST 列表的 `dataFilePath 为空字符串 → 返回 null` 之后加 sentinel guard normative 条目
  - 把 line 76 `absoluteTsMs = testStartWallClock + sample.tsDeltaMs（§8.4/M anchor 已对齐）` 的 `（§8.4/M anchor 已对齐）` 替换为引用本 round 显式 invariant 三条款（或直接内联三条款摘要 + link 本 round spec）
  - done condition：归档 spec 不再有 "unargued assertion"，与现实代码 + 本 round delta 一致

## 4. 单元测试 case L

- [x] 4.1 在 `core/data/src/test/java/com/blazepush/core/data/repository/LapTelemetryReadersTest.kt` 加 case L（现有 A-J 共 10 → 11）：
  ```kotlin
  @Test
  fun `case L - getDataPointsForResult sentinel entity timestamp returns null`() = runTest {
      // entity.timestamp = Long.MIN_VALUE（GPS 未同步 sentinel）+ dataFilePath 非空
      // → reader 返回 null + 0 次 readPerformanceSamples 调用
  }
  ```
- [x] 4.2 case L 功能性断言（防 v3 盲点 #7 grep gate trivially pass）：**spec drift 修订**——原计划 `verify(exactly = 0)` mockk 调用次数断言，但 `LapTelemetryReadersTest` 用真 fake DAO + 真 `TelemetryRepository`（非 mockk），无法 mockk-verify。改用更强的功能性断言：注入**有效 binary 文件（100 帧可读）+ sentinel timestamp**，断言 `assertNull(result)`——无 guard 时该 binary 读出 100 帧返回非 null，断言 null 即等价证明 guard 截断正常读取路径。guard 的"在 IO 之前"由 code-position（紧随 dataFilePath.isEmpty 之后）保证。spec.md sentinel scenario 已同步修订
- [x] 4.3 对齐现有 test 的 fixture 构造惯例（真 FakeTestRecordDao / 真 TelemetryRepository / fakeSpeedSegmentDao）——读 case F（`getDataPointsForResult normal path`）的 setup 复用，仅改 `timestamp = Long.MIN_VALUE`

## 5. 编译 + 单测

- [x] 5.1 worktree 内 `./gradlew :core:data:testDebugUnitTest --tests "*LapTelemetryReadersTest*"`（11 cases 全绿，含新 case L）
- [x] 5.2 worktree 内 `./gradlew :core:data:testDebugUnitTest`（core/data 全套零回归）
- [x] 5.3 `./gradlew :core:data:compileDebugKotlin`（编译确认）

## 6. 真机验证

- [x] 6.1 **SKIP**：本 round 纯数据层 defensive guard，无 UI 改动，无真机验证场景（sentinel 生产 0 触发，单测是唯一验证路径）。在 metrics.yaml 透明声明 SKIP 理由

## 7. commit + 合回 + Codex L2 + 归档

- [x] 7.1 worktree 内 commit：`fix(perftest): getDataPointsForResult 加 entity.timestamp sentinel guard 防跨时钟域 absoluteTsMs 崩塌`
- [x] 7.2 ff-only 合回主区 `feature/track-tech-v2`
- [x] 7.3 主区编译确认（`:core:data:testDebugUnitTest`）
- [ ] 7.4 **加速通道 L2**：提醒 user 触发 Codex L2 单线兜底；Codex 反馈消化后继续
- [x] 7.5 写 metrics.yaml：`complexity: trivial` + `review_mode: accelerated` + `review_rounds_l1: 0` + `review_rounds_l2: 0` + `codex_l1_findings` / `codex_l2_findings` 填充 + `accelerated_escalation: null` + 透明声明"派生 follow-up round，因 deferred memo 已含 medium 级设计分析（3 alternatives + 数学误差范围）保留 trivial 加速通道，user 拍板"
- [ ] 7.6 归档为 `archive/2026-05-29-unify-perftest-anchor-cross-clock`（含 proposal/design/specs/tasks/metrics.yaml + Codex L2 反馈 trail）
- [x] 7.7 看板 §5 状态改 done；deferred memo backlog #8 标 disposition done；清理 worktree
- [ ] 7.8 **需用户显式确认才能 push**

## 8. follow-up backlog（不在本 round 实现）

- [ ] 8.1 `migrate-perftest-timestamp-to-wallclock`（P3）—— 方案 B：把 `TestResult.timestamp` 迁移到本地壁钟域根本解决跨时钟域。**触发条件**：若未来 chart 在长 session（>10min）上出现 GPS-UTC 漂移可见错乱（invariant 2），或频繁 GPS 失锁周期切换导致 hourStartMillis 跳变（invariant 3）。当前数学分析证明漂移 < 5 帧无影响，不启动
