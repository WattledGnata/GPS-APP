# fix-active-lap-distance-accumulator code review

## 0. TL;DR

不建议立即核销 A22 到 ✅。commit `0321190` 的主实现方向正确，UI 已改为读取 engine 字段，相关 unit tests 也能通过；但 tasks/spec 承诺的 6 条 engine path coverage 中，路径 (d) `no target gate` 的测试没有实际走到 no-target 分支，而是退化成 sector rejected 覆盖。这会让 no-target 分支未来回归时测试仍假绿。

## 1. Findings

### Finding 1 — [P2] no-target path 测试没有真正覆盖 no-target 分支

- **位置**：`feature/test/src/test/java/com/blazepush/feature/test/usecase/LapTimingEngineTest.kt:1548-1571`
- **问题**：`processSample_noTargetGate_carriesDistanceForward` 的测试名和 A22 spec/tasks 都要求覆盖路径 (d) `expectedGate(...) ?: return session.copy(samples = updatedSamples, activeLap = activeLapWithDistance)`。但当前测试注释明确承认“sector gate 仍存在 → 走 handleSectorCrossing rejected 路径而非 no-target”，最终只断言 distance 增量大于 baseline。这样即使实现把 no-target 分支改回 `session.copy(samples = updatedSamples)` 丢掉 `activeLapWithDistance`，这条测试也不会失败。
- **要求**：补一条真正触发 no-target 的测试。可选做法：
  - 用 0-sector 自定义 track 开圈后喂普通帧，使 `nextExpectedGateIndex = 1` 且 `track.sectorGates` 为空，`expectedGate(...)` 返回 null。
  - 或手动构造 active lap session，设置 `nextExpectedGateIndex > track.sectorGates.size`，并保证 start-finish 不 accepted。
  - 断言返回 session 的 `samples` 追加当前帧，且 `activeLap.distanceMetersSinceStart` 相对 baseline 增加。

## 2. Verified

- `openspec validate fix-active-lap-distance-accumulator --strict` PASS（实施方汇报，review 前 artifacts 已通过）。
- `./gradlew :feature:test:testDebugUnitTest --tests "*LapTimingEngineTest*" --tests "*LapDebugExecutionScreenStateTest*"` PASS。
- UI 源码中 `calculateDistanceSince`、`private fun haversineDistanceMeters`、`samples.zipWithNext`、`samples.filter { ... timestampMillis ... }` 均已清除。
- `distanceMetersSinceStart =` 的生产源码写入点仅在 `ActiveLap.kt` 和 `LapTimingEngine.kt`。
- A56 diff 新增行 grep 未命中 `@Entity` / `@Dao` / `@Database` / `RoomDatabase` / `chunkWrite` / `persistDistance` / `@Insert` / `@Query`。

## 3. Verdict

暂不核销 A22。修复 Finding 1 后可重提 mini review；无需 patches 清单。

## 4. Round 2 mini review

### 4.1 Finding closure

- Finding 1 closed：commit `a8e2377` 将 `processSample_noTargetGate_carriesDistanceForward` 重写为 `processSample_whenNextExpectedGateIndexExceedsSectorCount_routesThroughNoTargetGatePath`。
- 新测试通过 `session.copy(nextExpectedGateIndex = track.sectorGates.size + 1)` 构造 active lap 已穿完所有 sector 但未闭圈的状态，使 `expectedGate(...)` 真正返回 null，覆盖 `processSample` 的 no-target 早退路径。
- 新测试断言 no-target 路径下 `distanceMetersSinceStart` 增加、`crossingEvents` 不变、`nextExpectedGateIndex` 不推进、`samples` append 当前帧；这些断言足以区分 sector rejected 路径和 no-target 路径。

### 4.2 Validation

- `./gradlew :feature:test:testDebugUnitTest --tests "*LapTimingEngineTest.processSample_whenNextExpectedGateIndexExceedsSectorCount_routesThroughNoTargetGatePath" --tests "*LapDebugExecutionScreenStateTest*"` PASS。
- 实施方回报 `openspec validate fix-active-lap-distance-accumulator --strict` PASS。
- A22 code review finding 已闭合，无新增 P1/P2。

### 4.3 Verdict

Round 2 通过。A22 已核销，backlog 已迁入第五节 ✅ 存档，附录状态列已改 ✅。
