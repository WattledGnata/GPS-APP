# fix-gps-stats-and-lazy-catalog-hot-start code review

## 0. TL;DR

不建议立即核销 A28/A37 到 ✅。commit `ebaf394` 的主实现和测试基本对齐 specs/tasks，但 A28 的生产源码里仍残留 `dataCount / dataCountStartTime` 字面量，违反 `gps-runtime-stats` spec 的“ViewModel 不维护 dataCount / dataCountStartTime”源码 grep 契约。修一个注释即可重提 mini review。

## 1. Findings

### Finding 1 — [P2] A28 删除字段名仍残留在生产源码注释中

- **位置**：`feature/test/src/main/java/com/blazepush/feature/test/viewmodel/GpsDataViewModel.kt:165`
- **问题**：spec `gps-runtime-stats` R1 Scenario 要求实施后 grep `dataCount`、`dataCountStartTime`、`expectedInterval = 100` 均不存在；tasks §1.3 也要求相关字段和赋值删净。当前字段和逻辑已删除，但 `resetStats()` KDoc 仍写 `dataCount / dataCountStartTime 已随累计平均逻辑删除`，导致机器 grep 仍命中生产源码。评审方实跑：
  - `rg -n "dataCount|dataCountStartTime|expectedInterval = 100|_dataStats|val dataStats|StateFlow<DataStats>|EXPECTED_SAMPLE_INTERVAL_MS" feature/test/src/main core/bluetooth/src/main core/domain/src/main`
  - 命中 `GpsDataViewModel.kt:165`
- **要求**：把注释改成不含被禁字段名的表述，例如“旧累计平均状态已随 A28 删除”；行为代码无需改。

## 2. Verified

- `openspec validate fix-gps-stats-and-lazy-catalog-hot-start --strict` PASS。
- `./gradlew :feature:test:testDebugUnitTest --tests "*GpsDataViewModelTest*"` PASS。
- `./gradlew :feature:test:testDebugUnitTest --tests "*ReplayAlignedTrackCatalogTest*"` 单独重跑 PASS。
- `TrackCatalog.getAllTracks()` 生产/测试调用点均已迁移到 suspend/runTest/协程上下文。
- `ReplayAlignedTrackCatalog.getTrack(TFIC)` cold fallback / warm replay-aligned 主线实现与测试均已覆盖。
- `by lazy` 残留仅在 `Track.orderedSectorGates`，不属于 `ReplayAlignedTrackCatalog` A37 禁区。

## 3. Note

我曾并行运行两个 `:feature:test:testDebugUnitTest --tests ...` 命令，第二个命令因共享 Gradle test task 的 binary results 输出目录报 `results.bin` 不存在；单独重跑 `ReplayAlignedTrackCatalogTest` 已 PASS，因此该失败不是本 change 的测试失败。

## 4. Verdict

暂不核销 A28/A37。修复 Finding 1 后可重提 mini review；无需 patches 清单。

## 5. Round 2 mini review

### 5.1 Finding closure

- Finding 1 closed：commit `fabb285` 将 `GpsDataViewModel.resetStats()` KDoc 从旧字段名表述改为“旧累计平均状态已随 A28 删除”，生产源码不再出现 A28 禁字段字面量。

### 5.2 Validation

- `openspec validate fix-gps-stats-and-lazy-catalog-hot-start --strict` PASS。
- 禁字段 grep：`dataCount|dataCountStartTime|expectedInterval = 100|_dataStats|val dataStats|StateFlow<DataStats>|EXPECTED_SAMPLE_INTERVAL_MS` 在本 change 约束目录零命中。
- `./gradlew :feature:test:testDebugUnitTest --tests "*GpsDataViewModelTest*"` PASS。
- `./gradlew :feature:test:testDebugUnitTest --tests "*ReplayAlignedTrackCatalogTest*"` 单独重跑 PASS。

### 5.3 Verdict

Round 2 通过。A28 / A37 已核销，backlog 已迁入第五节 ✅ 存档，附录状态列已改 ✅。
