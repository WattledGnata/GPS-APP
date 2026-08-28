# 延期立项设计 Memo：录制参数配置屏（recording-params-config-screen）

**延期决策时间**：2026-05-30  
**延期决策原因**：用户调研 RaceChrono 参数清单后明确"一期给默认值，后面再加配置屏"——配置屏是独立 UX 工作量，CameraX 部分 API 需升级评估，当前批次优先完成核心录制管线闭环。  
**建议 round 名**：`recording-params-config-screen`  
**源 round**：`recording-persist-across-pages-and-hud-indicator`（Phase 2 视频管线基础设施批）

---

## 1. 现状

### 1.1 当前录制基础设施

Phase 2 视频管线已在 `feature/test/recording/` 落地：

- **`RecordingConfig.kt`**（`feature/test/src/main/java/com/blazepush/feature/test/recording/RecordingConfig.kt`）：参数 data class，当前字段：
  - `resolution: RecordingResolution = RecordingResolution.FHD_1080P`（枚举，当前仅 `FHD_1080P`）
  - `targetFps: Int = 30`（注释说明"Camera2 Interop hint，不保证精确锁定"）
  - `companion object { val DEFAULT = RecordingConfig(FHD_1080P, 30) }`
  - 注释明确标注"后续设置屏 round 由外部传入不同 config，引擎 API 不变"——扩展点预留完整

- **`CameraRecordingEngine.kt`**（`feature/test/src/main/java/com/blazepush/feature/test/recording/CameraRecordingEngine.kt`）：
  - `bind(lifecycleOwner, context, config)` 接受 `RecordingConfig`，通过 `when(config.resolution)` 映射 `QualitySelector`
  - 当前 `when` 分支只有 `FHD_1080P → QualitySelector.from(Quality.FHD)`
  - fps 由 QualitySelector + 设备 Camera HAL 决定（`Recorder.Builder` 不实现 `ExtendableBuilder`，无法 Camera2Interop 锁 fps）——已在代码注释和 design.md Decision 3 透明声明
  - 麦克风：硬编码 `withAudioEnabled()`（`startRecording` 内）
  - 摄像头：硬编码 `CameraSelector.DEFAULT_BACK_CAMERA`（`bind` 内）
  - 防抖、对焦、曝光：无任何控制代码

### 1.2 参数现状汇总

| 参数 | 当前状态 | 硬编码位置 |
|---|---|---|
| 视频清晰度 | FHD 1080p 硬编码 | `CameraRecordingEngine.bind` → `QualitySelector.from(Quality.FHD)` |
| 帧率 | 30fps（设备 HAL 决定）| `targetFps=30` 字段存在但引擎未控制 |
| 编码格式 | H.264（CameraX 默认）| CameraX `Recorder` 默认；未显式设置 `MediaSpec` |
| 麦克风 | 开 | `prepareRecording(...).withAudioEnabled()` |
| 摄像头 | 后置 | `CameraSelector.DEFAULT_BACK_CAMERA` |
| 对焦 | 连续自动对焦（HAL 默认）| 无控制代码 |
| 曝光 | EV 0（HAL 默认）| 无控制代码 |
| 防抖 | 关（HAL 默认）| 无控制代码 |

---

## 2. 数据证据

### 2.1 RaceChrono 参数清单（用户调研，2026-05-30）

RaceChrono 为专业赛道圈速 App，其录制参数设置屏覆盖以下 8 个维度，是本工程配置屏立项的直接参照：

| 参数 | RaceChrono 一期默认 | RaceChrono 可选项 |
|---|---|---|
| 视频清晰度 | 1080p (FHD) | 4K(UHD) / 1080p / 720p(HD) |
| 帧率 | 30fps | 30 / 60 |
| 编码格式 | H.264 (AVC) | H.264 / H.265 (HEVC) |
| 麦克风 | 开 | 开 / 关 |
| 摄像头 | 后置 | 前 / 后 切换 |
| 对焦 | 连续自动对焦 | 自动 / 锁定无限远 |
| 曝光 | 标准 (EV 0) | -5 ~ +5 (EV 补偿) |
| 防抖 | 关 | 开 / 关 |

### 2.2 CameraX 1.3.4 API 可行性逐项评估

**当前工程 CameraX 版本**：`camera-core:1.3.4`（`libs.versions.toml` 或 `core/camera` build.gradle 中声明）。
**当前 compileSdk**：34（升级 CameraX 到 1.4.0+ 需要 compileSdk 35，这是硬约束）。

| 参数 | CameraX 1.3.4 可行性 | 具体 API | 限制说明 |
|---|---|---|---|
| **视频清晰度** | ✅ 完全可行 | `QualitySelector.from(Quality.UHD/FHD/HD)`；`Recorder.Builder().setQualitySelector(...)` | 4K 需做设备能力 fallback（`QualitySelector.fromOrderedList(listOf(UHD, FHD), FallbackStrategy.lowerQualityThan(UHD))`）；低端设备不支持 UHD |
| **帧率** | ⚠️ 受限（不可精确锁定）| 理论：`Camera2Interop(Recorder.Builder()).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, ...)` | `Recorder.Builder` 在 1.3.4 **不实现 `ExtendableBuilder`**，`Camera2Interop` 无法附加；60fps 帧率控制**需升级 CameraX 到 1.4.0+（要求 compileSdk 35）**；一期只能接受设备 HAL 默认帧率 |
| **编码格式** | ⚠️ 部分可行 | `MediaSpec.Builder().configureVideo { it.setVideoEncoderSpec(VideoEncoderConfig.defaultConfig(VideoEncoderInfo.MIME_TYPE_H265)) }` + `Recorder.Builder().setAspectRatio(...)` | H.265 API 在 1.3.4 存在但设备支持不一致（需运行时检测 `EncoderProfiles`）；H.264 是默认安全选项 |
| **麦克风** | ✅ 完全可行 | `prepareRecording(...).withAudioEnabled()` / 不调用则无音频 | 关闭麦克风可不申请 `RECORD_AUDIO` 权限（但当前已申请，关闭只是不调用 withAudioEnabled）|
| **摄像头** | ✅ 完全可行 | `CameraSelector.DEFAULT_BACK_CAMERA` / `CameraSelector.DEFAULT_FRONT_CAMERA` | 切换需重新 `bind()`（`unbindAll + bindToLifecycle`）；切换时录制中须先 stop |
| **对焦** | ⚠️ 需 Camera2Interop | `Camera2Interop(Preview.Builder()).setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)` + `LENS_FOCUS_DISTANCE = 0f`（无限远）| CameraX 1.3.4 `Preview.Builder` 实现 `ExtendableBuilder` ✅，可用 Camera2Interop；但无限远对焦锁在 UI 上的必要性需结合用户场景验证 |
| **曝光** | ✅ 完全可行 | `camera.cameraControl.setExposureCompensationIndex(ev)` | 需查设备 `camera.cameraInfo.exposureState.exposureCompensationRange` 确认支持范围；不同设备 EV 步进不同 |
| **防抖** | ⚠️ 部分可行，需升级确认 | CameraX 1.3.4：`VideoCapture.Builder<Recorder>().setVideoStabilizationMode(StabilizationMode.ON)` | `StabilizationMode` API 在 1.3.4 是否稳定需确认（1.4.0 才正式 stable）；设备支持率差异大；开启后帧率/分辨率可能降级 |

### 2.3 4K/60fps 文件体量与发热量化估算

**文件体量**（参考 H.264 编码比特率标准）：

| 清晰度/帧率 | 典型比特率 | 1 小时文件大小 | 30 分钟赛事 |
|---|---|---|---|
| 720p / 30fps | ~8 Mbps | ~3.6 GB | ~1.8 GB |
| 1080p / 30fps | ~16 Mbps | ~7.2 GB | ~3.6 GB |
| 1080p / 60fps | ~24 Mbps | ~10.8 GB | ~5.4 GB |
| 4K / 30fps | ~45 Mbps | ~20 GB | ~10 GB |
| 4K / 60fps | ~80 Mbps | ~36 GB | ~18 GB |

**结论**：1080p/30fps 是平衡点；4K 的内部存储压力极大（手机存储通常 64-256GB，一次赛事可能占用 5-20%）。

**发热估算**：4K/60fps 持续录制 30 分钟，GPU/ISP 功耗约为 1080p/30fps 的 2-3 倍；在中端 Android 设备（骁龙 700 系列）上会触发热节流（thermal throttle），导致帧率主动降级或强制停录。

**H.265 省空间比例**：H.265 vs H.264 相同质量下文件体积减少 30-50%。但 H.265 编码 CPU 开销大，老设备（Android 9 以下）硬件解码器支持不保证，分享到第三方平台兼容性差。

---

## 3. 方案对比

### 3.1 配置屏 UI 形态

| 方案 | 描述 | 优点 | 缺点 |
|---|---|---|---|
| **A：一次性全量设置屏** | 独立 `RecordingSettingsScreen`，列出所有 8 个参数，滚动列表或分组 | 最接近 RaceChrono 范式；参数关系一目了然 | 用户打开设置屏才能改；适合高级用户 |
| **B：分组折叠 ExpandableSection** | 设置屏内按"画质/音频/摄像头/高级"分组，默认收起 | 参数多时不压迫；初级用户只看默认 | 实现稍复杂；折叠状态需持久化 |
| **C：录制前快捷面板** | 点录制按钮时弹出 BottomSheet，只露出最常用的 2-3 个参数（清晰度/麦克风）；高级入口进全量设置 | 低摩擦；常见操作不需要进设置 | 两个入口管理复杂；快捷面板 + 全量设置需同步状态 |

**初步推荐**：Phase 2 立项时优先做 **A（全量设置屏）**，后续如用研发现 C 的快捷面板需求再扩展。全量设置屏工作量边界清晰，可独立归档。

### 3.2 参数持久化方案

| 方案 | 描述 | 优点 | 缺点 |
|---|---|---|---|
| **DataStore Preferences** | `Context.dataStore` + `preferencesDataStore` key-value 存储 | 轻量；无 schema 版本；异步读写（Flow）；Jetpack 官方推荐持久化配置 | 无类型约束；key 拼错无编译报错 |
| **DataStore Proto** | Protobuf 定义 `RecordingConfigProto`，DataStore TypedSerializer | 强类型；schema 演化安全 | 需引入 protobuf 依赖；build 复杂度上升 |
| **Room + Entity** | `RecordingConfigEntity` 存设置 | 统一数据层 | 杀鸡用牛刀；config 是 singleton 设置非列表数据 |
| **SharedPreferences** | 传统 SP | 无额外依赖 | 同步 IO；deprecated 方向 |

**推荐**：**DataStore Preferences**（key-value）。工作量最小，Jetpack 官方路线，config 是 singleton（不是列表），无需 Proto 复杂度。key 定义集中在 `RecordingPrefsKeys` object 防拼错。

---

## 4. 推荐方案 + 数学/性能分析

### 4.1 推荐技术方案

**配置屏形态**：全量设置屏 `RecordingSettingsScreen`（独立 Composable，从 LapLiveScreen 或应用设置入口导航进入）。

**参数持久化**：DataStore Preferences（`core:data` 或 `feature:test` 内新建 `RecordingPreferencesRepository`）。

**RecordingConfig 扩展方向**（逐步扩展 RecordingConfig data class 字段）：

```kotlin
data class RecordingConfig(
    val resolution: RecordingResolution = RecordingResolution.FHD_1080P,
    val targetFps: Int = 30,
    // 后续扩展字段（recording-params-config-screen round 加入）：
    val audioEnabled: Boolean = true,
    val cameraFacing: CameraFacing = CameraFacing.BACK,    // 枚举：BACK/FRONT
    val videoEncoder: VideoEncoder = VideoEncoder.H264,    // 枚举：H264/H265
    val stabilizationEnabled: Boolean = false,
    val focusMode: FocusMode = FocusMode.CONTINUOUS_AUTO,  // 枚举：CONTINUOUS_AUTO/LOCKED_INFINITY
    val exposureCompensationEv: Int = 0,                   // -5..+5
)
```

**引擎接入**：`CameraRecordingEngine.bind()` 已接受 `RecordingConfig`，配置屏变更后通过 `bind()` 重新绑定（config 改动 MUST rebind）。

### 4.2 一期（当前）vs 后期（配置屏 round）参数映射

| 参数 | 一期硬编码 | 后期配置屏 | CameraX API 路径 |
|---|---|---|---|
| 清晰度 | FHD 1080p | UHD/FHD/HD 三档 | `QualitySelector.fromOrderedList([UHD, FHD, HD], FallbackStrategy.lowerQuality...)` |
| 帧率 | 30（HAL 默认） | 30/60（需升级 CameraX 1.4.0+ / compileSdk 35） | 1.4.0+ `Camera2Interop(Recorder.Builder())` |
| 编码 | H.264（默认） | H.264/H.265（运行时能力检测） | `Recorder.Builder` + `MediaSpec` / `EncoderProfiles` |
| 麦克风 | 开 | 开/关 | `withAudioEnabled()` 条件调用 |
| 摄像头 | 后置 | 前/后切换 | `CameraSelector.DEFAULT_FRONT_CAMERA` + rebind |
| 对焦 | 连续自动（HAL） | 自动/无限远 | `Camera2Interop(Preview.Builder()).setCaptureRequestOption(AF_MODE)` |
| 曝光 | EV 0（HAL） | -5~+5 | `cameraControl.setExposureCompensationIndex(ev)` |
| 防抖 | 关（HAL） | 开/关 | `VideoCapture.Builder.setVideoStabilizationMode(StabilizationMode.ON/OFF)`（需验证 1.3.4 稳定性） |

### 4.3 compileSdk 升级约束分析

CameraX 1.4.0 正式版要求 `compileSdk = 35`。当前工程 `compileSdk = 34`。

升级到 compileSdk 35 的连锁影响：
- AGP 版本可能需要跟进（AGP 8.3+ 支持 compileSdk 35）
- `@OptIn` / `@RequiresApi` 部分 API 可能行为变化
- 需全工程 Lint 检查

**结论**：compileSdk 升级是独立技术债，建议在 `recording-params-config-screen` round 立项时决策是否同步升级，或拆成前置 round `upgrade-compilesdk-35`。帧率精确控制（60fps）强依赖此升级。

---

## 5. 实施约束（MUST 条款）

### M1：参数改动 MUST 重新 bind use-case

`CameraRecordingEngine.bind()` 通过 `cameraProvider.unbindAll() + bindToLifecycle()` 实现重配。任何参数变更（清晰度/帧率/摄像头朝向）**MUST 触发 `unbind() + bind()` 重新绑定**，不能修改已绑定的 Recorder 参数。录制中不允许改参数（应在 Idle 状态才能打开设置屏）。

### M2：4K 清晰度 MUST 做设备能力 fallback

不是所有 Android 设备都支持 UHD（4K）录制。MUST 用 `QualitySelector.fromOrderedList(listOf(Quality.UHD, Quality.FHD), FallbackStrategy.lowerQualityThan(Quality.UHD))` 而非 `QualitySelector.from(Quality.UHD)`（后者在不支持 UHD 的设备上会崩溃）。UI 上 MUST 显示实际选中清晰度（可能被降级）。

### M3：fps 不可精确锁定（CameraX 1.3.4）MUST 透明声明

CameraX 1.3.4 的 `Recorder.Builder` 不实现 `ExtendableBuilder`，`Camera2Interop` 无法附加 fps hint。一期配置屏若提供 "60fps" 选项，MUST 在 UI 上标注"实际帧率由设备决定"或暂不提供 60fps 选项（直至升级 CameraX 1.4.0+）。**禁止** 提供 60fps 选项但实际帧率不受控的伪选项。

### M4：H.265 MUST 做运行时能力检测

H.265 编码并非所有 Android 设备（尤其 Android 9 以下）都支持硬件编码。MUST 在设置屏加载时查询 `EncoderProfiles.getVideoProfiles()` 检测 H.265 支持，不支持则在 UI 中禁用 H.265 选项（灰显 + 说明文字）。

### M5：曝光 EV 范围 MUST 运行时查询

设备支持的 EV 范围不统一。MUST 读取 `camera.cameraInfo.exposureState.exposureCompensationRange` 和 `exposureCompensationStep`，根据实际范围渲染滑块。不能硬编码 -5~+5 作为 UI 范围（实际可能是 -2~+2）。

### M6：compileSdk 35 升级前禁止暴露 fps 精确控制选项

60fps 精确锁定依赖 CameraX 1.4.0+（compileSdk 35）。在升级前，`RecordingConfig.targetFps` 字段可保留作扩展点，但 UI 上**禁止**暴露 60fps 选项。升级 compileSdk 后此约束解除。

### M7：切换摄像头 MUST 先 stop 录制

摄像头朝向改变需要 rebind，rebind 前 MUST stop 当前录制（若有）。设置屏入口应只在非录制态（`RecordingState.Idle`）可访问，或在 rebind 逻辑内自动 stop。

---

## 6. 单元测试覆盖

### 可单测（纯函数，无硬件依赖）

1. **`RecordingConfig` 默认值验证**：`DEFAULT.resolution == FHD_1080P`，`DEFAULT.targetFps == 30`，`DEFAULT.audioEnabled == true` 等。
2. **`RecordingConfig` 序列化/反序列化**：DataStore key-value 写入后读取 roundtrip（可 mock DataStore Preferences）。
3. **参数 fallback 逻辑纯函数**：`resolveEffectiveQuality(requested: RecordingResolution, supportedQualities: Set<Quality>): Quality` 可单测（输入 UHD 请求 + 设备仅支持 FHD → 返回 FHD）。
4. **EV 范围 clamp 逻辑**：`clampEv(requested: Int, range: IntRange): Int` 纯函数单测。
5. **`RecordingResolution → Quality` 映射表**：枚举映射关系单测，防止扩展枚举时遗漏 `when` 分支。

### 需真机验证（无法纯单测）

- 实际分辨率是否被 QualitySelector 正确选中（需查 `VideoCapture.getCameraInfo().videoCapabilities`）
- fps 实际值（需 `MediaExtractor` 解析录制文件帧率）
- H.265 编码正确落盘（需 `MediaFormat` 解析）
- 防抖效果（纯主观视觉）
- 对焦行为（无限远锁定后取景变化）

---

## 7. 与当前 round 的协同关系

**当前 round**：`recording-persist-across-pages-and-hud-indicator`（Phase 2 视频管线 · HUD 指示器 + 跨页面保活）

协同关系：

1. **扩展点已预留**：`RecordingConfig` data class + `companion object DEFAULT` 设计已考虑外部传入不同 config，`CameraRecordingEngine.bind()` 签名 `config: RecordingConfig = RecordingConfig.DEFAULT` 对配置屏 round 完全兼容——配置屏 round 不需改引擎 API，只需向 `bind()` 传不同 config 即可。
2. **无字段冲突**：当前 round 新增 `resolution`/`targetFps` 字段与配置屏 round 后续扩展字段（`audioEnabled`/`cameraFacing` 等）无字段名冲突，data class 扩展安全。
3. **DataStore 依赖**：当前工程若已引入 `androidx.datastore`（A56/Phase 0 数据层有 DataStore 使用），配置屏 round 可复用；若未引入，配置屏 round MUST 在 `core/data` 的 `build.gradle` 添加依赖并立项说明。
4. **前置条件**：配置屏 round 在当前 round 归档后立项（配置屏依赖稳定的引擎 bind API）。

---

## 8. 不并入当前批的理由

1. **用户明确决策**："一期给默认值，后面再加配置屏"——这是用户明确的范围决策，不是技术限制。
2. **独立 UX 工作量**：配置屏是完整的 UI 设计工作（8 个参数 + 控件选型 + Track Tech V2 视觉规范适配 + 导航入口）。混入当前 round（以 HUD 指示器为核心目标的 round）会导致 scope 膨胀、review 焦点分散。
3. **CameraX 部分 API 需评估**：fps 精确控制（60fps）强依赖 CameraX 1.4.0+（compileSdk 35 升级），防抖 API（`StabilizationMode`）在 1.3.4 稳定性需验证，H.265 需运行时能力检测逻辑——这些技术债不是当前录制管线基础设施的必要部分。
4. **DataStore 依赖引入**：当前 round 不需要持久化录制参数，引入 DataStore 是额外依赖变动，应在专门立项中评估（检查 core:data 层是否已有 DataStore）。
5. **compileSdk 升级决策**：fps 精确控制需 compileSdk 35 升级，这是工程级变更，需独立决策窗口，不宜混入功能 round。

---

## 9. 立项节奏估算

**建议 round 名**：`recording-params-config-screen`

**前置条件**：
- `recording-persist-across-pages-and-hud-indicator` round 归档
- （可选前置）`upgrade-compilesdk-35` round（若需要精确 fps 控制）

**建议拆分为以下 task 组**（立项时在 tasks.md 细化）：

| Task 组 | 工作内容 | 估算 | 复杂度影响 |
|---|---|---|---|
| T1：能力探测层 | `RecordingCapabilityDetector`：查询设备支持的 Quality 列表、H.265 编码支持、EV 范围、防抖支持；封装为同步/Flow API | 0.3 天 | — |
| T2：RecordingConfig 扩展 | 添加 `audioEnabled`/`cameraFacing`/`videoEncoder`/`stabilizationEnabled`/`focusMode`/`exposureCompensationEv` 字段；`RecordingResolution` 枚举扩展 UHD/HD | 0.2 天 | — |
| T3：引擎接入（apply config 参数到 CameraX）| `CameraRecordingEngine.bind()` 内按 config 字段配置 `CameraSelector`/`Camera2Interop`/`withAudioEnabled`/`StabilizationMode`；`cameraControl.setExposureCompensationIndex` 在 bind 后调用 | 0.5 天 | — |
| T4：DataStore 持久化 | `RecordingPreferencesRepository`（DataStore Preferences）；`RecordingConfigMapper`（key-value ↔ RecordingConfig） | 0.3 天 | — |
| T5：配置屏 UI | `RecordingSettingsScreen` Composable（Track Tech V2 视觉规范）；导航入口（LapLiveScreen 三点菜单 or 独立设置入口）；设备能力约束 UI 灰显逻辑 | 0.7 天 | — |
| T6：测试 + 真机验证 | 单测（默认值/序列化/fallback/EV clamp）+ 真机验证（清晰度 fallback/麦克风开关/前摄切换/曝光） | 0.3 天 | — |

**总估算**：2.3 天（medium 复杂度；跨 3 层：能力探测 / 持久化 / UI）

**复杂度判定**：**medium**（200-400 行新代码 + 2-3 module 涉及 + 无 Room schema 改）。按工程规则走 v3 标准 L1/L2 review（2-3 轮 Opus 双线）。

**需要的前置决策**（立项时 L0 明确）：
1. compileSdk 升级（35）是否与本 round 捆绑，还是前置独立 round？（影响 fps 选项是否暴露）
2. 配置屏导航入口位置（三点菜单/悬浮按钮/独立设置页）？
3. 防抖选项是否在本 round 内（需真机验证 StabilizationMode 1.3.4 稳定性）？

**与 Phase 2 视频管线批的位置**：本 round 在 Phase 2 核心管线（录制引擎 + GPS 同步 + 持久化）闭环后独立插入，不阻塞 Phase 2 exit。
