# 延期立项设计 Memo：CameraX 升级（HEVC + 60fps）

**延期决策时间**：2026-06-02（夜间自驱批）
**延期决策原因**：B（H.265）与 C（60fps）经实测均卡在 CameraX 1.3.4 公共 API 限制，需升级 CameraX 1.4+ / compileSdk 35。升级要下载新 artifact（离线缓存没有）+ 工程级回归 + 真机验证，**不宜夜间无人值守自驱执行**（怕搞崩构建 + 无法真机验）。
**建议 round 名**：`upgrade-camerax-compilesdk35`（C 主体）→ 升级后 `recording-encoder-hevc`（B 顺接）
**源 round**：`recording-params-config-screen` follow-up（§10 backlog B/C）

---

## 1. 现状

录制引擎 `CameraRecordingEngine`（`feature/test/.../recording/`）用 CameraX **1.3.4** 的 `Recorder` + `VideoCapture`：
- `Recorder.Builder().setQualitySelector(...)` 选分辨率；视频编码 = 设备对该 Quality 的 `EncoderProfiles` 默认（通常 **H.264/AVC**）。
- 帧率由 QualitySelector + 设备 HAL 决定（通常 30fps）。
- compileSdk = 34；CameraX 全家桶 1.3.4（`gradle/libs.versions.toml` `cameraX = "1.3.4"`）。

## 2. 数据证据（实测 · javap CameraX 1.3.4 jar）

`javap` 检查 `camera-video-1.3.4-runtime.jar`（2026-06-02 实测）：

| 类 | 暴露的相关方法 | 结论 |
|---|---|---|
| `VideoSpec.Builder` | `setFrameRate(Range)` / `setBitrate(Range)` / `setAspectRatio(int)` | **无 mime/codec setter** |
| `MediaSpec.Builder` | `OutputFormat`（MPEG_4 等容器格式）| 只控**容器**不控**视频 codec** |
| `Recorder.Builder` | `setTargetVideoEncodingBitRate` / `setAspectRatio` / `setVideoEncoderFactory(EncoderFactory)` **package-private** | 唯一 codec 入口是内部 `EncoderFactory`，**非 public，app 不可用** |

**结论**：
- **HEVC（B）**：CameraX 1.3.4 **无公共 API 强制视频 codec**。codec 由设备 EncoderProfiles 决定，应用无法切到 H.265。
- **60fps（C）**：`VideoSpec.Builder.setFrameRate` 存在，但 `Recorder.Builder` 在 1.3.4 不实现 `ExtendableBuilder`，Camera2Interop 附不上；且 setFrameRate 经 VideoSpec 的实际生效度在 1.3.4 受限（HAL 决定），memo 旧结论"需 1.4+"成立。

## 3. 方案对比

| 方案 | 描述 | 优点 | 缺点 |
|---|---|---|---|
| **A：升级 CameraX 1.4+ / compileSdk 35（推荐）** | 升级全家桶 + compileSdk，用 1.4 改进的 VideoSpec/codec 控制 | 官方路线；B+C 一次性解锁；维护成本低 | 下载新 artifact（需网络）+ AGP/Lint 回归 + 真机验 |
| **B：绕开 CameraX，自写 MediaCodec 录制管线** | MediaCodec(HEVC) + MediaMuxer 自录 | 不依赖 CameraX 升级；codec/fps 全可控 | 大重写（相机帧→Surface→编码→muxer + 时钟锚点 + 跨页保活全重做），抛弃 Phase 2 已稳的 Recorder 链路；风险极高 |

**推荐 A**。B（自写 MediaCodec）等于推翻 Phase 2 录制地基，不划算。

## 4. 推荐方案 + 升级影响分析

### 4.1 升级清单
- `gradle/libs.versions.toml`：`cameraX` 1.3.4 → 1.4.x（最新稳定）。
- `compileSdk` 34 → 35（`app` + 各 module build.gradle）；`targetSdk` 评估是否同步。
- AGP 版本：1.4 + compileSdk 35 需 AGP 8.3+，核对当前 AGP，必要时升。
- 全工程 Lint + 编译回归。

### 4.2 升级后 B（HEVC）实现
- CameraX 1.4 的 `Recorder`/`VideoSpec` 编码控制改进后，设 HEVC mime；**仍 MUST 运行时检测**设备 H.265 编码支持（`MediaCodecList.findEncoderForFormat(video/hevc)` 或 `EncoderProfiles.getVideoProfiles()`）→ 不支持灰显回落 H.264。`RecordingConfig` 加 `videoEncoder: VideoEncoder(H264/H265)` 字段 + 设置浮层加编码 chips（不支持灰显）。兼容性提示（老设备解码/分享差）。

### 4.3 升级后 C（60fps）实现
- CameraX 1.4 `Recorder.Builder` 支持 Camera2Interop 或 VideoSpec.setFrameRate 精确锁 60fps。`RecordingConfig.targetFps` 已预留；设置浮层解锁 60fps 选项（当前 spec "不暴露 60fps" Requirement 届时改）。

## 5. 实施约束（MUST）

- **M1 升级在独立 round**：`upgrade-camerax-compilesdk35` 只做升级 + 回归（不混功能），全模块编译 + 现有单测全绿 + **真机录制冒烟**（确认 1.4 录制链路不回归 SOURCE_INACTIVE 等 Phase 2 老坑）。
- **M2 B/C 顺接**：升级 round 绿后，B（HEVC，medium）+ C（60fps，small）各自独立 round 顺接，复用已升级地基。
- **M3 HEVC 运行时检测**：B MUST 运行时检测，不支持灰显回落 H.264（memo M4 旧条款）。
- **M4 网络 + 真机前提**：升级 round MUST 在有网（下 artifact）+ 能真机验（录制冒烟）时做，**不夜间无人自驱**。

## 6. 单元测试覆盖

- HEVC 支持检测纯函数：`isHevcEncoderSupported()` 基于 `MediaCodecList`（可 mock codec 列表单测）。
- `RecordingConfig.videoEncoder` 默认值 + 序列化 roundtrip。
- 60fps：`targetFps` 持久化 + 设置浮层暴露逻辑（升级后解锁）。
- 真机：实际 HEVC 落盘（`MediaFormat` 解析 mime=video/hevc）、实际 60fps（`MediaExtractor` 解析帧率）。

## 7. 与当前进度的协同关系

- A（video-storage-cleanup）已完成，与本 round 无耦合。
- D（driver-display-name）已/将完成，纯本地无耦合。
- B/C 都依赖本升级；升级前 B/C **无法真做**（实测 1.3.4 限制）。
- `recording-params-config-screen` 已留 `targetFps` 扩展点 + spec "不暴露 60fps" Requirement（升级后改）。

## 8. 不在夜间自驱批做的理由

1. **离线无 artifact**：CameraX 1.4 不在 gradle 缓存，`--offline` 构建拉不到。
2. **工程级风险**：compileSdk + AGP + Lint 全回归，半夜搞崩构建 = 早上一堆红，违背"看成果"。
3. **必须真机验**：录制链路升级后 MUST 真机冒烟（防 Phase 2 老坑回归），user 睡觉无法验。
4. **B 无独立路径**：1.3.4 实测无公共 codec API，B 不能脱离升级单独做（除非自写 MediaCodec 大重写，更不该夜间做）。

## 9. 立项节奏估算

| round | 内容 | 复杂度 | 估算 | 前提 |
|---|---|---|---|---|
| `upgrade-camerax-compilesdk35`（C 主体地基） | CameraX 1.4 + compileSdk 35 + AGP/Lint 回归 + 真机录制冒烟 | large | 1-1.5 天 | **有网 + 能真机 + user 在场** |
| `recording-encoder-hevc`（B） | HEVC mime + 运行时检测 + 设置 chips 灰显 + 兼容提示 | medium | 0.7 天 | 升级 round 绿 |
| `recording-fps-60`（C 功能收尾） | 解锁 60fps 选项 + 精确锁 + 改 spec | small | 0.4 天 | 升级 round 绿 |

**顺序**：升级地基 → B（HEVC）→ C（60fps 收尾）。升级地基 MUST user 在场 + 有网 + 真机。
