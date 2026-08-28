# fix-gps-stats-and-lazy-catalog-hot-start tasks review

## 0. TL;DR

`openspec validate fix-gps-stats-and-lazy-catalog-hot-start --strict` 通过，但 tasks 暂不放行 `/opsx:apply`。当前有 3 个会让实施方照做失败的点：A37 编译门槛顺序不可能成立、旧 `ReplayAlignedTrackCatalogTest` 与新冷缓存契约冲突、A28 时间相关测试缺少确定性时钟。

## 1. Findings

### Finding 1 — [P1] §3.5 编译门槛放在 BREAKING 消费方迁移之前

- **位置**：`openspec/changes/fix-gps-stats-and-lazy-catalog-hot-start/tasks.md:130-227`
- **问题**：§3.1-§3.3 把 `TrackCatalog.getAllTracks()` 改成 `suspend fun`，但 §4 才改 `TestSessionViewModel.kt:80` 的构造期同步调用。当前 §3.5 却要求在 §4 前跑 `./gradlew :feature:test:compileDebugKotlin` BUILD SUCCESSFUL。照顺序执行时，`TestSessionViewModel` 仍在 property initializer 里同步调用 suspend 方法，编译必红。tasks 自己也写“若此时 TestSessionViewModel 未改会失败，下一节处理”，与 BUILD SUCCESSFUL 门槛冲突。
- **要求**：删除 §3.5，或改成“预期 compile 会因消费方未迁移失败，不作为门槛”；把真正的 compile 门槛放到 §4.2，在接口、两个实现、所有生产消费方迁移后统一跑。

### Finding 2 — [P1] 旧 ReplayAlignedTrackCatalogTest 未迁移，会和冷缓存 getTrack 新契约冲突

- **位置**：`openspec/changes/fix-gps-stats-and-lazy-catalog-hot-start/tasks.md:233-262`
- **问题**：新 spec 要求冷缓存 `getTrack(TFIC)` 返回 fallback 且不触发 asset IO。但现有 `ReplayAlignedTrackCatalogTest.kt` 里至少 `getTrack_buildsGeneratedTficTrackFromReplayAssets` 仍冷调用 `catalog.getTrack("preset-tfic-lpcc")` 并断言 `TrackSource.Generated` / `layoutName == "REAL_TRACK_REPLAY"`。实施后这条旧测试会失败。tasks 只要求更新 `getAllTracks_exposesReplayAlignedTrackToRuntimeSelection`，没有安排迁移这些旧 `getTrack` replay 断言。
- **要求**：tasks §5 增加旧测试迁移项：所有期待 `getTrack(TFIC)` 返回 replay-aligned 的旧测试必须先 `runTest { catalog.getAllTracks() }` warm cache 后再调用 `getTrack`，或改成断言 cold fallback。至少覆盖 `generatedTrack_reusesCorrectedTficGateGeometry` / `getTrack_buildsGeneratedTficTrackFromReplayAssets` 这类冷 getTrack 测试。

### Finding 3 — [P2] packetLoss 精确断言缺少确定性时间源

- **位置**：`openspec/changes/fix-gps-stats-and-lazy-catalog-hot-start/tasks.md:109-115`
- **问题**：`updateDataStats` 用 `System.currentTimeMillis()` 计算 `dataAge = now - data.timestamp`，但测试要求精确断言 `packetLoss == 2.0` / `4.0`。tasks 只写“固定 System.currentTimeMillis 或用 data.timestamp 直接算”，没有给可执行方案。若测试用 `timestamp = System.currentTimeMillis() - 300`，调度抖动会让实际 dataAge 变成 301/302ms，精确等值断言 flaky。
- **要求**：tasks 明确一种稳定方案：要么给 `GpsDataViewModel` 注入 clock provider（默认 `System.currentTimeMillis`，测试 fake），要么测试断言使用容差（例如 `assertEquals(2.0, packetLoss, 0.05)`）并在 emission 前尽量贴近构造 timestamp。若不想扩大生产 API，推荐容差断言并把 25Hz 200ms 场景也写进测试，以硬区分 v1=1.0 / v2≈4.0。

### Finding 4 — [P2] §1.5 代码片段引用不存在的 `gpsDataRepository.gpsData`

- **位置**：`openspec/changes/fix-gps-stats-and-lazy-catalog-hot-start/tasks.md:79-84`
- **问题**：当前 `GpsDataRepository` 暴露的是 `gpsDataFlow`，`GpsDataViewModel` 内已有属性 `val gpsData: StateFlow<GpsData> = gpsDataRepository.gpsDataFlow`。§1.5 片段写 `gpsDataRepository.gpsData.onEach`，照抄会 unresolved reference。
- **要求**：片段改为复用现有 `gpsData.onEach { ... }.launchIn(viewModelScope)`，或写 `gpsDataRepository.gpsDataFlow.onEach`；不要写不存在的 `gpsDataRepository.gpsData`。

## 2. Notes

- `openspec validate --strict` 通过只说明 OpenSpec 结构有效，不覆盖上述任务顺序/测试迁移问题。
- §2.4 DISCONNECTED reset 测试建议明确先发 `CONNECTED` 再发 `DISCONNECTED`，避免 fake repository 初始就是 `DISCONNECTED` 时 `StateFlow` 去重导致测试没有真正覆盖“迁入 DISCONNECTED”。

## 3. Verdict

暂不放行 `/opsx:apply`。修完 Finding 1 和 Finding 2 后可重提 tasks mini review；Finding 3/4 建议同轮修掉，避免实施期抖动。

## 4. Round 2 mini review

### 4.1 Finding closure

- Finding 1 closed：§3.5 已改为“跳过中间 compile 门槛”，真正 compile 门槛放到 §4.2，顺序与 BREAKING 迁移一致。
- Finding 2 closed：§5.2b 已列出 3 条旧 `getTrack(TFIC)` replay-aligned 测试，并要求先 `runTest { catalog.getAllTracks() }` warm cache，再保留原断言。
- Finding 3 closed：§1.2b 抽出 `computePacketLossRate(dataAge, frequency)` 纯函数，§2.3 直接对纯函数做精确断言，避开 `System.currentTimeMillis()` 抖动。
- Finding 4 closed：§1.5 已改为保留现有 `gpsData.collect` launch，并新增并列 `connectionState` launch，不再引用不存在的 `gpsDataRepository.gpsData`。

### 4.2 Non-blocking note

`resetStats_onConnectionStateDisconnected_clearsQuality` 测试实现时建议让 fake repository 初始为 `CONNECTED`，喂帧后再发射 `DISCONNECTED`。如果 fake 初始就是 `DISCONNECTED`，`StateFlow`/`distinctUntilChanged` 可能不会产生迁入事件，测试容易验证不到真正路径。

### 4.3 Verdict

Round 2 通过。可以进入 `/opsx:apply`。
