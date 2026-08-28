## MODIFIED Requirements

### Requirement: `PresetTrackCatalog` 内存直返实现（suspend 不强制 IO）

`PresetTrackCatalog.getAllTracks()` MUST 实现为 suspend fun 但 body **不需要** `withContext(Dispatchers.IO)`，直接 return 内存中的预置赛道列表。`suspend` 关键字仅为对齐接口契约。

预置赛道列表 MUST 由 `mainPresets`（含 TFIC LPCC，所有 Build Variant 共用，定义在 main 源集）与 `extraPresetTracks(): List<Track>`（由 Android Gradle 互斥变体源集 `src/debug/` 与 `src/release/` 各提供一份实现）拼接而成：

- main 源集 MUST 在 `PresetTracks.kt` 中调用 `extraPresetTracks()`，但 MUST NOT 在 main 源集声明 `extraPresetTracks` 函数本体（否则 main+debug 合并编译时触发 `duplicate JVM declarations`）。
- release 源集 MUST 提供 `feature/test/src/release/.../repository/ExtraPresetTracksRelease.kt`，其中 `internal fun extraPresetTracks(): List<Track> = emptyList()`。
- debug 源集 MUST 提供 `feature/test/src/debug/.../repository/ExtraPresetTracksDebug.kt`，其中 `internal fun extraPresetTracks(): List<Track> = listOf(<天投泊寓环线 Track>)`。
- 两个变体源集文件的包名 MUST 一致（`com.blazepush.feature.test.repository`），文件名后缀 `Debug` / `Release` 仅作 IDE/grep 区分，不影响编译可见性。
- 拼接顺序 MUST 为 `mainPresets + extraPresetTracks()`，即 TFIC 永远位于列表第 0 位。
- release 构建 MUST NOT 编译进任何 debug 源集文件；release 包内 `getAllTracks()` 返回的列表 MUST 行为等价于本 round 实施前。
- variant 区分 MUST 通过 Gradle 源集机制达成，MUST NOT 依赖 `BuildConfig.DEBUG` 或其它运行时 if-else 判断（否则 release 字节码会留死代码）。

- 业务目标：避免对纯内存查询强加上下文切换开销，同时让 debug 包额外获得真实小型环线赛道用于圈速调试。
- 测试消费方：`TrackCatalogTest` 现有"只含 TFIC"断言迁移到 `src/testRelease/`；新建 `src/testDebug/` 测试覆盖"TFIC + 天投泊寓"双赛道断言。

#### Scenario: PresetTrackCatalog 无 withContext 调用

- **GIVEN** 实施后 `PresetTrackCatalog.kt`（或等价文件名，tasks 阶段 grep 确认）源码
- **WHEN** grep `withContext` / `Dispatchers.IO` 在 `getAllTracks` body 内
- **THEN** 零匹配（纯 return 内存列表）

#### Scenario: PresetTrackCatalog.getAllTracks 返回预置赛道

- **GIVEN** `PresetTrackCatalog` 实例
- **WHEN** `runTest { catalog.getAllTracks() }`
- **THEN** 返回非空 `List<Track>`，第 0 位 MUST 为 TFIC LPCC（`id == "preset-tfic-lpcc"`）

#### Scenario: release variant 仅含 TFIC

- **GIVEN** `:feature:test:testReleaseUnitTest` 任务（即 `main + release + test + testRelease` 源集组合）
- **WHEN** `runTest { PresetTrackCatalog().getAllTracks() }`
- **THEN** 返回 `List<Track>` 的 `map { it.id }` 严格等于 `listOf("preset-tfic-lpcc")`

#### Scenario: debug variant 额外含天投泊寓

- **GIVEN** `:feature:test:testDebugUnitTest` 任务（即 `main + debug + test + testDebug` 源集组合）
- **WHEN** `runTest { PresetTrackCatalog().getAllTracks() }`
- **THEN** 返回 `List<Track>` 的 `map { it.id }` 严格等于 `listOf("preset-tfic-lpcc", "preset-boyu-loop")`

#### Scenario: extraPresetTracks 在 release 源集返回 emptyList

- **GIVEN** release 源集下 `feature/test/src/release/.../repository/ExtraPresetTracksRelease.kt`
- **WHEN** 阅读 `extraPresetTracks` 函数 body
- **THEN** 函数返回 `emptyList()`，且文件**不**包含天投泊寓 Track 数据或任何 import `com.blazepush.feature.test.model.track.*` 之外的赛道相关引用

#### Scenario: extraPresetTracks 在 debug 源集返回天投泊寓

- **GIVEN** debug 源集下 `feature/test/src/debug/.../repository/ExtraPresetTracksDebug.kt`
- **WHEN** 阅读 `extraPresetTracks` 函数 body
- **THEN** 函数返回包含**1 个**`Track` 的 list，该 Track 满足 `id == "preset-boyu-loop"`、`name.zh == "成都天投泊寓环线"`、`source == TrackSource.Preset`

#### Scenario: main 源集禁止声明 extraPresetTracks 实现

- **GIVEN** 实施后 `feature/test/src/main/` 目录树
- **WHEN** grep `fun extraPresetTracks` 在所有 main 源集 `.kt` 文件中
- **THEN** 零匹配（main 仅可调用该函数，不可声明实现，否则 debug variant 编译时 duplicate JVM declarations）

#### Scenario: release 构建产物零变更

- **GIVEN** 本 round 实施前后两次 release apk 构建产物
- **WHEN** 对比 `:app:assembleRelease` 输出 apk 中 `feature/test` 模块的 `.dex`/资源
- **THEN** release apk 中 `PresetTrackCatalog.getAllTracks()` 返回的 `List<Track>.map { it.id }` 严格等于 `listOf("preset-tfic-lpcc")`；release apk 内含 `ExtraPresetTracksReleaseKt` 类、不含 `ExtraPresetTracksDebugKt` 类、不含 `preset-boyu-loop` 字符串。本 scenario 锁定**行为等价**而非字节等价（`PresetTracks.kt` 加入 `+ extraPresetTracks()` 拼接表达式必然有源码 diff，release 行为靠 src/release 源集 emptyList 实现保证）

## ADDED Requirements

### Requirement: 天投泊寓环线 Track 数据契约（debug variant only）

The system SHALL 在 debug variant 下提供 `id == "preset-boyu-loop"` 的预置 `Track`，几何参数与命名 MUST 严格满足以下契约（坐标全部由 `.rcz` 离线转换而来，约束防止后续偶发 drift）：

- `name.zh == "成都天投泊寓环线"`
- `name.en == "Chengdu Tiantou Boyu Loop"`
- `name.abbr == null`（小型私属环线无业内官方缩写）
- `lengthKm == 2.591`（Lap 1 实测路径长度，3 位小数）
- `thumbnailAssetPath == null`（缩略图为 follow-up，本 round 不提供）
- `source == TrackSource.Preset`
- `referencePath.points.size == 87`（30m 等距重采样自 Lap 1, 2:39.888）
- `startFinishGate.type == TimingGateType.StartFinish`、`startFinishGate.name == "起终点"`
- `sectorGates.size == 4`、`sectorGates.map { it.type } == listOf(StartFinish? Sector ×4)` 即全部为 `Sector`
- `sectorGates.map { it.sequenceIndex } == listOf(1, 2, 3, 4)`（用 Lap 1 实测过线时间反推得到的赛道流向顺序）
- referencePath 全部 sample latitude 落在 `[30.397, 30.407]`、longitude 落在 `[104.054, 104.062]`（Lap 1 bbox 锁死）
- referencePath 首末点距离 ≤ 5m（闭合度，sanity check Lap 1 切片正确）

#### Scenario: 天投泊寓 Track 顶层字段契约

- **GIVEN** `:feature:test:testDebugUnitTest` 下调用 `PresetTrackCatalog().getTrack("preset-boyu-loop")`
- **WHEN** 读取该 Track 的 `name` / `lengthKm` / `thumbnailAssetPath` / `source` / `referencePath.points.size`
- **THEN** 各字段 MUST 等于上述契约值（`name.zh == "成都天投泊寓环线"`、`lengthKm == 2.591`、`thumbnailAssetPath == null`、`source == TrackSource.Preset`、`points.size == 87`、`name.abbr == null`）

#### Scenario: 天投泊寓 4 sector + 1 startFinish gate 顺序契约

- **GIVEN** `:feature:test:testDebugUnitTest` 下调用 `PresetTrackCatalog().getTrack("preset-boyu-loop")`
- **WHEN** 读取 `startFinishGate.type`、`startFinishGate.name`、`sectorGates.size`、`sectorGates.map { it.type }`、`sectorGates.map { it.sequenceIndex }`
- **THEN** `startFinishGate.type == TimingGateType.StartFinish`、`startFinishGate.name == "起终点"`、`sectorGates.size == 4`、`sectorGates` 全部 type 为 `TimingGateType.Sector`、`sectorGates.map { it.sequenceIndex } == listOf(1, 2, 3, 4)`

#### Scenario: 天投泊寓 referencePath 几何边界

- **GIVEN** `:feature:test:testDebugUnitTest` 下 `PresetTrackCatalog().getTrack("preset-boyu-loop")?.referencePath?.points`
- **WHEN** 取所有 sample 的 latitude / longitude
- **THEN** 全部 latitude ∈ [30.397, 30.407] 且全部 longitude ∈ [104.054, 104.062]（赛道 bbox）

#### Scenario: 天投泊寓 referencePath 闭合度

- **GIVEN** `:feature:test:testDebugUnitTest` 下天投泊寓 Track 的 `referencePath.points`
- **WHEN** 计算 `points.first()` 与 `points.last()` 的大圆距离
- **THEN** 距离 ≤ 5m（确认 Lap 1 切片正确闭合）

### Requirement: `.rcz` 编码规则文档化沉淀

The system SHALL 在 `docs/design/rcz-format-decoding.md` 沉淀 `.rcz` 文件格式解码规则，覆盖：

- track 文件内 `centerLatitude` / `centerLongitude` 编码规则（int32，× 6,000,000 得到十进制度）
- `bearing` 编码（int，单位 millidegree，罗经方向 0° = 北，90° = 东）
- `width` 编码（int，单位 mm）
- session 文件内 binary channel 命名规则（`channel_<sessionId>_<deviceId>_<flag>_<channelId>_<sizeFlag>`）
- 关键 channel 用途（channel 1 = timestamp ms int64 LE、channel 2 = 累计距离 mm int64 LE、channel 3 = packed lat/lon 各 int32 ×6e6 LE、channel 4–6 / 30002–30005 暂不消费）
- Lap 1 切片公式（用 `session.json.laps[].startTimestamp` 与 `finishTimestamp` 二分查 channel 1 时间窗）
- 用 TFIC `.rcz` 反推编码规则的方法论（任何后续 `.rcz` 来源都可走同一管线交叉验证）

文档 MUST 用简体中文撰写，MUST 引用本 change 名称作为文档建立背景。

#### Scenario: 文档存在且包含核心字段

- **GIVEN** 本 change 实施完成后的工作树
- **WHEN** 检查 `docs/design/rcz-format-decoding.md`
- **THEN** 文件存在，文档内容包含以下关键串：`6,000,000`、`millidegree`、`mm`、`channel 1`、`channel 3`、`packed`、`Lap 1`、`add-debug-preset-track-boyu-loop`

### Requirement: `.rcz` 离线解码脚本作为工具留档

The system SHALL 在 `docs/tools/decode_rcz_session.py` 提供一个离线 Python 脚本，输入 **session `.rcz` 文件**（其内已自含 `trackId.json` 即 track 定义，无需另传 track `.rcz`），输出可粘贴到 `ExtraPresetTracksDebug.kt` 的 Track DSL 文本片段。脚本 MUST 满足：

- 仅在 `docs/tools/` 留档，**禁止**进入任何 production / Gradle 编译路径
- 自包含、不依赖第三方库（标准库 `zipfile` / `struct` / `math` / `json` 即可）
- 输入参数：**session `.rcz` 路径**（位置参数，必填，必须是 session 文件而非 track 文件，脚本 MUST 通过检查 zip 内是否包含 `session.json` 来校验输入类型）+ 期望取的 lap number（默认 1）+ referencePath 重采样步长米数（默认 30）+ 输出文件路径（默认 stdout）
- 输出：Kotlin DSL 文本片段（含 referencePath 列表、起终点 / sector gate line + passDirection + sequenceIndex；sequenceIndex 由脚本对 channel 3 的 packed lat/lon 在所选 lap 时间窗内对每个 sector trap 跑"最早穿过 gate line"反推得到）
- 脚本顶部注释标明用法、本 change 名称、输入 session `.rcz` 的来源描述（含期望的 sha256，便于离线复现）

#### Scenario: 脚本存在且可直接执行

- **GIVEN** 本 change 实施完成后的工作树
- **WHEN** 检查 `docs/tools/decode_rcz_session.py`
- **THEN** 文件存在，shebang 为 `python3`，开头注释包含 `add-debug-preset-track-boyu-loop` 与用法说明，依赖列表 MUST 仅包含 Python 标准库

#### Scenario: 脚本拒绝非 session 输入

- **GIVEN** 一个仅含 `trackId.json` 的 track-only `.rcz`（无 `session.json`）
- **WHEN** 用该文件作脚本输入运行
- **THEN** 脚本 MUST 退出码非零并打印明确错误，提示 "input must be a session .rcz containing session.json + channel binaries; track-only .rcz is not supported (track definition is read from session's embedded trackId.json)"

### Requirement: `.rcz` 输入文件 + 脚本输出 DSL 受控落 git

The system SHALL 把以下输入与产物文件落入 `docs/tools/input/` 与 `docs/tools/output/`，使 apply 阶段（以及未来回看 / 审计 / 复现）无需依赖任何项目外部目录或聊天上下文：

- `docs/tools/input/track_天投泊寓环线.rcz`（track-only `.rcz`，仅作格式参考与未来对比；本 round 不直接消费）
- `docs/tools/input/session_20260108_225454_天投泊寓环线.rcz`（session `.rcz`，**本 round 实际使用**）
- `docs/tools/input/README.md`：登记两份 `.rcz` 的来源描述（用户从 RaceChrono / Race-Captain 风格采集设备导出）+ sha256 期望值（track: `ca9fc2c3a59750e4...`、session: `666b501cb2d074cf...`，完整 sha256 由 task 1.0 落地时实测填入）+ 隐私声明（含 GPS 坐标，已对外公开范围内可用，无需脱敏）
- `docs/tools/output/decode_rcz_session_boyu_loop.txt`（脚本输出的 Kotlin DSL 文本片段，作为可审查产物，apply 阶段直接复制粘贴到 `ExtraPresetTracksDebug.kt`）

#### Scenario: 输入文件存在且 sha256 与登记一致

- **GIVEN** 本 change 实施完成后的工作树
- **WHEN** 计算 `docs/tools/input/track_天投泊寓环线.rcz` 与 `docs/tools/input/session_20260108_225454_天投泊寓环线.rcz` 的 sha256
- **THEN** 两值与 `docs/tools/input/README.md` 登记的期望值一致；文件存在于 git 仓库（`git ls-files` 命中）

#### Scenario: 脚本输出 DSL 文本可审查

- **GIVEN** `docs/tools/output/decode_rcz_session_boyu_loop.txt`
- **WHEN** 文本与 `ExtraPresetTracksDebug.kt` 内 `boyuLoopTrack` 的关键字段（`referencePath` 全部 87 点 + 起终点 line + 4 个 sector line/passDirection/sequenceIndex）做 spot check
- **THEN** 字段值一一对应（坐标允许 ≤ 1e-6° 浮点精度差异）
