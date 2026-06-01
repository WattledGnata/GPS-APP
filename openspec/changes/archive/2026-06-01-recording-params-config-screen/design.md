## Context

Phase 2 视频管线（拍摄 / 回放 / 导出）已闭环，但录制参数硬编码（见 proposal Why）。`RecordingConfig`（`feature/test/.../recording/RecordingConfig.kt`）当前：

```kotlin
enum class RecordingResolution { FHD_1080P }
data class RecordingConfig(
    val resolution: RecordingResolution = RecordingResolution.FHD_1080P,
    val targetFps: Int = 30,
) { companion object { val DEFAULT = RecordingConfig(FHD_1080P, 30) } }
```

`CameraRecordingEngine.bind(lifecycleOwner, context, config)` 已接受 config，但只 `when(resolution) { FHD_1080P -> QualitySelector.from(Quality.FHD) }`，麦克风 / 摄像头 / 对焦 / 曝光均硬编码或缺失。

**约束**：CameraX `1.3.4`（升 1.4+ 需 compileSdk 35，本轮不升）；DataStore Preferences `1.0.0` 已在 `feature/test`，先例 `datastore/RecentTracksStore.kt`；完整 API 可行性见 `docs/design/recording-params-config-deferred.md` §2.2（逐项）+ §5（M1–M7 MUST 条款）。本轮 road-test-first 模式（无 Codex / 无 Opus 子 agent；靠 CC 自审 + FileLogger + 真机）。

## Goals / Non-Goals

**Goals:**
- 用户可在录制前配置 5 类参数（清晰度 / 麦克风 / 前后置 / 曝光 EV / 对焦），持久化跨会话保留。
- 对焦支持"锁定无限远"（用户路测核心诉求）。
- 设备不支持的项（4K / 超出 EV 范围）安全降级、UI 透明显示，不崩。
- 改动集中 `feature/test`，不触碰公共协议 / 圈速 / GPS 接收链路。

**Non-Goals:**
- 60fps 精确锁定（需升 CameraX 1.4+ / compileSdk 35）——本轮 compileSdk 保持 34，UI 不暴露 60fps。
- H.265 编码、视频防抖——延后（需运行时能力检测 / 真机稳定性验证）。
- 录制中动态改参数（只在 Idle 态允许进设置）。
- 快捷面板 / 录制前 BottomSheet（本轮只做全量设置屏，C 方案延后）。

## Decisions

### Decision 1：配置屏形态 = 全量独立设置屏（RecordingSettingsScreen）

5 个参数列在一个独立 Compose 屏，分组（画质 / 音频 / 摄像头 / 对焦曝光）但不折叠。

- **Alternative A（选中）·全量设置屏**：所有参数一屏可见，最接近 RaceChrono 范式，工作量边界清晰、可独立归档。
- **Alternative B·分组折叠 ExpandableSection**：参数多时不压迫，但本轮仅 5 项，折叠收益低且折叠态要持久化，徒增复杂度 → 拒绝。
- **Alternative C·录制前快捷 BottomSheet**：低摩擦但需两个入口（快捷 + 全量）同步状态，复杂度高；用研未证实需求 → 延后。

**Rationale**：5 项规模一屏不压迫，A 实现最直接、review 焦点集中。

> **实施期决策修订（2026-06-01 · road-test 反馈）**：A 方案原实现为**独立 NavHost 路由屏** `RecordingSettingsScreen(navController)`。真机路测暴露两个问题：① 导航到独立 route 会让 `LapLiveScreen` 离开 composition → DisposableEffect 复位**竖屏** + unbind 相机，体验割裂；② 离开预览页**看不到调参数的实时效果**（曝光/对焦/前后置）。**修订为浮层** `RecordingSettingsOverlay(onDismiss)`：浮在 `CameraPreviewPage` 之上（不跳路由 / 不开 activity），由本页 `showRecordingSettings` state 控制显隐，左侧 dismiss 区露出实时预览、右侧 360dp 半透明面板。收益：**横屏保持**（LapLiveScreen 不离开 composition）+ **相机不解绑、改参数经 rebind 即时反映到预览**（曝光滑块拖动只动 draft、松手才落库，避免 rebind 风暴）+ 轻量。仍 Idle-only（齿轮 `enabled = recordingState is Idle`）。Alternative B/C 评估不变（仍拒绝）。属交互形态修订（route→overlay），非数据流 / capability 边界变化。

### Decision 2：持久化 = DataStore Preferences（仿 RecentTracksStore）

`RecordingPreferencesRepository` 包 `DataStore<Preferences>`，key 集中 `RecordingPrefsKeys` object；读出 `Flow<RecordingConfig>`，缺 key 走 `RecordingConfig.DEFAULT` 字段默认。

- **Alternative A（选中）·DataStore Preferences**：已在工程（零新依赖）、有 `RecentTracksStore` 先例可仿、官方推荐、config 是 singleton 正合 key-value。
- **Alternative B·DataStore Proto**：强类型 + schema 演化安全，但要引 protobuf 依赖 + build 复杂度上升；5 个标量字段不值得 → 拒绝。
- **Alternative C·Room Entity**：杀鸡用牛刀，config 是 singleton 设置非列表数据；且会牵动 Room schema migration（本轮明确不碰）→ 拒绝。
- **Alternative D·SharedPreferences**：同步 IO + deprecated 方向，工程已转 DataStore → 拒绝。

**Rationale**：零依赖 + 有先例 + 语义匹配，工作量最小。

### Decision 3：RecordingConfig 就地扩展字段（不拆新类）

在现有 data class 加 `audioEnabled / cameraFacing / focusMode / exposureCompensationEv`，`RecordingResolution` 枚举加 `UHD_4K / HD_720P`；全部带默认值（向后兼容 `DEFAULT`）。

- **Alternative A（选中）·就地扩展**：扩展点已在 `RecordingConfig` 注释里预留（"后续设置屏 round 按需扩展"）；`bind(config)` 签名不变；新字段带默认值，旧调用 `RecordingConfig.DEFAULT` 不破。
- **Alternative B·拆独立 CameraTuning 类组合进 config**：分层更"干净"但引入嵌套 + 映射成本，5 个标量字段不需要 → 拒绝。

**Rationale**：最小改动、零破坏，符合预留扩展点设计。

### Decision 4：对焦无限远 = Camera2Interop 附加到 Preview.Builder

`focusMode == LOCKED_INFINITY` 时，对 `Preview.Builder` 用 `Camera2Interop.Extender` 设 `CONTROL_AF_MODE = OFF` + `LENS_FOCUS_DISTANCE = 0f`（0 屈光度 = 无限远）；`CONTINUOUS_AUTO` 时不附加（走 HAL 默认连续自动）。

- **Alternative A（选中）·Camera2Interop on Preview.Builder**：CameraX 1.3.4 `Preview.Builder` 实现 `ExtendableBuilder`，可附 Camera2 capture request option（memo §2.2 确认可行）。这是 1.3.4 唯一能锁对焦的路径。
- **Alternative B·cameraControl 运行时 API**：`CameraControl` 不暴露 lens focus distance（只有 `startFocusAndMetering` 点测对焦），无法锁无限远 → 技术不可行，拒绝。
- **Alternative C·完全不做对焦（延后）**：但对焦无限远是用户路测核心诉求，不能延 → 拒绝。

**Rationale**：1.3.4 内唯一可行且满足用户诉求的路径。**真机验证项**：低端设备 `LENS_FOCUS_DISTANCE` 支持度差异 → 见 Risk 3。

### Decision 5：4K 清晰度 = QualitySelector.fromOrderedList 降级（绝不 from(UHD)）

`resolveEffectiveQuality(requested, supported)` 纯函数 + `QualitySelector.fromOrderedList(listOf(UHD, FHD, HD), FallbackStrategy.lowerQualityThan(...))`；UI 显示实际选中（可能被降级）。

- **Alternative A（选中）·fromOrderedList + fallback**：不支持 UHD 的设备自动降到 FHD，不崩（memo M2）。
- **Alternative B·QualitySelector.from(Quality.UHD)**：在不支持 4K 的设备上**直接崩溃** → 拒绝（这正是 M2 要防的）。

**Rationale**：设备能力差异大，必须降级兜底；`resolveEffectiveQuality` 抽纯函数便于单测穷举。

### Decision 6：能力探测时机 = 进设置屏时查（RecordingCapabilityDetector）

进 `RecordingSettingsScreen` 时查 `cameraProvider` 的 supported `Quality` 列表 + `cameraInfo.exposureState.exposureCompensationRange`，据此渲染（4K 灰显 / EV 滑块范围）。

- **Alternative A（选中）·进屏时探测**：数据随设备 / 当前 facing 实时准确；探测一次性、开销小。
- **Alternative B·app 启动时探测 + 缓存**：启动开销 + facing 切换后缓存失效（前后摄能力不同）→ 拒绝。

**Rationale**：进屏探测最准（尤其前后摄能力不同），开销可忽略。

### Decision 7：参数生效 = 改完 rebind（不在线重配）

config 变更经 repository 持久化后，回到录制页时 `CameraRecordingEngine` 用新 config `unbindAll + bindToLifecycle` 重绑；切摄像头若录制中 MUST 先 stop（memo M1/M7）。入口只在 `RecordingState.Idle` 可达，从根上避免录制中改参数。

- **Alternative A（选中）·rebind**：`Recorder` 参数绑定后不可变，重配只能 rebind；Idle-only 入口避开录制中改参数的所有边界。
- **Alternative B·在线 reconfigure 已绑 Recorder**：CameraX 不支持改已绑 Recorder 的 QualitySelector 等 → 技术不可行，拒绝。

**Rationale**：CameraX 约束决定只能 rebind；Idle-only 入口是最简洁的安全保证。

### Decision 8：本轮不升 compileSdk / 不暴露 60fps

compileSdk 保持 34、CameraX 1.3.4；`targetFps` 字段保留作扩展点但 UI 不给 60fps 选项（memo M3/M6）。

- **Alternative A（选中）·不升级**：60fps 强依赖 CameraX 1.4+（compileSdk 35），牵动 AGP / Lint / 全工程回归，是独立工程债，不该绑进一个 UI 配置 round。
- **Alternative B·本轮捆绑升 compileSdk 35**：把工程级风险塞进功能 round，scope 膨胀 + review 焦点分散 → 拒绝（若将来要 60fps，独立立 `upgrade-compilesdk-35` 前置 round）。

**Rationale**：隔离工程级风险，保持本轮 medium 边界。

## Risks / Trade-offs

- **[Risk 1] 4K 设备不支持导致崩溃** → Decision 5 `fromOrderedList` fallback；`resolveEffectiveQuality` 纯函数单测穷举 UHD-不支持 / 全支持 / 仅 HD 等 case；UI 显示实际选中清晰度。
- **[Risk 2] 曝光 EV 范围设备差异（可能是 -2~+2 不是 -5~+5）** → 运行时读 `exposureCompensationRange` 渲染滑块范围；`clampEv(requested, range)` 纯函数兜底越界值；不硬编码 UI 范围（memo M5）。
- **[Risk 3] 对焦无限远在部分设备无效 / 取景异常** → Camera2Interop 是 `@OptIn(ExperimentalCamera2Interop)`；不同设备 `LENS_FOCUS_DISTANCE` 校准不一。Mitigation：真机验证（默认设备 + 小屏机）；设备不支持 MANUAL AF 时 HAL 会忽略 option（退化为连续自动，不崩）；FileLogger 记录所选 focusMode + 是否附加 interop，路测可诊断。
- **[Risk 4] 切前后摄 / 改参数发生在录制中** → 入口 `RecordingState.Idle`-only（设置齿轮录制态 disabled）+ rebind 前 stop（memo M1/M7）；从交互层杜绝。
- **[Risk 5] DataStore 异步读取首帧拿不到值** → `Flow<RecordingConfig>` 以 `RecordingConfig.DEFAULT` 为缺省；进录制页若 config 未就绪用 DEFAULT，不阻塞。
- **[Trade-off] 全量设置屏需用户主动进入** → 本轮接受（A 方案）；高频参数的快捷面板（C）留作后续 follow-up，不阻塞本轮。

## Migration Plan

- **无 schema migration**：DataStore Preferences 无版本概念；新增 key 在旧安装上缺省 → 读取走 `RecordingConfig.DEFAULT` 字段默认值，自然向后兼容。
- **部署**：随 app 内部 debug apk 真机验证；无服务端 / 无双端协同。
- **Rollback**：若设置屏出问题，移除录制页齿轮入口即可——`CameraRecordingEngine` 仍可用 `RecordingConfig.DEFAULT` 工作（与本轮前行为一致），无数据残留风险。

## Open Questions

- **对焦无限远的真机生效度**（Risk 3）属真机验证项，非阻塞设计——设计已给"设备忽略即退化连续自动"的安全降级路径。无其它悬而未决的设计层问题。
