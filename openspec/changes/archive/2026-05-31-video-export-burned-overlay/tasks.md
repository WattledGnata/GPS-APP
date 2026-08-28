# Tasks — video-export-burned-overlay

> road-test-first 模式（user 已授权该批）：FileLogger 密集埋点（GL/codec/MediaStore 关键状态转移 + 每 30 帧采样）+ 真机攒批路测兜底，不调 Opus 子 agent。
> apply 启动前 MUST 跑 #3/#14/#16 自查（见 §0）。复杂度 large，是否拆 round 见 design "待拍板"——若拍板拆，阶段 1 = round A（共享绘制层 + 回放复用），阶段 2-7 = round B。

## 0. apply 启动前自查（road-test-first 强制）

- [ ] 0.1 #3 grep 锚点对齐：`grep -n "fun buildFrames\|fun resolveCurrentLap\|fun computeDeltaMs" feature/test/src/main/java/com/blazepush/feature/test/usecase/VideoOverlayTelemetry.kt`；`grep -n "fun frameWallClock\|fun findNearestSampleIndex\|fun lapPlayheadRange\|fun playheadToVideoPosition" feature/test/src/main/java/com/blazepush/feature/test/recording/VideoTelemetrySync.kt`；`grep -n "fun speedToNeedleAngle\|fun gForceToBallOffset" feature/test/src/main/java/com/blazepush/feature/test/usecase/GaugeMath.kt`；`grep -n "fun project" feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/TrackMiniMap.kt` —— 验证 design baseline 锚点行号/签名与生产代码一致（large round 不在 archive，无 rebase 漂移，但仍 verify）。
- [ ] 0.2 #16 跨 round 共享字段 drift：本 round 纯消费 round 6 纯函数（不改其签名 / 不加共享 entity 字段）→ 列消费方 = 回放屏 `LapVideoPlaybackScreen` + 本 round 导出管线；verify 共享绘制层重构后回放端行为零漂移。
- [ ] 0.3 #14 不涉及 DAO 接口变更（纯读已有 reader）→ 无 fake DAO stub 补充，记录"N/A"。

## 1. 共享 overlay 绘制层抽取 + 回放端复用（round A 候选）

- [ ] 1.1 新增 `feature/test/src/main/java/com/blazepush/feature/test/overlay/OverlayCanvasPainter.kt`（首行 `// @IgnoreFormatCheck`）：object，纯 `android.graphics.Canvas` 绘制函数。从 `SpeedometerGauge.kt:54-142` 抽 `drawSpeedometer(canvas, cx, cy, radius, speedKmh, paints)`（复用 `GaugeMath.speedToNeedleAngle` + 现有刻度/指针/读数几何）；从 `GForceBall.kt:51-93` 抽 `drawGForceBall(canvas, cx, cy, radius, latG, lonG, paints)`（复用 `GaugeMath.gForceToBallOffset`）；从 `TrackMiniMap.kt:138-163` 抽 `drawTrackMiniMap(canvas, w, h, padding, points, currentLat, currentLon, paints)`（复用 `TrackMiniMapProjection.project`）；新建 `drawLapTimePanel(canvas, x, y, lapNumber, elapsedMs, deltaMs, paints)`（圈号 + Score 风格圈速字符串 + delta 颜色，对应 `LapVideoPlaybackScreen.kt:478-519` LapTimeCorner 逻辑，复用 `formatElapsed`/`formatDelta`）。**done condition**：四函数编译通过，吃 `android.graphics.Canvas` + `Paint` 容器，无 Compose 依赖。
- [ ] 1.2 `SpeedometerGauge.kt`：把 `Canvas { DrawScope … }` 块（:54-142）改为 `Canvas(...) { drawIntoCanvas { c -> OverlayCanvasPainter.drawSpeedometer(c.nativeCanvas, …) } }`（Paint 用 `remember` 缓存避免每帧重建）。底部读数 Text（:144-157）保留 Compose（或并入共享层，按视觉一致取舍）。**done condition**：编译过，回放速度表视觉与改前一致（真机比对 1.6）。
- [ ] 1.3 `GForceBall.kt`：同 1.2，`Canvas` 块（:51-93）改调 `OverlayCanvasPainter.drawGForceBall`。
- [ ] 1.4 `TrackMiniMap.kt`：`Canvas` 块（:138-163）改调 `OverlayCanvasPainter.drawTrackMiniMap`（`TrackMiniMapProjection` 仍是数学源，共享层只搬 drawPath/drawCircle）。
- [ ] 1.5 `LapVideoPlaybackScreen.kt` LapTimeCorner（:478-519）：圈速面板改调 `OverlayCanvasPainter.drawLapTimePanel`（或保留 Compose Text 但确保导出端 `drawLapTimePanel` 视觉对齐——文字用 nativeCanvas.drawText）。**caveat**：圈速字符串走 Score 风格（V2：非 DSEG7），共享层 drawText 用对应 Paint typeface。
- [ ] 1.6 新增 `OverlayCanvasPainterTest.kt`（`feature/test/src/test/.../overlay/`，首行 `// @IgnoreFormatCheck`）：画到 `Bitmap(ARGB_8888)` 断言不崩 + 尺寸正确 + null 数据降级（"--"/"LAP --"/无当前点）；speedToNeedleAngle / gForceToBallOffset 几何经共享层调用结果与直接调 GaugeMath 一致。**done condition**：JVM 单测绿（Bitmap 用 Robolectric 或仅断言函数调用不抛——按现有测试惯例，若 Bitmap 需 instrumentation 则降级断言纯几何）。
- [ ] 1.7 FileLogger 埋点：共享层重构无运行时新状态，但回放端首次复用打一条 `FileLogger.d("OverlayPainter", "shared painter wired: speedo/gball/minimap/laptime")`。
- [ ] 1.8 **真机 gate（round A 验收）**：vivo V2405A 小屏 + 华为，回放 overlay 四角视觉与重构前**零变化**（并排比对截图）。

## 2. GL 离屏渲染骨架（round B）

- [ ] 2.1 新增 `feature/test/src/main/java/com/blazepush/feature/test/export/gl/OverlayEglCore.kt`（首行 `// @IgnoreFormatCheck`）：EGL14 context 创建 / makeCurrent / swapBuffers / release（按 Grafika `EglCore` + `WindowSurface` 范式）。**done condition**：可创建以 encoder input Surface 为目标的 EGLSurface。
- [ ] 2.2 新增 `export/gl/OverlayGlRenderer.kt`：external OES texture（`GL_TEXTURE_EXTERNAL_OES` + `samplerExternalOES` shader）画满帧（套 `SurfaceTexture.getTransformMatrix`）+ 2D texture 叠 overlay Bitmap（`glTexImage2D` + alpha blend）。提供 `prepareSurfaceTexture(): SurfaceTexture`（给 decoder 输出）+ `drawFrame(stMatrix, overlayBitmap)`。**done condition**：shader 编译通过、两纹理合成逻辑就位、FileLogger 记 GL error（glGetError）。
- [ ] 2.3 FileLogger 埋点：EGL 创建 / shader 编译 / 每次 glGetError 非 0 → `FileLogger.e("ExportGL", ...)`。

## 3. 解码-叠加-编码 drain loop（round B）

- [ ] 3.1 新增 `export/VideoExportConfig.kt`：码率/帧率/I 帧间隔/MIME（`video/avc`）/color format（`COLOR_FormatSurface`）常量（Decision 8，便于真机后调）。
- [ ] 3.2 新增 `export/VideoExportPipeline.kt`（首行 `// @IgnoreFormatCheck`）：`MediaExtractor` 选视频轨读 MediaFormat（含 rotation）→ `MediaCodec` decoder 输出到 `OverlayGlRenderer.prepareSurfaceTexture()` 的 Surface → `MediaCodec` encoder（`COLOR_FormatSurface` + `createInputSurface()`，EGL 渲染目标）→ `MediaMuxer`。单线程同步 drain loop（Decision 3）：feedDecoder / drainDecoderToSurface / awaitFrameAvailable / 每帧算 overlay 数据（3.3）+ 画 overlay Bitmap（复用 OverlayCanvasPainter）+ GL drawFrame + swapBuffers(带 PTS) / drainEncoderToMuxer / EOS 传递。**done condition**：能把一段源 mp4 重编码出带 overlay 的新 mp4（先写临时文件验证，4 接 MediaStore）。
- [ ] 3.3 每帧 overlay 数据：decoder 帧 PTS → `VideoTelemetrySync.frameWallClock(videoStartedAtWallClock, pts/1000)` → `findNearestSampleIndex(frameWallClock, sampleWallClocks)` → `OverlayFrame` + `VideoOverlayTelemetry.resolveCurrentLap` + `computeDeltaMs`（**复用 round 6 纯函数**，loadLapPlaybackData 同源样本/best reference 加载逻辑参 `LapVideoPlaybackScreen.kt:575-664`，可抽公共加载 helper）。
- [ ] 3.4 overlay Bitmap 复用：进管线分配一张 `Bitmap(srcW, srcH, ARGB_8888)`，每帧 `eraseColor(0)` 清空 + `Canvas(bitmap)` 调 `OverlayCanvasPainter` 四函数（角标位置按帧宽高布局，对应回放 OverlayHud 四角 `LapVideoPlaybackScreen.kt:392-424`）。
- [ ] 3.5 FileLogger 埋点：decoder/encoder 配置（codec name + color format + 宽高 + rotation）；每 30 帧记 in/out buffer idx + PTS + 当前 overlay 数据（spd/lonG/latG/delta）+ 渲染耗时；EOS；总帧/总耗时/帧均耗时（耗时量级核对）。

## 4. 按圈裁剪 + 音轨直通（round B）

- [ ] 4.1 裁剪段计算：用 `VideoTelemetrySync.lapPlayheadRange(lapStart, lapEnd)` ∩ 视频覆盖段 → 起止视频 position（`playheadToVideoPosition`）；`MediaExtractor.seekTo(startPositionUs, SEEK_TO_PREVIOUS_SYNC)`，丢弃 PTS < startPosition 的解码帧（不渲染不喂 encoder），PTS > endPosition 即触发 EOS。交集为空 → 抛特定异常（导出失败"该圈无视频画面"，spec 反例）。**done condition**：导出文件时长 ≈ 圈在视频内的覆盖时长。
- [ ] 4.2 音轨直通：另一 `MediaExtractor` 选音频轨，`readSampleData` → `MediaMuxer.writeSampleData`（不解不编），仅写 PTS ∈ [startUs, endUs] 的 sample，PTS 减 startUs 对齐到 0（与视频帧 PTS 同起点平移，Decision 6）。源无音轨 → 跳过音轨（纯视频导出，spec 反例）。
- [ ] 4.3 旋转处理：源 MediaFormat 若带 `KEY_ROTATION`（90/270）→ GL 渲染套旋转矩阵输出旋正帧，或透传 rotation metadata（apply 期按真机源视频 rotation 实测决定，FileLogger 记 source rotation）。
- [ ] 4.4 FileLogger 埋点：裁剪交集起止 position + seek 目标 + 丢弃帧数；音频 sample 数 + 首末 PTS（音画对齐校验）；source rotation。

## 5. MediaStore 导出 + minSdk28 兼容（round B）

- [ ] 5.1 新增 `export/VideoExportMediaStoreWriter.kt`（首行 `// @IgnoreFormatCheck`）：API >= 29 → `insert(MediaStore.Video EXTERNAL_CONTENT_URI, ContentValues{DISPLAY_NAME, MIME_TYPE=video/mp4, RELATIVE_PATH=Movies/BlazePush, IS_PENDING=1})` → `openFileDescriptor(uri, "w")` 给 `MediaMuxer(fd, format)`（API 26+ fd 构造）→ 完成 IS_PENDING=0。API == 28 → `WRITE_EXTERNAL_STORAGE` 权限 + `DATA` 绝对路径（DIRECTORY_MOVIES/BlazePush）。**done condition**：导出文件系统相册可见。
- [ ] 5.2 半成品清理：drain loop 标志位中断 / 异常 → `finally` delete pending URI（不留 0 字节坏文件，spec 反例）。
- [ ] 5.3 `app/src/main/AndroidManifest.xml`（:18-21 视频录制权限块附近）加 `<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />` + 前台 Service 权限（`FOREGROUND_SERVICE` + API 34 `FOREGROUND_SERVICE_MEDIA_PROCESSING`）。
- [ ] 5.4 API 28 权限懒请求：复用 `PermissionRequestOutcome.from` 范式（参 round 1/2），仅 `Build.VERSION.SDK_INT == 28` 时点导出前请求 `WRITE_EXTERNAL_STORAGE`（API 29+ 不请求，spec 反例）。
- [ ] 5.5 FileLogger 埋点：MediaStore insert URI + 写入字节数 + IS_PENDING 翻转 + 清理结果。

## 6. 前台 Service + 进度 UI + 分享（round B）

- [ ] 6.1 新增 `export/VideoExportService.kt`（首行 `// @IgnoreFormatCheck`）：前台 Service，`startForeground` 带进度通知（API 34 `foregroundServiceType="mediaProcessing"`）；onStartCommand 取 sessionId/lapIndex → 后台线程跑 `VideoExportPipeline`；进度（已处理帧/总帧 → %）经共享 `StateFlow` 单例 / LocalBroadcast 回传；完成/失败/取消更新通知；取消 = stopSelf + 中断标志 + 清理。manifest 声明 Service。
- [ ] 6.2 `LapSessionDetailScreen.kt`（圈行播放入口工厂 :499/:574 旁）加"导出带数据视频"入口（同 gate：仅 VALID/BEST + 有视频显示；V2 视觉单行 Ellipsis + 末尾固定元素前 Spacer）。点击 → `startForegroundService` + 显示进度对话框（百分比 + 取消，观察 6.1 StateFlow）。**M2 教训**：async 三态用 if/else 禁 early-return。
- [ ] 6.3 完成处理：进度对话框收 100% → 关闭 + Toast"已保存到相册" + 可选拉起系统分享 Intent（`ACTION_SEND`，`type=video/mp4`，content URI 用 5.1 的 MediaStore URI）。
- [ ] 6.4 FileLogger 埋点：Service start/stop/cancel；进度回传节流（每 5% 记一次）；完成弹分享 / 失败 Toast。

## 7. 收尾

- [ ] 7.1 编译全绿：`feature:test` + `:app` build（offline gradle 8.9，参 reference_gradle_wrapper_8_7_corrupt_use_8_9）。
- [ ] 7.2 既有单测不回归：`VideoOverlayTelemetryTest` / `VideoTelemetrySyncTest` / `GaugeMath` 相关 / `TrackMiniMapProjection` 相关 + 新增 `OverlayCanvasPainterTest` 全绿。
- [ ] 7.3 **真机攒批路测**（串行，一次 adb install）：华为（默认）+ vivo V2405A 小屏。验收：导出某圈 → 进度条推进 → 完成落相册 → 系统相册打开成片 overlay 四角与回放一致（速度表/G 球/小地图/圈速）+ 不畸变 + 音画同步；取消半成品不留；API 版本（28 权限流 / 29+ 无权限）按机型验。记录各机型 codec name + color format + 耗时量级。
- [ ] 7.4 metrics.yaml（归档时）：`review_mode: "road-test-first"` + `review_rounds_l1/l2: 0` + `codex_l1/l2_findings: []`（注 road-test-first 去 Codex）+ `complexity: "large"` + FileLogger 埋点锚点摘要 + `design_decisions_diverged_during_apply: []`（apply 期 #17 透明声明）。

## 10. Follow-up backlog（延期立项 memo link）

- [ ] 10.1 **黑帧段导出**（`video-export-blackout-frame-segments`）：圈头/尾超视频覆盖段叠 overlay 的黑帧导出（一期 Decision 5 只导有画面段）。若 user 路测要求 → 立 memo `docs/design/video-export-blackout-segments-deferred.md`。
- [ ] 10.2 **导出画质选项**（`video-export-quality-options`）：用户可选分辨率/码率（一期 Decision 8 固定同源 + 固定码率）。
- [ ] 10.3 **多圈拼接导出** / 4K-HDR / 竖屏视频导出 / 非 LAP_SESSION 导出（一期 scope 外，§What Changes 不做列表）。
- [ ] 10.4 **media3-transformer 迁移评估**（若一期手写 GL 路测发现厂商兼容性差，Decision 2 升级路径）：共享绘制层产出 Bitmap 喂 BitmapOverlay。
