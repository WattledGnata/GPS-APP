## Context

Phase 2 视频管线导出环节。前序 round 已就绪：录制（filesDir/video/*.mp4）+ 视频↔遥测时钟锚点（`videoStartedAtWallClock` 与 binary `absoluteTsMs` 同 `System.currentTimeMillis()` 时钟域）+ `VideoTelemetrySync` / `VideoOverlayTelemetry` 离线计算纯函数（已单测）+ 按圈实时叠加播放屏（`LapVideoPlaybackScreen`，Compose Canvas overlay 叠 ExoPlayer）+ 模拟仪表 overlay（速度指针表 / G 球 / 小地图 / 圈速计时，数学与绘制已分离）。本 round 落地"把 overlay 烧进视频帧导出新 mp4 存相册分享"——离线逐帧解码原始视频 → GL 把视频帧 + overlay 合成到每个像素 → 编码新 mp4 → 落 MediaStore。

**复杂度判定 = large**：(a) 引入新能力（视频导出 GL/MediaCodec 管线，多组件：extractor/decoder/GL/encoder/muxer/Service/MediaStore writer）(b) 修改现有共享 UI 组件（回放 overlay 绘制层下沉为共享层）(c) 前台 Service。road-test-first 模式（user 已授权该批）：FileLogger 密集埋点 + 真机攒批路测兜底，**不调 Opus 子 agent**；CC design 期格外谨慎（GL/MediaCodec 是高踩坑面）。**是否拆 round 待拍板**（见末尾"待拍板"）。

技术 baseline 核实锚点（apply 期 #3 自查，grep 验证）：

- `feature/test/.../usecase/VideoOverlayTelemetry.kt:84` `buildFrames(samples, smoothingWindow=5): List<OverlayFrame>`（`OverlayFrame(absoluteTsMs, speedKmh, latG, lonG, lat, lon)`，:37）；`resolveCurrentLap(frameWallClock, lapWindows): LapResolution?`（:151，`LapResolution(lapNumber, currentLapElapsedMs)` :65）；`computeDeltaMs(reference, currentLapElapsedMs, currentLat, currentLon): Long?`（:257，internal）；`buildReferenceFromSamples(bestLapSamples, lapStartWallClock, lapDurationMs): ReferenceLapIndex?`（:208，internal）。
- `feature/test/.../recording/VideoTelemetrySync.kt`：`frameWallClock(videoStartedAtWallClock, framePtsMs): Long`（:32）；`findNearestSampleIndex(frameWallClock, sampleWallClocks): Int`（:51，空抛 IAE，边界 clamp）；`lapPlayheadRange(lapStartWallClock, lapEndWallClock, leadInMs=3000): LongRange`（:92）；`isWithinVideoCoverage(playheadWallClock, videoStartedAtWallClock, videoDurationMs): Boolean`（:117）；`playheadToVideoPosition(playheadWallClock, videoStartedAtWallClock, videoDurationMs): Long`（:138）；`LAP_LEAD_IN_MS=3000`（:148）。
- `feature/test/.../usecase/GaugeMath.kt`：`speedToNeedleAngle(speedKmh, maxKmh=260, startAngleDeg=135, sweepAngleDeg=270): Double`（:52）；`gForceToBallOffset(latG, lonG, maxG=1.5): Pair<Double,Double>`（:81）；常量 `SPEEDO_MAX_KMH`/`SPEEDO_START_ANGLE_DEG`/`SPEEDO_SWEEP_ANGLE_DEG`/`GBALL_MAX_G`。
- `feature/test/.../ui/tracktech/TrackMiniMap.kt`：`TrackMiniMapProjection.project(points, currentLat, currentLon, canvasWidth, canvasHeight, padding): Projected?`（:58，`Projected(polyline: List<Offset>, current: Offset?)` :35，points<2 返回 null）。
- `feature/test/.../ui/tracktech/SpeedometerGauge.kt` / `GForceBall.kt`：现有 `Canvas { DrawScope … }` 绘制块（SpeedometerGauge.kt:54-142 / GForceBall.kt:51-93）；SpeedometerGauge.kt:99 已用 `drawContext.canvas.nativeCanvas` 调 `android.graphics.Canvas.drawText`（证明 nativeCanvas 路径可行）。
- `feature/test/.../recording/CameraRecordingEngine.kt:337-344`：视频录到 `File(context.filesDir, VIDEO_DIR)/<ts>.mp4`（app 私有，导出**读**源无需权限）。
- `core/domain/.../model/TelemetryModels.kt:50-53`：`TelemetrySession.videoFilePath: String?` + `videoStartedAtWallClock: Long?` + `bestLapMs: Long?`（:47）+ `trackId: String?`。
- `core/data/.../repository/TelemetryRepository.kt`：`getSession(sessionId): TelemetrySession?` + `getLapTelemetry(sessionId, lapIndex): LapTelemetry?`。
- `feature/test/.../ui/tracktech/LapSessionDetailScreen.kt:183`：圈行已有 `navController.navigate("lap_video/$sessionId/$lapIndex")` 播放入口工厂（:499/:574 行末播放图标）；导出入口加在同处旁。
- `feature/test/.../FileLogger.kt:129-131`：`d(tag,msg)` / `v(tag,msg)` / `e(tag,msg,throwable?)`，落 `filesDir/debug_log.txt`。
- `app/src/main/AndroidManifest.xml`：当前声明 CAMERA/RECORD_AUDIO/位置/蓝牙；**无任何存储 / 前台 Service 权限**（导出要加）。
- `feature/test/build.gradle.kts:10-13`：compileSdk 34 / minSdk 28；:63-64 media3 1.3.1（exoplayer+ui，导出**不用** media3-transformer）。

## Decisions

### Decision 1：overlay 绘制复用策略 = 抽共享 `android.graphics.Canvas` 绘制层，回放与导出双端复用（vs 导出端 GL 重画 / 两套绘制代码）

- **选**：把 `SpeedometerGauge`/`GForceBall`/`TrackMiniMap`/圈速计时面板的绘制逻辑下沉成纯 `android.graphics.Canvas` 绘制函数（`OverlayCanvasPainter` object：`drawSpeedometer(canvas, cx, cy, radius, speedKmh, paints)` / `drawGForceBall(...)` / `drawTrackMiniMap(...)` / `drawLapTimePanel(...)`），全部消费**同一套** `GaugeMath` / `TrackMiniMapProjection` 纯数学（已单测）。
  - **回放端（Compose）**：现有 `Canvas { DrawScope }` 块改为 `Canvas(modifier) { drawIntoCanvas { c -> OverlayCanvasPainter.drawSpeedometer(c.nativeCanvas, …) } }`（`drawIntoCanvas` 拿到 `Canvas`，其 `.nativeCanvas` 是 `android.graphics.Canvas`）。视觉与行为零变化（同图元、同坐标、同数学）。
  - **导出端**：每帧把当前 overlay 数据画到一个透明 `Bitmap`（`Bitmap.createBitmap(w, h, ARGB_8888)` + `Canvas(bitmap)`），调同一套 `OverlayCanvasPainter` 函数 → 这个 overlay `Bitmap` 上传为 GL 纹理叠到视频帧上（见 Decision 2）。
- **Alt A（拒绝）· 导出端 GL 重画 overlay（GLSL shader 画指针/圆/文字）**：用 GLES 自绘指针线、圆、刻度、文字（位图字体或 SDF），与回放 Compose 端是**两套完全独立**的绘制实现。**拒绝理由**：(1) GL 画文字（圈速 `1:32.457` / 速度数字 / G 数值 / 刻度标注）极痛苦（需位图字体图集或 SDF 渲染），而 Android Canvas `drawText` 一行搞定；(2) 两套绘制 = 双份维护，回放调一个视觉、导出要同步改另一套 → 必漂移（v3 盲点：双份实现不同步）；(3) 速度表刻度/指针、G 球同心圈、小地图 polyline 的几何已在 `GaugeMath`/`TrackMiniMapProjection` 纯函数里，GL 重画只是把"画"那层重写一遍，纯属浪费。
- **Alt B（拒绝）· 导出与回放各写一套 Canvas 绘制（不抽共享层）**：导出端复制粘贴 SpeedometerGauge 的 DrawScope 逻辑改成 android.graphics.Canvas。**拒绝理由**：复制即漂移源头（v3 高频盲点：双份实现），且 DrawScope API ≠ android.graphics.Canvas API（drawCircle 签名不同），复制需逐行翻译，错误面大；抽共享层（吃 `android.graphics.Canvas`）让回放端经 `nativeCanvas` 也能调，是 1 套代码。
- **rationale**：**这是本 round 核心决策**。关键洞察 —— 现有三组件已是"纯数学（GaugeMath/TrackMiniMapProjection，已单测）+ 绘制层（DrawScope 标准图元 + nativeCanvas.drawText）"的分离结构，绘制层用的 `drawCircle`/`drawLine`/`drawPath`/`drawArc`/`drawText` 在 `android.graphics.Canvas` 上都有等价 API。下沉成吃 `android.graphics.Canvas` 的纯函数后：回放端用 `drawIntoCanvas{ it.nativeCanvas }` 复用、导出端用 `Canvas(bitmap)` 复用 → **1 套绘制代码两端共享**，视觉天然一致（导出成片 = 回放所见），杜绝漂移。GL 只负责"把视频帧外部纹理 + overlay Bitmap 纹理合成到 encoder Surface"（合成那层必须 GL，因为视频帧来自 decoder 的 SurfaceTexture 外部纹理，只能在 GL 上下文采样）；"画 overlay"那层留在 CPU Canvas（廉价，overlay 区域小、每帧画一次 Bitmap）。**`OverlayCanvasPainter` 函数本身可单测**（画到 Bitmap 后断言关键像素 / 或断言绘制调用，至少断言不崩 + 尺寸正确）。

### Decision 2：烧录合成管线 = MediaCodec decoder→SurfaceTexture→GLES 离屏→encoder Surface→MediaMuxer（纯 platform，vs media3-transformer / FFmpeg / mp4parser）

- **选**：纯 platform API 管线（业界标准，参 Grafika `DecodeEditEncodeTest` 范式）：
  1. `MediaExtractor` 打开源 mp4，选视频轨（`video/avc`），读 `MediaFormat`（宽高/帧率/旋转 `KEY_ROTATION`）。
  2. `MediaCodec` decoder 配置输出到一个 **GL external texture 的 `SurfaceTexture`**（`new Surface(surfaceTexture)`），解码帧落 GL 纹理（不回读 CPU，零拷贝）。
  3. `MediaCodec` encoder（`video/avc`）配置 **input `Surface`**（`createInputSurface()`），EGL 把这个 Surface 作为 GL 渲染目标。
  4. 每帧：decoder 出帧（`SurfaceTexture.updateTexImage()`）→ GLES20 画满帧（external texture，用 `samplerExternalOES` + 源旋转矩阵 `getTransformMatrix`）→ 把 overlay `Bitmap`（Decision 1）作为普通 2D 纹理 `glTexImage2D` 叠上（blend）→ `eglSwapBuffers` 推到 encoder Surface（带 PTS）。
  5. encoder 出编码数据 → `MediaMuxer` 写新 mp4（视频轨 + 音轨直通，见 Decision 6）。
- **Alt A（拒绝）· androidx.media3-transformer（`Transformer` + `OverlayEffect`/`TextureOverlay`）**：media3 已在依赖（exoplayer+ui），加 `media3-transformer` 就有官方 `Transformer` API 做"视频 + overlay effect"导出，省手写 GL。**拒绝理由**：(1) 引入**新第三方 artifact**（media3-transformer，触发"引入新依赖"判定）；(2) media3-transformer 1.3.x 的 overlay effect 体系（`OverlayEffect`/`BitmapOverlay`/`TextOverlay`）支持"静态 overlay 或按时间表的 overlay"，但本需求是**每帧 overlay 内容都不同**（速度指针/圈速/delta 逐帧变）→ 需 `BitmapOverlay.createPlatformBitmapOverlay` 配 per-frame 动态 bitmap supplier，1.3.x 的动态 per-frame bitmap 支持不成熟（需自定义 `SamplerOverlay` / `GlEffect`），文档与厂商兼容性未验，反而踩坑面更大且黑盒难调；(3) Transformer 的剪辑（`ClippingConfiguration`）对按圈裁剪好用，但其内部仍是 MediaCodec+GL，自己写管线对"每帧动态 overlay"控制力更强、可 FileLogger 逐帧埋点（road-test-first 必需）。**保留升级路径**：若一期手写 GL 路测发现厂商兼容性差，follow-up 可评估迁移 media3-transformer（共享绘制层 Decision 1 产出的 Bitmap 可喂给 BitmapOverlay）。
- **Alt B（拒绝）· FFmpeg（mobile-ffmpeg / ffmpeg-kit）**：能用 filter 烧字幕/图层。**拒绝理由**：(1) 重型第三方 native 库（apk 体积 +10~20MB / LGPL/GPL 授权风险）；(2) overlay 每帧动态内容需逐帧生成图片 + filter 复杂；(3) 软解软编慢且耗电，不如 platform 硬编解码。
- **Alt C（拒绝）· mp4parser / isobmff 容器拼接**：mp4parser 只做容器层（封装/裁剪/合并 box），**不能重编码像素**（无法把 overlay 烧进帧）→ 根本满足不了"overlay 进像素"需求，仅适合无损裁剪（与本需求不符）。
- **rationale**：L0 已锁"导出用 GL/MediaCodec"。纯 platform 管线 0 新依赖、硬编解码省电、对每帧 overlay 控制力最强、可逐帧埋点（road-test-first 兜底）。这套是 Android 视频处理的业界标准范式（Grafika / CameraX VideoCapture 内部同理）。compileSdk34/minSdk28 全程平台 API 可用（MediaCodec API16+ / Surface input API18+ / GLES20 API8+，远早于 28）。**复杂度集中在 EGL/GL 样板代码**（context 创建、external texture、shader）→ tasks 把 GL helper（`OverlayEglCore`/`OverlayGlRenderer`）单列骨架阶段，参考成熟的 Grafika `EglCore`/`WindowSurface`/`TextureRender` 模式抄写（避免从零造轮子）。

### Decision 3：解码-渲染-编码循环结构 = decoder/encoder 同线程 drain loop（vs 多线程 producer-consumer）

- **选**：单后台线程（Service 内的 `HandlerThread` 或 coroutine `Dispatchers.Default`）跑同步 drain loop：`while(!encoderDone){ feedDecoderInput(); drainDecoderToSurface(); awaitFrameAvailable(); renderGl(); drainEncoderOutputToMuxer(); }`。decoder 输出 Surface（onFrameAvailable）→ GL 渲染 → encoder Surface → encoder 输出 muxer，全在一个循环里推进。EOS 经 decoder→GL→encoder `signalEndOfInputStream()` 传递。
- **Alt A（拒绝）· decoder 线程 + encoder 线程 producer-consumer**：多线程吞吐高但 EGL context 跨线程要 makeCurrent 切换 + 帧同步复杂 + 死锁面大。**拒绝理由**：导出是离线批处理（非实时），吞吐不是瓶颈（用户等几秒到几十秒可接受），单线程 drain loop 正确性远高于吞吐优化；EGL context 单线程独占最稳。
- **rationale**：单线程同步 drain loop 是 Grafika `DecodeEditEncode` 验证过的最稳范式；EGL 单线程 makeCurrent 一次不切换；帧同步用 `SurfaceTexture.OnFrameAvailableListener` + 条件变量（awaitNewImage）。每帧/每 30 帧 FileLogger 埋点（in/out buffer index、PTS、渲染耗时、当前 overlay 数据）供路测核对。

### Decision 4：导出执行 = 前台 Service + 通知进度（vs WorkManager / 同步阻塞 / 纯协程）

- **选**：**前台 Service**（`VideoExportService`，`startForeground` + 进度通知）执行导出 drain loop。详情屏点"导出"→ `ContextCompat.startForegroundService` 启动 + 传 sessionId/lapIndex；Service 跑导出，经回调（绑定 / `LocalBroadcast` / 共享 `StateFlow` 单例）回传进度（已处理帧 / 总帧 → 百分比）；UI 显示进度对话框（百分比 + 取消按钮 → 停 Service）。完成 → 通知"已保存到相册" + 详情屏弹分享 Intent。
- **Alt A（拒绝）· WorkManager**：WorkManager 适合"可延迟、需保证最终执行、跨进程重启续跑"的后台任务。**拒绝理由**：(1) 引入新依赖（androidx.work 当前 0 命中）；(2) 视频导出是用户**当下发起、想立即看到进度、可取消**的前台交互任务，不是"可延迟到充电时跑"的后台任务，WorkManager 的调度语义不匹配；(3) WorkManager worker 跨进程回传进度（`setProgress`）+ 观察更绕。前台 Service 进度通知 + 可取消更贴合"导出中…47%"交互。
- **Alt B（拒绝）· 同步阻塞 / Composable 内协程**：导出耗时数秒~数十秒，挂在 Composable 协程里 → 用户切屏 / 进程被杀 / 配置变更导出就断；且无前台通知用户不知道在干嘛。**拒绝理由**：长耗时任务必须脱离 Composable 生命周期（前台 Service 才有 ANR 豁免 + 通知 + 不随 UI 销毁）。
- **rationale**：前台 Service 是"用户发起、需进度可见、可取消、不可被随意杀"的长耗时媒体处理任务的标准载体（API 34 `FOREGROUND_SERVICE_MEDIA_PROCESSING` 专为此类设计）。0 新依赖。manifest 加 `FOREGROUND_SERVICE`（+ API 34 `FOREGROUND_SERVICE_MEDIA_PROCESSING`）+ Service 声明。取消 = Service `stopSelf` + 中断 drain loop（标志位）+ 删半成品文件（MediaStore pending 项 delete）。**风险**：API 34 前台 Service 类型限制严（需声明 type + 用户可见通知），mitigation 见 Risks。

### Decision 5：按圈裁剪 + 圈头/尾超视频覆盖段处理（一期只导有视频覆盖段，黑帧段 backlog）

- **选**：导出段 = 圈时间轴 [`lapStart - leadIn`, `lapEnd`]（复用 `lapPlayheadRange`）与视频覆盖段 [`videoStart`, `videoStart + videoDuration`] 的**交集**。即：导出起点 = `max(playheadStart, videoStart)`，导出终点 = `min(playheadEnd, videoStart + videoDuration)`（均转成视频内 position 经 `playheadToVideoPosition`）。`MediaExtractor.seekTo(startPositionUs, SEEK_TO_PREVIOUS_SYNC)` 起（seek 到起点前最近关键帧，丢弃早于 startPosition 的解码帧不渲染），decode 到终点 position 止（PTS > 终点即 EOS）。圈头早于视频起点 / 圈尾晚于视频终点的**黑帧段一期不导出**（导出文件 = 该圈有真实画面的那段，overlay 与画面对齐）。
- **Alt A（拒绝整体不裁剪，导整段视频）**：导出整个 session 视频。**拒绝理由**：违背"按圈导出"已锁方向（一圈一文件，跟回放一致），用户要的是单圈成片不是整段。
- **Alt B（拒绝一期就导黑帧段）**：圈头/尾超视频覆盖段时，导出端补黑帧（纯黑背景 + overlay 数据，模拟回放黑屏段）。**拒绝理由**：(1) 黑帧段要在 GL 里画纯黑而非视频纹理 + 仍叠 overlay + 自己生成 PTS 递增帧喂 encoder（无 decoder 源帧驱动），管线分叉复杂度陡增；(2) 一期价值核心是"有画面的圈段带数据成片"，黑帧段是边角（多数情况圈完整落在视频内，覆盖段外只是 leadIn 前导 3 秒）；(3) road-test-first 先把主路径（有画面段）跑通路测，黑帧段拆 follow-up。**backlog**：`video-export-blackout-frame-segments`（圈头/尾黑帧段叠 overlay 导出），留 §10 + design memo（若 user 路测要求）。
- **rationale**：交集裁剪复用全部既有 `VideoTelemetrySync` 纯函数（lapPlayheadRange / isWithinVideoCoverage / playheadToVideoPosition），与回放按圈逻辑同源（导出段 ⊆ 回放段）。`SEEK_TO_PREVIOUS_SYNC` + 丢弃早帧保证起点精确（H.264 需从关键帧解但只渲染 ≥startPosition 的帧）。终点 EOS 用 PTS 判定。**caveat**：若交集为空（圈完全落在视频覆盖段外，极罕见）→ 导出失败提示"该圈无视频画面"（spec 反例）。FileLogger 记交集起止 position + seek 目标 + 丢弃帧数。

### Decision 6：音轨处理 = 直通 copy（vs 重编码 / 丢弃音轨）

- **选**：源视频音轨（`audio/*`）经 `MediaExtractor` 读 + `MediaMuxer` **直接写**（`readSampleData` → `writeSampleData`，不解不编），按裁剪段的时间范围筛选音频 sample（PTS 落 [startUs, endUs] 才写，PTS 减去 startUs 对齐到 0）。视频轨重编码（烧 overlay），音轨直通。
- **Alt A（拒绝）· 重编码音轨**：解码 + 重编码 AAC。**拒绝理由**：无需求改音频（overlay 不碰音轨），重编码纯属浪费 + 音质损失 + 复杂度。
- **Alt B（拒绝）· 丢弃音轨（导出无声）**：**拒绝理由**：录制时录了音频（L0-2 录音频），成片分享带现场声更完整；丢音轨降级体验。但**若源无音轨**（极少）→ 导出纯视频轨（不强求音轨），spec 反例覆盖。
- **rationale**：音轨直通 = 0 重编码、保真、快。MediaMuxer 支持多轨写入（视频重编码轨 + 音频直通轨）。裁剪段音频 sample 按 PTS 范围筛 + 时间戳平移对齐视频起点。**caveat**：音视频轨 PTS 必须用**同一裁剪起点**平移对齐（否则音画不同步）；FileLogger 记音频 sample 数 + 首末 PTS。

### Decision 7：MediaStore 导出 + minSdk28 兼容（scoped storage vs legacy WRITE_EXTERNAL_STORAGE）

- **选**：写 `MediaStore.Video.Media.EXTERNAL_CONTENT_URI`，`ContentValues` 设 `DISPLAY_NAME`（如 `BlazePush_<track>_lap<N>_<ts>.mp4`）+ `MIME_TYPE=video/mp4` + `RELATIVE_PATH=Movies/BlazePush`（API 29+）。
  - **API 29+（Android 10+）**：scoped storage，`insert` 拿 content URI + `IS_PENDING=1` → `openOutputStream` 写 mp4 字节（从临时编码文件 copy，或直接让 MediaMuxer 写 FileDescriptor）→ 完成置 `IS_PENDING=0`。**无需任何运行时存储权限**。
  - **API 28（Android 9）**：scoped storage 未启用，`MediaStore.Video` 写仍需 `WRITE_EXTERNAL_STORAGE` 运行时权限；`RELATIVE_PATH` 不支持（API 29 引入），改用 `DATA`（绝对路径 `Environment.getExternalStoragePublicDirectory(DIRECTORY_MOVIES)/BlazePush/...`）兼容路径。manifest 声明 `WRITE_EXTERNAL_STORAGE android:maxSdkVersion="28"`（仅 28 需要），API 28 上点导出前先 `RequestPermission(WRITE_EXTERNAL_STORAGE)`（复用 round 1/2 `PermissionRequestOutcome` 范式）。
- **Alt A（拒绝）· 只导到 app 私有目录（不进相册）**：导到 `getExternalFilesDir(Movies)`。**拒绝理由**：app 私有目录用户在系统相册看不到、卸载即删、分享需 FileProvider 绕路 → 违背"存相册可分享"已锁方向。
- **Alt B（拒绝）· 全版本都用 WRITE_EXTERNAL_STORAGE + 绝对路径**：API 29+ 也走 legacy DATA 路径。**拒绝理由**：API 29+ legacy 写需 `requestLegacyExternalStorage`（API 30 起失效），且 scoped storage 是 API 29+ 正路，混用反而踩坑。按版本分流最稳。
- **rationale**：scoped storage（API 29+）写 MediaStore 0 权限是 Android 现行正路（导出到 Movies/ 相册可见）；API 28 是 minSdk 边界，单独走 legacy 权限 + DATA 路径兼容（maxSdkVersion 限定权限仅 28 申请，避免 29+ 误申请被拒）。**MediaMuxer 写 FileDescriptor**：API 26+ `MediaMuxer(FileDescriptor, format)` 支持直接写 content URI 的 fd（`contentResolver.openFileDescriptor(uri, "w")`）→ 编码直接落 MediaStore（免临时文件 copy）；API 28+ 满足（minSdk28）。FileLogger 记 insert URI + 写入字节数 + IS_PENDING 翻转。

### Decision 8：导出分辨率/码率 = 同源分辨率 + 固定码率档（vs 用户可选 / 降分辨率）

- **选**：导出分辨率 = 源视频分辨率（encoder `MediaFormat` 宽高取自 decoder `MediaFormat` 的 `KEY_WIDTH`/`KEY_HEIGHT`，含旋转处理）；码率固定档（如 `width*height*帧率*0.15` bps 或固定 8~12Mbps for 1080p，常量便于调）；帧率取源帧率（`KEY_FRAME_RATE` 缺失则默认 30）；I 帧间隔 1~2 秒。一期**不提供用户分辨率/码率选项**。
- **Alt A（拒绝）· 用户可选分辨率/码率**：导出对话框给"高清/标清"档。**拒绝理由**：一期最小可用，固定同源档先跑通；选项是 UI + 编码参数表 + 测试矩阵膨胀，价值边际低（用户主诉求是"带数据成片"，画质同源已够）。backlog：`video-export-quality-options`。
- **Alt B（拒绝）· 强制降到 720p 省时间/体积**：**拒绝理由**：降分辨率损画质，用户分享想要原画质；同源分辨率 + 硬编码速度可接受（见耗时估算）。
- **rationale**：同源分辨率保画质、参数最少、测试面最小。码率/帧率/I 帧间隔抽常量（`VideoExportConfig`）便于真机后调。**旋转 caveat**：源 mp4 可能带 `KEY_ROTATION`（90/270）；encoder 输出统一"已旋正"的横屏帧（GL 渲染时套源旋转矩阵），或保留 rotation metadata 透传 —— tasks 标注 apply 期按真机源视频 rotation 实测决定（横屏录制通常 rotation=0 或 90，FileLogger 记 source rotation）。

## Risks

- **GL/EGL 样板复杂 + 厂商兼容性**：EGL context 创建 / external texture / shader / encoder Surface 是高踩坑面，厂商 GPU 驱动差异大（external OES 采样、color format）。**mitigation**：(1) GL helper（`OverlayEglCore`/`OverlayGlRenderer`）按 Grafika 成熟范式抄写（业界验证），不从零造；(2) encoder color format 用 `COLOR_FormatSurface`（Surface 输入避免 YUV 手动转换，最兼容）；(3) drain loop 每阶段 FileLogger 埋点 + `runCatching` 包裹，失败 → 删半成品 + Toast"导出失败"+ FileLogger.e 记 GL/codec 错误，不崩 app；(4) 真机攒批路测覆盖华为（默认）+ vivo 小屏，记录各机型 codec name + color format。
- **烧录耗时**：逐帧解码+GL+编码慢。**量级估算**：1080p30 硬编解码，移动端 SoC 通常 100~300 帧/秒吞吐（解码快、GL 合成快、编码是瓶颈）→ 1 分钟视频（1800 帧）约 **6~18 秒**；单圈通常 1~3 分钟 → **十几秒到 ~1 分钟**。**mitigation**：前台 Service 后台跑 + 进度条（Decision 4）；用户可取消；FileLogger 记总帧/总耗时/帧均耗时供路测核对量级；若路测发现某机型过慢（如软编 fallback），follow-up 评估降分辨率档。
- **内存（解码帧 + GL 纹理 + overlay Bitmap）**：Surface-to-Surface 零拷贝（decoder 帧不回 CPU），overlay Bitmap = 1 张 ARGB_8888（1080p ≈ 8MB，每帧复用同一 Bitmap 不重分配）+ 整 session 样本（round 6 估算 < 6MB）。**mitigation**：overlay Bitmap 进 Service 时分配一次循环复用（每帧 `eraseColor(0)` 清空重画）；样本一次性读内存（同回放 Decision 4）；FileLogger 记峰值。**caveat**：超大分辨率（4K）overlay Bitmap ≈ 33MB → 一期假设 ≤1080p（H.264 横屏录制典型），4K 拆 backlog。
- **音画同步（裁剪段 PTS 平移）**：音视频轨用同一裁剪起点平移到 0，错则音画不同步。**mitigation**：视频帧 PTS = 源 PTS - startUs（与 GL 渲染喂 encoder 的 PTS 一致）；音频 sample PTS 同样减 startUs；FileLogger 记音视频首帧 PTS 校验对齐；真机听感验证音画同步。
- **回放端绘制层重构回归**：`SpeedometerGauge`/`GForceBall`/`TrackMiniMap` 改调共享层，可能视觉回归（坐标/颜色/图元翻译错）。**mitigation**：(1) 共享层吃 `android.graphics.Canvas`，回放端经 `drawIntoCanvas{nativeCanvas}` 调，图元 1:1 对应（drawCircle/drawLine/drawPath/drawText）；(2) 抽取时逐函数比对原 DrawScope 调用；(3) `OverlayCanvasPainter` 单测（画到 Bitmap 断言关键像素 / 不崩 / 尺寸）；(4) **真机比对回放 overlay 视觉零变化**（小屏 gate 必走，与导出成片并排比对）；(5) apply 期 #16 自查 verify 回放路径未行为漂移。
- **前台 Service API 34 类型限制**：API 34 起前台 Service 必须声明 `foregroundServiceType` + 满足类型约束（`mediaProcessing` 类型有 6 小时限制 + 需用户可感知通知）。**mitigation**：manifest Service 声明 `android:foregroundServiceType="mediaProcessing"`（API 34）+ `FOREGROUND_SERVICE_MEDIA_PROCESSING` 权限；`startForeground` 带进度通知（用户可见）；导出通常远短于 6 小时限制；API 34 以下不需 type。真机验证华为（可能 API 高）+ 各 ROM 通知显示。
- **大文件 / 存储空间**：导出 mp4 与源同量级（单圈几十 MB~ 上百 MB）。**mitigation**：MediaStore 写入失败（空间不足）→ delete pending 项 + Toast"存储空间不足"+ FileLogger.e；不留半成品。
- **取消半成品清理**：导出中途取消 / 失败 → MediaStore 已 insert 的 pending URI 必须 delete（否则相册留 0 字节坏文件）。**mitigation**：drain loop 标志位中断 + `finally` 块 delete pending URI；FileLogger 记取消 + 清理结果。
- **公共协议 / 圈速链路 / Room 边界**：本 round MUST NOT 触碰 GPS 接收链路 / binary writer / crossing / LapTimingEngine / Room schema / CameraRecordingEngine 录制逻辑 / round 6 纯函数签名。**mitigation**：导出全在新增 export/overlay/service 包 + 纯消费已有 reader API + 纯函数；apply 期 #16 自查 verify 0 触碰。

## 待 user / 主会话拍板

1. **复杂度判定 large → 是否拆多 round**：本 round 含 (a) 共享绘制层抽取 + 回放重构 (b) GL/MediaCodec 烧录管线 (c) 按圈裁剪 + 音轨 (d) MediaStore 导出 (e) 前台 Service + 进度 UI，工作量大。建议拆法（若拍板拆）：**round A** = 共享绘制层抽取 + 回放端复用（独立可验：回放 overlay 视觉零回归）；**round B** = GL/MediaCodec 烧录管线 + 按圈裁剪 + MediaStore 导出 + Service/进度 UI（依赖 round A 的共享绘制层）。或整体一个 large round 串行推。**请拍板**。
2. **第三方库取舍**：design Decision 2 推荐纯 platform MediaCodec/GL（0 新依赖）拒绝 media3-transformer（避免新 artifact + 每帧动态 overlay 在 transformer 1.3.x 支持不成熟）。若 user 倾向用 media3-transformer 省 GL 样板（接受新依赖 + 动态 overlay 踩坑风险），请明示改 Decision 2。
3. **导出分辨率**：Decision 8 一期固定同源分辨率 + 固定码率档（不给用户选）。确认接受"一期无画质选项"。
4. **黑帧段**：Decision 5 一期只导有视频覆盖段（圈头/尾超覆盖的黑帧段不导出，拆 backlog）。确认接受。
5. **音轨**：Decision 6 音轨直通 copy（导出带现场声）。确认（vs 一期先无声更简单）。
