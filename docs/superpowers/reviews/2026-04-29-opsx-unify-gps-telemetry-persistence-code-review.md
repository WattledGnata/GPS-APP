# unify-gps-telemetry-persistence code review

- **日期**：2026-04-29
- **变更**：`openspec/changes/unify-gps-telemetry-persistence`
- **结论**：暂不核销。实现方向基本贴近已通过的 seek-header / command-channel 方案，但当前代码仍有 1 个编译阻断和 3 个持久化语义风险，需修完后重提 commit-diff review。

## Findings

### 1. [P1] feature:test 单测编译失败，TestSessionViewModel 新依赖未迁完

- **位置**：
  - `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLapTest.kt:320-329`
  - `feature/test/src/test/java/com/blazepush/feature/test/viewmodel/TestSessionViewModelTrackLoadingTest.kt:153-162`
- **问题**：`TestSessionViewModel` 构造函数已新增 `telemetryRepository` 必填参数，但两个既有测试 helper 仍按旧参数列表构造，导致 `:feature:test:compileDebugUnitTestKotlin` 直接失败。
- **证据**：
  - `./gradlew :feature:test:testDebugUnitTest --tests "*TestSessionViewModel*"` 失败：
    - `No value passed for parameter 'telemetryRepository'`
    - 命中上述两个 test 文件。
- **要求**：给这两个 helper 注入 fake/mock `TelemetryRepository`，并补跑至少 `:feature:test:testDebugUnitTest --tests "*TestSessionViewModel*"`。这是合流门槛，未修前不能核销。

### 2. [P1] ViewModel 用多路 launch 写 telemetry，会在 start/end 竞态下静默丢点

- **位置**：
  - `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:190-195`
  - `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:280-292`
  - `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:363-379`
  - `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:451-465`
  - `core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt:50-52`
- **问题**：`activeTestStartTs` / `activeLapStartSystemTs` 在 `startSession()` 完成前就被置为非空，后续帧会立即 `viewModelScope.launch { telemetryRepository.writeSample(...) }`。但 `TelemetryRepository.writeSample()` 在 `activeWriter == null` 时静默 return。与此同时，每帧写入和 `endSession()` 都是独立 coroutine，没有单一 FIFO 顺序；stop/finish 可能先关闭 writer，随后较早帧的写入 coroutine 才执行并被丢弃。
- **影响**：这会破坏 A56 的核心目标：密集 telemetry 持久化不能在真实 25Hz 路测中静默漏帧。当前 writer 内部虽然已经用 `SUSPEND` + `Flush/Close ack` 解决了 channel 层背压，但 ViewModel 调用层重新引入了无序/无 ack 的丢点窗口。
- **要求**：
  - startSession 完成前不要设置“可写”锚点，或先缓冲样本到 start 完成后顺序写入。
  - 每个 session 使用单一有序写入协程/队列，finish/stop 必须等待此前所有 sample write 完成后再 `endSession()`。
  - `TelemetryRepository.writeSample()` 不应静默吞掉无 active session 的调用；至少返回状态或抛出可测试错误。
  - 补测试覆盖：startSession 尚未完成时的第一批帧不丢；finish 立即发生时 close 不越过 pending writes。

### 3. [P2] CrossingEvent 的 lapIndex 用 updatedSession.currentLapIndex，会把闭圈事件归到下一圈

- **位置**：`feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:503-510`
- **问题**：新增 crossing 持久化时统一使用 `updatedSession.currentLapIndex`。但 start/finish 闭圈后，engine 会把 `currentLapIndex` 推进到下一圈；刚刚用于关闭上一圈的 crossing 会被持久化为下一圈。这样 Room 中的过线事件与实际 LapRecord / lap summary 关联错位。
- **要求**：按 crossing 语义确定 lapIndex：闭圈 start/finish crossing 应写入刚完成的 active lap index；开圈 crossing 写入新 active lap index；sector crossing 写当前 active lap index。建议由 engine 输出带 lapIndex 的事件，或在 ViewModel 写入前基于 `currentSession` / `updatedSession` 的差异显式映射，并补“闭圈事件归属上一圈”的测试。

### 4. [P2] 删除记录不删除二进制 telemetry 文件，会留下大文件孤儿

- **位置**：`core/data/src/main/java/com/blazepush/core/data/repository/TestResultRepository.kt:54-56`
- **问题**：旧 JSON 路径下删除结果会清理对应数据文件；本实现删除 `TestRecordEntity` 后不删除 `dataFilePath` 指向的 binary 文件。A56 的文件可能是长时间 25Hz 路测数据，用户删除记录后文件继续常驻会持续占用存储。
- **要求**：删除记录时同步删除 `dataFilePath` 指向的 telemetry 文件，或引入 `TelemetryRepository.deleteSession(...)` 统一删除 Room metadata + binary file。删除前需限制在 app telemetry 目录下，避免误删任意路径；补 repository 单测。

## Verification

- `openspec validate unify-gps-telemetry-persistence --strict`：PASS。
- `./gradlew :core:data:testDebugUnitTest --tests "*BinaryTelemetryWriterTest*" --tests "*LapTelemetryReaderTest*" --tests "*TelemetryRepositoryTest*"`：PASS（上一轮已跑）。
- `./gradlew :core:data:compileDebugKotlin`：PASS（上一轮已跑）。
- `./gradlew :feature:test:compileDebugKotlin`：PASS（上一轮已跑）。
- `./gradlew :feature:test:testDebugUnitTest --tests "*TestSessionViewModel*"`：FAIL，见 Finding 1。

## Review Verdict

暂不迁 A56。修完上述 findings 后重提核销；重点复查 ViewModel 到 TelemetryRepository 的 session 生命周期顺序、closed/drained 语义、以及记录删除的文件清理行为。

---

## V2 Fix Review

- **日期**：2026-04-29
- **结论**：仍暂不核销。编译阻断已修复，data 层测试通过；但 telemetry 接入层的核心无序写入风险仍未关闭，且 lapIndex 修补过粗，开圈事件会被写成 `0`。

### Closed

- 原 Finding 1（`telemetryRepository` 测试依赖未迁完）：已修。`TestSessionViewModelTrackLapTest` 与 `TestSessionViewModelTrackLoadingTest` helper 已补 mock `TelemetryRepository`。
- 原 Finding 4（删除记录不清理 binary 文件）：方向已修。`deleteResult()` 会删除 `dataFilePath` 指向且 canonical path 包含 `/telemetry/` 的文件。建议后续再收紧为 app filesDir 下的 telemetry 目录，但不继续阻塞本轮。

### Remaining Findings

#### 1. [P1] startSession 仍在后台 launch，首批帧仍可能在 writer 未建立时被静默丢弃

- **位置**：
  - `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:190-195`
  - `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:361-377`
  - `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:449-461`
  - `core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt:50-52`
- **复核**：V2 把 per-frame `writeSample()` 从独立 `launch` 改成 inline suspend 调用，这是正确方向；但 `activeTestStartTs` / `activeLapStartSystemTs` 仍在 `startSession()` 完成前置为非空，`startSession()` 本身仍在 `viewModelScope.launch` 中异步执行。下一帧到来时会 inline 调 `writeSample()`，而 `TelemetryRepository.activeWriter` 可能还是 null；当前 `writeSample()` 仍是 `activeWriter?.write(sample)`，会静默吞掉。
- **要求**：必须让“锚点可写”与 writer ready 同步。例如：
  - 在同一 coroutine 内 `startSession()` 完成后再设置 `active*StartTs` / `active*SessionId`；
  - 或引入 pending buffer，在 session ready 后按 FIFO flush；
  - 或把 telemetry start/write/end 全部交给单一 actor，禁止 ViewModel 状态先行。
  - `TelemetryRepository.writeSample()` 对无 active writer 不能静默成功，应返回 false 或抛出可测试错误。

#### 2. [P2] lapIndex 修补对闭圈有效，但开圈事件会写成 0

- **位置**：`feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt:490-505`
- **复核**：V2 统一使用 `currentSession.currentLapIndex`，确实修正了闭圈后 `updatedSession.currentLapIndex` 已推进的问题。但首次 start/finish 开圈时，`currentSession.currentLapIndex` 仍是 `0`，engine 在 accepted 分支里新建 `activeLap(lapIndex = 1)` 并返回 `currentLapIndex = 1`。因此开圈 crossing 会被持久化为 lap 0。
- **要求**：按事件类型/状态分别映射：
  - 开圈 start/finish crossing：写入新 active lap index（通常 `updatedSession.activeLap?.lapIndex` 或 `updatedSession.currentLapIndex`）。
  - 闭圈 start/finish crossing：写入刚完成的 active lap index（`currentSession.activeLap?.lapIndex`）。
  - sector crossing：写入当前 active lap index。
  - 补测试覆盖开圈事件不是 0、闭圈事件不漂到下一圈。

## V2 Verification

- `./gradlew :feature:test:testDebugUnitTest --tests "*TestSessionViewModel*"`：PASS。
- `./gradlew :core:data:testDebugUnitTest`：PASS。

---

## V3 Fix Review

- **日期**：2026-04-29
- **结论**：代码 review 阻塞项已关闭。A56 代码层面可核销；剩余仅为 `tasks.md` §10.1-10.3 真机 manual gates 与最终 commit hash 回填。

### Closed

- V2 Finding 1（`startSession()` 后台 launch 导致 writer 未 ready）：已修。
  - 性能测试路径：`startTest()` 改为 `suspend fun`，`telemetryRepository.startSession(PERFORMANCE_TEST)` 与 pre-trigger frames 写入在 GPS collect coroutine 内 inline 完成；该 coroutine 不会处理下一帧直到 writer ready。
  - 圈速路径：`selectLapDebugMode()` 只记录 `activeLapStartSystemTs`，writer 在 `bridgeGpsToLapTiming()` 段 3 首次正常推进时 inline 懒启动，然后立即写当前帧；不再存在 `activeLapSessionId == null` 时仍写 sample 的窗口。
- V2 Finding 2（开圈 `lapIndex` 写成 0）：已修。
  - 新增 `lapIndexForCrossing(previousLapIndex, updatedLapIndex)`：`0 -> N` 开圈用新 index；闭圈和 sector 用旧 index。
  - `TestSessionViewModelTrackLapTest` 新增 4 条确定性测试覆盖开圈不是 0、闭圈不漂移、sector 不变、三圈以上闭圈。

### Verification

- `openspec validate unify-gps-telemetry-persistence --strict`：PASS。
- `./gradlew :feature:test:testDebugUnitTest`：PASS。
- `./gradlew :core:data:testDebugUnitTest`：PASS。
- 旧 JSON 路径 grep：`TestDataFileStorage|saveDataPoints|loadDataPoints` 在 `core/feature/app/simulator` Kotlin 源码零命中。

### Remaining Manual Gates

- `tasks.md` §10.1：真机跑一次 0-100，确认 binary 文件落盘且可读取。
- `tasks.md` §10.2：真机跑一次圈速 session，确认 CrossingEvent 写入 Room 且 binary 样本数正确。
- `tasks.md` §10.3：真机验证 BLE 中途断开/重连，writer 不崩溃且已 flush 数据完整。
