## Context

**现状**：
- `feature/test/src/main/java/com/blazepush/feature/test/repository/PresetTracks.kt` 维护一个静态 `internal val presetTracks: List<Track> = listOf(<TFIC>)`。
- `PresetTrackCatalog.getAllTracks()` 直返该列表（无 `withContext`，由 `track-catalog-hot-start` 锁定为内存直返）。
- `feature/test/src/test/.../TrackCatalogTest.getAllTracks_exposesOnlyTficLpccPreset` 用 `assertEquals(listOf("preset-tfic-lpcc"), ids)` 锁死"只有一条 preset"。
- `feature/test` 当前**不存在** `src/debug/` 与 `src/release/` 源集，只有 `main` 与 `test`。
- AGP 自动行为：debug 构建合并 `main + debug` 源集，release 构建合并 `main + release`；JVM 单测则按 `:feature:test:testDebugUnitTest` / `testReleaseUnitTest` 分别合并 `main+debug+test+testDebug` / `main+release+test+testRelease`。

**新数据来源**：
- 用户提供两份 RaceChrono / Race-Captain 风格 `.rcz` ZIP：track 定义 + 同赛道 4 圈 25 Hz session 实测。
- 已通过用 TFIC `.rcz` 与现有 `PresetTracks.kt` 的坐标交叉验证，反推出编码规则：坐标 ×6,000,000、bearing 千分度（罗经）、width 毫米。
- ESP32 真机当前位置 `(30.39527°N, 104.06677°E)` 与解码后赛道中心 `(30.4017°N, 104.0580°E)` 距离 ~700m，与用户口述"3-500m"同量级，几何与位置完全匹配。

**约束 / Stakeholders**：
- CC 实施 + Codex review；用户决策与真机验证（华为 `8KE0219522008434`）。
- 本 round 不动公共协议（RaceChrono BLE / replay JSON / binary）。
- 本 round 不污染现有 main runtime 流程，新增能力按"feedback_avoid_polluting_existing_code"隔离原则收口到 debug 源集。

## Goals / Non-Goals

**Goals**：
- debug apk 装到真机后，赛道选择列表可见 2 条预置：TFIC LPCC + 天投泊寓环线；release apk 仍只可见 TFIC。
- 新赛道的 referencePath / 5 个 gate 几何参数全部从 `.rcz` 实测数据离线生成，**不**依赖运行时解析私有格式。
- `.rcz` 编码规则一次性沉淀为可复用文档 + 离线脚本，未来再导别的 `.rcz` 走同一管线。

**Non-Goals**：
- ❌ 不实现 runtime `.rcz` parser（避免引入私有格式依赖到 production 代码）。
- ❌ 不为天投泊寓提供 replay-aligned 拟合（`ReplayAlignedTrackCatalog` 不动）。
- ❌ 不补缩略图（`thumbnailAssetPath` 设 `null`，follow-up round 单独补图）。
- ❌ 不动 `LapTimingEngine` / `TimingGate` 几何计算 / 圈速判定逻辑。
- ❌ 不为 debug 包额外加 BuildConfig flag / runtime 判断 / Koin 模块差异。

## Decisions

### D1：Debug 隔离机制选 Android Gradle 互斥源集（`src/debug/` + `src/release/`），不选 BuildConfig.DEBUG

**选择**：`feature/test` 模块下新建**两个**变体源集，各自提供同名 `internal fun extraPresetTracks(): List<Track>` 的实现：
- `src/debug/java/com/blazepush/feature/test/repository/ExtraPresetTracksDebug.kt`：返回 `listOf(<天投泊寓>)`
- `src/release/java/com/blazepush/feature/test/repository/ExtraPresetTracksRelease.kt`：返回 `emptyList()`

`main` 源集**不**提供 `extraPresetTracks()` 实现，仅在 `PresetTracks.kt` 中调用它。

**为什么**：
- AGP 在 debug variant 下把 `main + debug` **合并**进同一 KotlinCompile（同一编译单元，不是覆盖）；release variant 把 `main + release` 合并。两个变体源集与 main 共存，**不互相可见**。
- 如果 `main` 与 `debug` 同时声明同包同签名 top-level 函数，编译器报 `duplicate JVM declarations`；只有 `debug` 与 `release` **互斥**（不会同时进入同一编译单元），才能各自独立提供同签名实现。
- 不需要运行时分支，没有 `if (BuildConfig.DEBUG) { ... }` 留在 release 字节码里。
- 命名 `extraPresetTracks`（中性）而非 `extraDebugTracks`，因为 release 源集也要提供这个函数；用 "Debug" 词在 release 文件名里语义错位。

**Alternatives 排除**：
- 让 main 提供默认空实现 + debug 源集"整文件覆盖"：**已证伪**，AGP 不支持源集级文件覆盖（资源文件可以，Kotlin 源码不行）；同包同函数会 duplicate declaration。Codex review P1.1 命中。
- BuildConfig.DEBUG 运行时判断：违反"不污染 main runtime"约束；release 字节码里依然带有死分支。
- Koin module 差异：需要在 `app` 模块加 build-type 配置，影响范围更大。
- Gradle product flavor：体量过重，单条 debug-only 赛道不值得引入 flavor 维度。

### D2：钩子函数 `extraPresetTracks` 放独立文件，main 仅持有调用点

**选择**：
- `feature/test/src/main/.../repository/PresetTracks.kt`：保留 `internal val presetTracks: List<Track>`，但定义改为 `mainPresets + extraPresetTracks()`，其中 `mainPresets` 是原 listOf(<TFIC>)。**`PresetTracks.kt` 不声明 `extraPresetTracks` 函数本体**。
- `feature/test/src/release/.../repository/ExtraPresetTracksRelease.kt`（**新增**）：`internal fun extraPresetTracks(): List<Track> = emptyList()`。
- `feature/test/src/debug/.../repository/ExtraPresetTracksDebug.kt`（**新增**）：`internal fun extraPresetTracks(): List<Track> = listOf(<天投泊寓>)`。

**为什么**：
- 函数实现拆到变体源集独立文件，main 中的 `PresetTracks.kt` 只看到 `extraPresetTracks()` 这个外部符号，编译期由 variant 解析具体实现。
- `PresetTracks.kt` 的 main 改动仅 1 处（list 拼接调用 `extraPresetTracks()`），TFIC 数据原文不动。
- 文件名带 `Debug` / `Release` 后缀（不只靠目录区分），方便 grep / IDE 跳转 / code review 一眼判断这是 variant-specific 文件。

### D3：referencePath 用 Lap 1 实测 path，30m 等距步长降采样到 87 点

**选择**：从 `session_20260108_225454_天投泊寓环线.rcz` 的 channel 1（timestamp）+ channel 3（packed lat/lon × 6e6）裁出 Lap 1 时间窗 `[startTimestamp=1767884378576, finishTimestamp=1767884538464]` 内的 sample（共 3993 点），按沿弧长 30m 等距重采样到 87 点。

**为什么**：
- Lap 1 是 session 中的 best lap（2:39.888），轨迹质量代表赛道形状。
- Lap 1 闭合度（首末点距离）1.9m，说明圈完整且 Lap 边界正确。
- 30m 步长 → 87 点；TFIC 用 12 点（直线为主大型场地），天投泊寓多弯小型环线密度高一些合理；同时点数控制在百级以内，UI 渲染零压力。
- 不直接保留 25 Hz 全 3993 点：避免 referencePath 体积膨胀且无视觉收益。

**Alternatives 排除**：
- Optimal lap（149.302s 由 session metadata 给出，但是合成最佳分段、没有连续 GPS path）：拼出来不闭合，几何上不能用作 path。
- Douglas-Peucker 简化：可压更少点但弯道顶点采样可能过粗；30m 等距更稳。
- 用 5 个 gate 中心点连线代替：5 点不能描绘环线形状，UI 缩略图与几何检验都会错位。

### D4：Sector sequenceIndex 用 Lap 1 实测过线时间排序

**选择**：在离线脚本里对 4 个 sector gate 各自跑一次"Lap 1 内最早穿过 gate line 的 sample 时间戳"，按时间升序赋 `sequenceIndex = 1, 2, 3, 4`。

**为什么**：
- `.rcz` `traps[].orderValue` 全是 0，无显式顺序信息；`traps[]` 数组顺序 ≠ 流向顺序（已观察）。
- 用实测过线时间是唯一可靠的反推依据，且与 `LapTimingEngine` 的运行期 sector 检测语义一致。

**Alternatives 排除**：
- 手工目视排序：5 个 gate 在地图上肉眼可判，但容易错且不可复现。
- 按几何最近邻链构图：环线弯道密集时容易选错走向。

### D5：单元测试拆分为 main+testRelease vs testDebug 两套断言

**选择**：
- 现有 `TrackCatalogTest.getAllTracks_exposesOnlyTficLpccPreset` 移出 main `test` 源集，搬到新建的 `feature/test/src/testRelease/.../TrackCatalogReleaseVariantTest.kt`，断言 `assertEquals(listOf("preset-tfic-lpcc"), ids)` 不变。
- 新建 `feature/test/src/testDebug/.../TrackCatalogDebugVariantTest.kt`，断言 `assertEquals(listOf("preset-tfic-lpcc", "preset-boyu-loop"), ids)`（**顺序由 `mainPresets + extraPresetTracks()` 拼接顺序决定**）。
- 现有 `getTrack_locksTficLpccCoordinateContractWithReplayAlignedPresetConstants` 与 `getTrack_returnsNullForUnknownTrackId` 在 `src/test/`（共享）保留，行为不受新增 preset 影响。
- 新建 `feature/test/src/testDebug/.../BoyuLoopPresetTest.kt`，对天投泊寓 Track 的关键字段做坐标契约断言（同 TFIC 模式：name/lengthKm/source/gate count/sequenceIndex/path 边界 + 关键 path 点）。

**为什么**：
- AGP 单测把 `testDebug` / `testRelease` 源集分别合并进 `testDebugUnitTest` / `testReleaseUnitTest`，可以放心做 variant-specific 断言；CI 跑两个 variant 即两套都执行。
- 不破坏现有 TFIC 契约（继续锁死）。

**Alternatives 排除**：
- 把现有断言改成"包含 TFIC 即可"放宽：丢掉了"release 包不含其它 preset"这条 release-only 强契约。
- 只在真机验证：CI 单测覆盖丢失，回归易漏。

### D6：`Track.id = "preset-boyu-loop"`、`name.zh = "成都天投泊寓环线"`、`abbr = null`

**选择**：
- `id`：与 `preset-tfic-lpcc` 同命名风格，kebab-case `preset-boyu-loop`。
- `name.zh`：源 `.rcz` 内为"天投泊寓环线"，前缀加"成都"以与 TFIC 同地名前缀风格统一。
- `name.en`：`Chengdu Tiantou Boyu Loop`（粗略音译；非官方赛道名，无英文官方称呼。"天投" → 拼音 Tiantou，Codex review 修正笔误：之前误写 Tianpou）。
- `name.abbr`：`null`（小型私属环线无业内官方缩写，遵循 `track-presentation` 现有约定）。
- `lengthKm`：`2.591`（Lap 1 实测路径长度，保留三位小数对齐 TFIC 的 `3.260`）。
- `thumbnailAssetPath`：`null`（follow-up round 补图）。
- `source`：`TrackSource.Preset`（与 TFIC 一致）。

## Risks / Trade-offs

- **[R1]** Debug variant 单元测试依赖 AGP `testDebug` / `testRelease` 源集机制，若团队 CI 只跑 `:feature:test:test`（不区分 variant），则只跑共享 `test` 源集，variant-specific 断言不会执行。
  → **Mitigation**：在 tasks 阶段确认 CI 命令包含 `:feature:test:testDebugUnitTest` 与 `:feature:test:testReleaseUnitTest`；如缺失则补 CI 配置（写进 follow-up）。本 round 至少在 CC 本地两个 variant 各跑一次。
- **[R2]** `extraPresetTracks()` 钩子是 internal top-level 函数，由 `src/debug/` 与 `src/release/` **互斥源集**各提供一份实现。若开发者将来在 `main` 源集再声明一个同名同签名函数（误以为给 main 加默认实现），会触发 `duplicate JVM declarations` 编译错误。
  → **Mitigation**：在 `ExtraPresetTracksDebug.kt` 与 `ExtraPresetTracksRelease.kt` 文件顶部加一行注释，明确"本函数 MUST 仅在 src/debug + src/release 双源集各提供一份；main 源集禁止声明同签名函数"。Codex review P1.1 已校准过本机制，再发生时直接对照 design D1。
- **[R3]** 87 点 referencePath 比 TFIC 的 12 点**多 7×**，对 Compose Canvas 渲染或 stroke path 构造有微弱性能影响。
  → **Mitigation**：87 点对 Compose Path 构造毫秒级；UI 性能验证放进真机 gate（确认 LapsHomeScreen / SelectTrackBottomSheet 渲染顺滑）。
- **[R4]** 若 RaceChrono 的 `traps[]` 数组顺序其实**就是**赛道流向顺序（与本设计 D4 假设相反），D4 的"实测过线时间反推 sequenceIndex"会与"数组顺序"撞结果或错位。
  → **Mitigation**：D4 的实测反推是更稳的基线（与 engine 一致），即便撞结果也无害。如果未来某个新赛道的 traps 顺序与实测过线顺序一致，照样工作；不一致就以实测为准。

## Migration Plan

无 schema / 持久化变化，无 BREAKING；release apk 完全不受影响。debug apk 增量行为：选择列表多 1 条赛道。

**Rollback**：把 `feature/test/src/debug/.../ExtraPresetTracksDebug.kt` 内的 `boyuLoopTrack` 引用替换为 `emptyList()`（与 `src/release/` 版本一致），即可在不删任何文件的情况下回退到"无 boyu loop"状态；如要彻底回退，删除 `src/debug/` 与 `src/release/` 两个 ExtraPresetTracks 文件 + 把 `PresetTracks.kt` 内 `mainPresets + extraPresetTracks()` 改回 `mainPresets`，等价于本 round 未实施。

## Open Questions

- ⚠ 缩略图 `track_thumbnails/boyu_loop.png` 来源（卫星截图 / 本地实拍）→ 单独 follow-up round；本 round 用 `thumbnailAssetPath = null`，UI 在"无缩略图"分支应已有 fallback（待 tasks 阶段实地验证）。
