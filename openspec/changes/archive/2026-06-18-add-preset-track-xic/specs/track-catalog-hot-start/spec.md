## MODIFIED Requirements

### Requirement: `PresetTrackCatalog` 内存直返实现（suspend 不强制 IO）

`PresetTrackCatalog.getAllTracks()` MUST 实现为 suspend fun 但 body **不需要** `withContext(Dispatchers.IO)`，直接 return 内存中的预置赛道列表。`suspend` 关键字仅为对齐接口契约。

预置赛道列表 MUST 由 `mainPresets`（含 TFIC LPCC + XIC 厦门国际赛车场，所有 Build Variant 共用，定义在 main 源集）与 `extraPresetTracks(): List<Track>`（由 Android Gradle 互斥变体源集 `src/debug/` 与 `src/release/` 各提供一份实现）拼接而成：

- main 源集 MUST 在 `PresetTracks.kt` 中调用 `extraPresetTracks()`，但 MUST NOT 在 main 源集声明 `extraPresetTracks` 函数本体（否则 main+debug 合并编译时触发 `duplicate JVM declarations`）。
- release 源集 MUST 提供 `feature/test/src/release/.../repository/ExtraPresetTracksRelease.kt`，其中 `internal fun extraPresetTracks(): List<Track> = emptyList()`。
- debug 源集 MUST 提供 `feature/test/src/debug/.../repository/ExtraPresetTracksDebug.kt`，其中 `internal fun extraPresetTracks(): List<Track> = listOf(<天投泊寓环线 Track>)`。
- 两个变体源集文件的包名 MUST 一致（`com.blazepush.feature.test.repository`），文件名后缀 `Debug` / `Release` 仅作 IDE/grep 区分，不影响编译可见性。
- 拼接顺序 MUST 为 `mainPresets + extraPresetTracks()`，即 mainPresets[0] = TFIC，mainPresets[1] = XIC，debug variant 拼接后第 2 位为天投泊寓。
- release 构建 MUST NOT 编译进任何 debug 源集文件；release 包内 `getAllTracks()` 返回 `["preset-tfic-lpcc", "preset-xic-lpcc"]`。
- variant 区分 MUST 通过 Gradle 源集机制达成，MUST NOT 依赖 `BuildConfig.DEBUG` 或其它运行时 if-else 判断（否则 release 字节码会留死代码）。

- 业务目标：避免对纯内存查询强加上下文切换开销；debug 包额外获得真实小型环线赛道（boyu）用于圈速调试；XIC 作为第二条 main-variant preset 让所有 variant 用户都能跑厦门国际赛车场。
- 测试消费方：`TrackCatalogReleaseVariantTest`（仅 `:feature:test:testReleaseUnitTest`）断言 `["preset-tfic-lpcc", "preset-xic-lpcc"]`；`TrackCatalogDebugVariantTest`（仅 `:feature:test:testDebugUnitTest`）断言 `["preset-tfic-lpcc", "preset-xic-lpcc", "preset-boyu-loop"]`。

#### Scenario: PresetTrackCatalog 无 withContext 调用

- **GIVEN** 实施后 `PresetTrackCatalog.kt`（或等价文件名，tasks 阶段 grep 确认）源码
- **WHEN** grep `withContext` / `Dispatchers.IO` 在 `getAllTracks` body 内
- **THEN** 零匹配（纯 return 内存列表）

#### Scenario: PresetTrackCatalog.getAllTracks 返回预置赛道

- **GIVEN** `PresetTrackCatalog` 实例
- **WHEN** `runTest { catalog.getAllTracks() }`
- **THEN** 返回非空 `List<Track>`，第 0 位 MUST 为 TFIC LPCC（`id == "preset-tfic-lpcc"`），第 1 位 MUST 为 XIC（`id == "preset-xic-lpcc"`）

#### Scenario: release variant 含 TFIC + XIC

- **GIVEN** `:feature:test:testReleaseUnitTest` 任务（即 `main + release + test + testRelease` 源集组合）
- **WHEN** `runTest { PresetTrackCatalog().getAllTracks() }`
- **THEN** 返回 `List<Track>` 的 `map { it.id }` 严格等于 `listOf("preset-tfic-lpcc", "preset-xic-lpcc")`

#### Scenario: debug variant 额外含天投泊寓（三条赛道顺序锁定）

- **GIVEN** `:feature:test:testDebugUnitTest` 任务（即 `main + debug + test + testDebug` 源集组合）
- **WHEN** `runTest { PresetTrackCatalog().getAllTracks() }`
- **THEN** 返回 `List<Track>` 的 `map { it.id }` 严格等于 `listOf("preset-tfic-lpcc", "preset-xic-lpcc", "preset-boyu-loop")`

#### Scenario: extraPresetTracks 在 release 源集返回 emptyList

- **GIVEN** release 源集下 `feature/test/src/release/.../repository/ExtraPresetTracksRelease.kt`
- **WHEN** 阅读 `extraPresetTracks` 函数 body
- **THEN** 函数返回 `emptyList()`，且文件**不**包含天投泊寓 Track 数据或任何 import `com.blazepush.feature.test.model.track.*` 之外的赛道相关引用

#### Scenario: extraPresetTracks 在 debug 源集返回天投泊寓

- **GIVEN** debug 源集下 `feature/test/src/debug/.../repository/ExtraPresetTracksDebug.kt`
- **WHEN** `extraPresetTracks()` 被调用
- **THEN** 返回单元素列表，唯一元素 `id == "preset-boyu-loop"`

#### Scenario: 反例——XIC MUST NOT 进 extraPresetTracks（debug-only）

- **GIVEN** 实施后 `ExtraPresetTracksDebug.kt` 与 `ExtraPresetTracksRelease.kt`
- **WHEN** grep `preset-xic-lpcc` 在两文件中
- **THEN** 零匹配（XIC 仅在 main 源集 PresetTracks.kt mainPresets 中声明）
- **AND** 若实现把 XIC 放进 debug ExtraPresetTracksDebug.kt，release variant 包内 PresetTrackCatalog().getAllTracks() 不含 XIC → "release variant 含 TFIC + XIC" scenario fail
