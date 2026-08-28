# Phase 2 视频管线 · 入场就绪 memo（2026-05-30）

> 由 CC 在 M3 收尾期为"给接视频管线做准备"沉淀。目的：把开 Phase 2 前需要 **user 拍板的 L0 决策**、**已就绪的技术地基**、**阻塞项** 集中列清，user 回来一次性定了即可直接开 Phase 2 round 1。
> 详细 Phase 2/3 round 拆分见 `lap-timing-data-video-holistic-roadmap.md` §3 Phase 2 + Phase 3 + §1.3 + §2 交织线 1/4。本 memo 不重复，只补"入场就绪 + 待决策"。

---

## 0. 一句话现状

视频管线是**绿场**（生产源码 0 摄像头相关命中，已 grep 核实），但**地基最关键的一块——共享时间轴 wallClock 锚点——已经就绪**（圈速链路现成）。Phase 2 不能立刻无脑开，卡在 **3 个 L0 必答决策** + **Phase 1 exit gate** + **1 个 schema 前置 round**。

---

## 1. 技术地基核实（2026-05-30 grep 取证，全部就绪/确认）

| 地基 | 状态 | 证据 |
|---|---|---|
| **摄像头绿场** | ✅ 全新搭 | 生产源码 grep camerax/mediarecorder/camera2/mediacodec/mediamuxer/videocapture/RECORD_AUDIO/CAMERA = 0 命中 |
| **wallClock 同步锚点**（视频帧↔圈速数据共享时间轴，**最关键**）| ✅ 现成 | `TelemetryRepository.startSession()` L69 `startTs = System.currentTimeMillis()` + L90 `activeSessionStartTs=startTs`；binary samples `absoluteTsMs = startTs + tsDeltaMs`；crossing wallClock 同时钟域。**视频录制起点取同一 `System.currentTimeMillis()`（首帧落地回调）即线性映射到圈速位置** |
| LapLiveScreen（预览天然宿主）| ⚠️ 锁横屏 | L80 `SCREEN_ORIENTATION_LANDSCAPE` + keepScreenOn —— 与横竖屏 L0 直接关联 |
| Room schema | ⚠️ version=5 + `fallbackToDestructiveMigration()`（无 strict migration）| AppDatabase L29 version=5；AppModule L63 无参 destructive fallback。Phase 2 加 `videoFilePath/videoStartedAtWallClock`（v5→v6）前需决定走 strict 还是续 destructive |
| TelemetrySessionEntity | ✅ 无 video 字段（符合预期，待 Phase 2 加）| grep video = 0 命中 |

---

## 2. ⚠️ Phase 2 立项前 user 必答的 3 个 L0 决策（最重要，回来先定这个）

路线图明确 Phase 2 不替 user 假定，这 3 个决定 Phase 2 的形态与体量：

### L0-1：录制方向 = 横屏 还是 竖屏？
- LapLiveScreen（圈速主屏，预览天然宿主）**强制锁横屏**（L80）。
- 横屏录制：与现有锁一致，预览嵌 LapLiveScreen 顺手；但竖屏手持录视频不自然。
- 竖屏录制：更符合手机录像习惯，但与 enforce-portrait 锁 + LapLiveScreen 横屏锁**冲突**，要拆预览宿主。
- **影响**：决定 preview 宿主在哪屏 + 是否动 orientation 锁。

### L0-2：录音频 RECORD_AUDIO 吗？
- 录：有发动机声/环境音，视频更有料；但 RECORD_AUDIO 是敏感权限 + 文件体量翻倍 + 多一条权限流。
- 不录：纯画面，权限简单文件小。
- **影响**：权限 scope + 文件大小 + Phase 2 round 1 权限流复杂度。

### L0-3：要实时预览吗？
- 要：录制时 LapLiveScreen 嵌 PreviewView（CameraX）——多一个 `camera-preview-in-laplivescreen` round + 跟 M3/redesign-delta 抢 LapLiveScreen 文件。
- 不要：后台静默录制，不显示预览——省一个 round，但用户录时看不到取景。
- **影响**：是否要 preview round + LapLiveScreen 跨 round 文件协调。

> 建议回来用一次 L0 复述把这 3 个定了（参 CLAUDE.md Review v3 L0 协议）。

---

## 3. 阻塞项（开 Phase 2 前必须先清）

| 阻塞 | 说明 | 处置 |
|---|---|---|
| **Phase 1 exit gate 未跑** | Phase 2 entry 硬 gate = Phase 1 exit commit。当前 M3（多圈比较屏）第一刀刚做，Phase 1 还没 exit | M3 闭环 + 跑 `phase1-exit-review`（含 W4 补 L2 或豁免留痕 + deferred memo disposition + 留痕落点）后才进 Phase 2 |
| **Room strict migration 债** | version=5 + destructive fallback；Phase 2 加 video 字段是 schema 改 | `restore-strict-migrations-pre-release` round（roadmap §3，medium，Room 治理）建议 Phase 2 schema 改之前做；或 debug 阶段续 destructive（上线前必补） |
| **L0 三问未答** | 见 §2 | user 回来拍板 |

---

## 4. Phase 2 round 序列（roadmap §3，体量已按 review 重估，供参考）

1. `camera-module-and-permission`（**large**：新建 `core:camera` 模块 + 5 个 CameraX 依赖 + CAMERA 权限流 + manifest）— L0 后第一环
2. `camera-preview-in-laplivescreen`（medium，**仅 L0-3=要预览 才做**）— PreviewView 嵌 Compose
3. `camera-recording-and-gps-sync`（**architectural**：VideoCapture 启停 + **首帧 wallClock 精确锚定** + 录制状态机；可拆 core/resilience）— 最难一环
4. `session-video-metadata-persist`（medium：entity 加字段 + v5→v6 migration + deleteSession 白名单扩 `/video/`）
5. `recording-toggle-and-indicator`（medium，仅 UI）
6. `recording-resource-safety`（medium~large：存储满/电量/温度/异常退出 mp4 完整性）

---

## 5. 最关键技术风险（Phase 2 必须在单测锁死，不能拖到 Phase 3，roadmap 交织线 4）

- **首帧 wallClock 取值时机**：相机冷启动延迟数百 ms，`videoStartedAtWallClock` MUST 在**首帧落地回调**取 `currentTimeMillis()`（不是按下录制键时），否则 Phase 3 叠加全错位。
- **PTS↔wallClock 漂移**：video PTS 是录制器单调时钟，`currentTimeMillis()` 可能被 NTP/手动调时跳变 → "锚点写对了但取帧仍错位"。Phase 2 单测 MUST 锁 (a) 首帧时机 (b) PTS→absoluteTsMs 映射公式 (c) 长录制漂移容忍。
- **帧率/采样不互质**：video 30fps（33ms）vs binary 25Hz（40ms），取帧"最近邻 vs 插值"沿用 W3 LapAlignment 已确立的最近邻+clamp 策略。

---

## 6. 公共协议 MUST NOT（roadmap §3 末，A56 + 公共协议边界）

录制挂接 **MUST 只读** session 生命周期事件（startSession 首帧懒启动 / endActiveLapSession / finishActiveLapSession），**绝不**改 `bridgeGpsToLapTiming` 的 gpsData.timestamp 处理、**绝不**改 binary writer。此约束写入 `camera-recording-and-gps-sync` design risks 段。

---

## 7. CC 的建议（供 user 参考）

1. **先把 Phase 1 收尾**：M3 真机签收 → `phase1-exit-review`（W4 补 L2 + deferred disposition）→ Phase 1 exit commit。这是 Phase 2 硬 gate，绕不开。
2. **回来用一次 L0 复述定 §2 三问**（横竖屏 / 音频 / 预览）—— 这是 Phase 2 形态的根。
3. **`restore-strict-migrations-pre-release` 可任意时机插入**（Room 治理，medium），建议 Phase 2 video metadata schema 改之前。
4. Phase 2 第一刀建议 `camera-module-and-permission`（打通权限+模块骨架，large，先把"能开相机"立起来），再 `camera-recording-and-gps-sync`（首帧 wallClock 锚定，architectural，最难）。
5. **地基已就绪**（wallClock 锚点 + 绿场无历史包袱），技术上无硬阻塞，主要是 L0 决策 + Phase 1 exit 的流程前置。

---

## 8. follow-up（下次开 Phase 2 立项时对照本 memo 起草 proposal/design）

- 本 memo + roadmap §3 Phase 2 + §1.3 + §2 交织线 1/4 三处合起来就是 Phase 2 entry 的完整输入。
- L0 答完后第一个 `/opsx:ff` 应是 `camera-module-and-permission`（large，走 v3 标准或 road-test-first 视 user 当时授权）。

---

## 9. 架构决策更新（2026-05-30 · user 拍板）

### 9.1 优先级：app 优先服务"用手机拍视频"的用户，GoPro/外部素材 = 后话
- **决策**：Phase 2/3 走原排布——手机拍摄（轨 V）+ 图层叠到 app 自己录的视频。**透明层导出去合成外部运动相机素材延后**（后话）。
- **理由**：app 主用户群是用手机拍。GoPro 用户是 niche，后置。

### 9.2 图层本质是数据驱动 → 透明层导出以后低成本可加（不堵死后路）
- **关键洞察**（2026-05-30 讨论沉淀）：HUD 图层内容全来自圈速 telemetry（binary samples + crossings），**不依赖任何视频画面**。图层引擎对透明背景逐帧渲染即得透明层。
- 因此存在两条可独立的轨：
  - **轨 O**（图层引擎 + 透明导出）：只依赖 Phase 1 数据（已完成），**不需要相机**。
  - **轨 V**（app 相机拍摄，原 Phase 2）：依赖 L0 + Phase 1 exit。
  - 合成②（叠到 app 录的视频）= 轨 O + 轨 V。
- **现在为手机用户做轨 V + 合成②；轨 O 的"透明层导出"（chroma-key / PNG 序列 / 真 alpha）列为 Phase 3 future follow-up**，架构上随时能加（图层引擎复用，只多一个透明输出模式 + 外部同步参考 t0/帧率/对齐标记）。

### 9.3 L0-3 预览 = 有（已定）
- **决策**：拍摄时 **MUST 实时预览相机取景视角**（user 要对准构图）。
- 实现：`PreviewView`（CameraX）嵌 `LapLiveScreen` 当背景 + 现有实时圈速 HUD 画在预览上层（Compose 屏上合成，**不烧进 mp4**；mp4 录干净画面，HUD 留待离线/导出叠）。CameraX 原生支持 Preview + VideoCapture 同绑同相机。
- → `camera-preview-in-laplivescreen` round **必做**（不再是"视 L0 可选"）。

### 9.4 全部 3 个 L0 已定（2026-05-30 user 拍板）
- **L0-1 录制方向 = 横屏**：与 LapLiveScreen 现状一致（已锁横屏），不用拆 orientation 锁；车载/运动视频宽画面自然；预览嵌 LapLiveScreen 顺手。
- **L0-2 音频 = 录**：录 RECORD_AUDIO（发动机/排气/环境声，车载内容更有料）。**实现影响**：manifest + 运行时加 RECORD_AUDIO dangerous permission；VideoCapture 绑 AudioConfig；mp4 含音轨文件翻倍；权限流要并 CAMERA 一起申请（`camera-module-and-permission` round scope 含 RECORD_AUDIO）。
- **L0-3 预览 = 有**：见 §9.3。

→ **Phase 2 L0 全清**，唯一剩的 entry gate = Phase 1 exit commit。L0 答完第一个 round = `camera-module-and-permission`（large/architectural：CAMERA + RECORD_AUDIO 双 dangerous 权限 + CameraX 依赖 + core:camera 模块）。
