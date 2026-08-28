# fix-di-fallback-and-anomaly-island-cleanup code review

## 0. TL;DR

不建议立即核销 A17 / A30 到 ✅。A30 删除方案实现干净，A17 的 cause-chain 实现本身也能证明 DI provider 创建期的非 Missing 异常不会被 `single<TrackCatalog>` 吞掉；但实现期把关键测试从 spec 中的 `loadReplayJson()` / `getAllTracks()` IOException 上抛，改成了 provider 创建期直接抛 IOException。当前 `ReplayAlignedTrackCatalog.ensureReplayTrackLoaded()` 会 `runCatching { ... }.getOrNull()` 吞掉 `loadReplayJson()` 异常，所以 OpenSpec 仍声明的“真机 asset read IOException 原样上抛”契约没有被实现。

## 1. Findings

### Finding 1 — [P2] A17 真机异常测试降级为 DI provider 创建期异常，未覆盖 spec 的 asset read 失败

- **位置**：`feature/test/src/test/java/com/blazepush/feature/test/di/DomainModuleKoinTest.kt:72-118`
- **问题**：`di-real-device-error-propagation` spec 仍要求 fake `ReplayTrackSource.loadReplayJson()` 抛 `IOException`，`get<TrackCatalog>()` 成功后调用 `runBlocking { trackCatalog.getAllTracks() }` 时 IOException 原样上抛。但实际测试改成 fake `single<ReplayTrackSource>` provider 在 DI 实例化期直接 `throw IOException`，只证明了 `single<TrackCatalog>` 的 cause-chain fallback 不会吞 provider 创建期的非 Missing 异常。它没有覆盖 asset 读取 / JSON parse 发生在 `ReplayAlignedTrackCatalog.getAllTracks()` 内的路径。
- **证据**：当前 `ReplayAlignedTrackCatalog.ensureReplayTrackLoaded()` 在 `feature/test/src/main/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalog.kt:72-77` 使用 `runCatching { buildReplayAlignedTrack(...) }.getOrNull()`，会吞掉 `replayTrackSource.loadReplayJson()` / `loadTrackVbo()` 抛出的 IOException 并 fallback。因此按当前实现，spec 里的 `loadReplayJson()` IOException 上抛场景不会成立。
- **要求**：二选一收口，不要让 spec 与实现继续分叉：
  - 若 A17 的目标仍是“真机 asset read / parse 异常可见”，则需要修改 `ReplayAlignedTrackCatalog` 的容错策略，让 `loadReplayJson()` / parse IOException 在真实异常场景上抛，并补回 spec 所写的 `getAllTracks()` 抛 IOException 测试。
  - 若 A37 的 fallback 行为是刻意保留，则需要把本 change 的 A17 scope 明确降级为“仅保证 DI provider 创建期非 Missing 异常不被 `single<TrackCatalog>` 吞掉；asset read / parse 在 `ReplayAlignedTrackCatalog` 内继续 fallback”，同步修订 proposal/spec/tasks/backlog 核销条件，并把“asset read/parse 静默 fallback 是否可接受”作为显式豁免或独立后续条目。

## 2. Verified

- `openspec validate fix-di-fallback-and-anomaly-island-cleanup --strict` PASS。
- `./gradlew :feature:test:testDebugUnitTest --tests "*DomainModuleKoinTest*"` PASS。
- A30 grep：`\bAnomalyDetector\b|\bDataInterpolator\b|\bDataSmoothing\b` 在 `core/feature/app/simulator` `.kt` 源码零命中。
- A17 grep：`single<TrackCatalog>` 不再使用 `runCatching`；`AppModule.kt` 引入 Koin 自带 `org.koin.android.error.MissingAndroidContextException` 并使用 `findInCauseChain<MissingAndroidContextException>()`。

## 3. Verdict

暂不核销 A17 / A30。请先按 Finding 1 拍板并修订实现或工件；无需 patches 清单。

## 4. Round 2 / B 方案复核

### 4.1 Finding closure

- Finding 1 partially closed：spec 与 design 已选择 B 方案，把 A17 scope 显式限定为 `single<TrackCatalog>` provider 创建期异常处理；A37 `ReplayAlignedTrackCatalog.ensureReplayTrackLoaded()` 内 `runCatching { ... }.getOrNull()` 容错契约保留，不再承诺 `getAllTracks()` 内 asset read IOException 上抛。
- Backlog 已补充 B 方案说明，commit `fcc61cc` 代码本身与 B 方案一致。
- `openspec validate fix-di-fallback-and-anomaly-island-cleanup --strict` PASS。
- `./gradlew :feature:test:testDebugUnitTest --tests "*DomainModuleKoinTest*"` BUILD SUCCESSFUL。

### 4.2 New finding

#### Finding 2 — [P2] proposal/tasks 仍残留 asset read 上抛与自建 wrapper 旧契约

- **位置**：
  - `openspec/changes/fix-di-fallback-and-anomaly-island-cleanup/proposal.md:5-18`
  - `openspec/changes/fix-di-fallback-and-anomaly-island-cleanup/proposal.md:39-52`
  - `openspec/changes/fix-di-fallback-and-anomaly-island-cleanup/tasks.md:65-79`
  - `openspec/changes/fix-di-fallback-and-anomaly-island-cleanup/tasks.md:209-231`
  - `openspec/changes/fix-di-fallback-and-anomaly-island-cleanup/tasks.md:286-289`
- **问题**：B 方案已把 A17 限定为 DI provider 创建期异常，不再修订 A37 asset read fallback；但 proposal/tasks 仍写旧目标：
  - proposal 仍要求新建项目内 `MissingAndroidContextException.kt`，并让 `single<ReplayTrackSource>` 包一层 try/catch wrapper。
  - proposal/tasks 仍写 asset 损坏 / `IOException` / `Gson parse` 原样上抛让真机崩溃上报可见。
  - tasks §10.2 仍要求 fake `ReplayTrackSource.loadReplayJson()` 抛 `IOException` 后 `runBlocking { trackCatalog.getAllTracks() }` 抛出；这与新 spec 明确保留的 A37 `runCatching` fallback 契约冲突。
  - tasks commit body 仍写“新建 `MissingAndroidContextException` 作为 cause chain 标记类型”与 “A17 grep MissingAndroidContextException throw+catch 各命中”，和实际 commit `fcc61cc` 复用 Koin 自带类型 / 不新建 wrapper / 不要求 throw 字面量不一致。
- **要求**：把 proposal/tasks 与 spec/design 的 B 方案同步：
  - proposal What Changes / Impact 删除“新建项目内 wrapper”和 `single<ReplayTrackSource>` try/catch 转换，改为复用 Koin 自带 `org.koin.android.error.MissingAndroidContextException` + `single<ReplayTrackSource>` 零改动。
  - proposal Why / Capability 描述明确：A17 修 provider 创建期 catch 边界；asset read/parse fallback 属 A37 已核销契约，本 round 不改。
  - tasks §10.2 改成当前代码实际测试：fake `single<ReplayTrackSource>` provider 直接抛 `IOException`，断言 `get<TrackCatalog>()` 上抛且 cause chain 含原始 IOException；另可保留/新增一条源码或 runtime 检查锁住 `ReplayAlignedTrackCatalog.ensureReplayTrackLoaded()` 的 `runCatching` fallback。
  - tasks §13 commit body / grep 门槛改为 “Koin 自带 MissingAndroidContextException import + findInCauseChain 类型参数命中”，不要再要求项目内 throw / catch 各命中。

### 4.3 Verdict

Round 2 仍不核销 A17 / A30。代码和 spec/design 已接近可过，但 proposal/tasks 仍会误导后续 archive / audit；请清掉旧契约后重提 mini review。无需改代码。

## 5. Round 3 / final review

### 5.1 Finding closure

- Finding 2 closed：proposal / tasks 已同步到 B 方案。`proposal.md` 不再要求新建项目内 `MissingAndroidContextException.kt`，不再要求 `single<ReplayTrackSource>` try/catch wrapper；Impact 也明确不新建 wrapper 文件。
- Finding 2 closed：tasks §10.2 已从 `loadReplayJson()` / `getAllTracks()` IOException 上抛改为 fake `single<ReplayTrackSource>` provider 直接抛 `IOException`，与 commit `fcc61cc` 的 `DomainModuleKoinTest.providesTrackCatalog_realDeviceAssetFailure_propagatesNotSilenced` 一致。
- Finding 2 closed：tasks §12.5 / §13 commit body 已改为 `findInCauseChain<MissingAndroidContextException>` + Koin import + 项目内自建类型零命中，不再要求 “throw+catch 各命中”。
- `openspec validate fix-di-fallback-and-anomaly-island-cleanup --strict` PASS。
- `./gradlew :feature:test:testDebugUnitTest --tests "*DomainModuleKoinTest*"` BUILD SUCCESSFUL。

### 5.2 Non-blocking note

- `tasks.md` §10.2 的“补 import”仍列出 `kotlinx.coroutines.runBlocking` 与 `Assert.assertEquals`，但新测试片段已不使用它们，实际 `DomainModuleKoinTest.kt` 也未引入这两个 import。按 P3 文档尾巴豁免，不阻塞核销。

### 5.3 Verdict

通过。A17 / A30 核销为 ✅ resolved。

- A17 核销口径：B 方案生效，scope 限定为 `single<TrackCatalog>` provider 创建期异常边界；A37 `ReplayAlignedTrackCatalog.ensureReplayTrackLoaded()` 内 asset read / parse fallback 容错契约保留，未来若需要 asset read 失败上抛，另起独立 round。
- A30 核销口径：3 个孤岛类删除、DI 解绑、`GpsDataViewModel` 解耦与测试迁移完成，源码 grep 零命中。
