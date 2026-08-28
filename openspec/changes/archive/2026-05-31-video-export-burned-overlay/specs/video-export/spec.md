## ADDED Requirements

### Requirement: 共享 overlay 绘制层（回放与导出双端复用）

系统 SHALL 提供一个吃 `android.graphics.Canvas` 的纯绘制层 `OverlayCanvasPainter`，把速度指针表 / G 球摩擦圆 / 赛道小地图 / 圈速计时面板的绘制逻辑下沉为纯函数，消费同一套 `GaugeMath` / `TrackMiniMapProjection` 纯数学。回放端（Compose）与导出端（GL 烧录）MUST 复用同一套绘制函数，不得各写一套绘制实现。

绘制函数 MUST 与现有 Compose 组件视觉等价（同图元、同坐标映射、同数学），重构后回放 overlay 视觉行为零变化。

#### Scenario: 共享绘制层产出与回放视觉一致

- **GIVEN** 一组 overlay 数据（speedKmh=132、latG=0.8、lonG=-0.3、当前 lat/lon、lapNumber=3、elapsedMs、deltaMs）
- **WHEN** 在一个 `Bitmap` 的 `Canvas` 上调 `OverlayCanvasPainter.drawSpeedometer/drawGForceBall/drawTrackMiniMap/drawLapTimePanel`
- **THEN** 速度指针角度等于 `GaugeMath.speedToNeedleAngle(132)`、G 球动点偏移等于 `GaugeMath.gForceToBallOffset(0.8, -0.3)`、小地图投影等于 `TrackMiniMapProjection.project(...)`（与回放端经 `drawIntoCanvas{nativeCanvas}` 调用同函数得到的几何完全一致）

#### Scenario: 回放端经 nativeCanvas 复用共享层不崩

- **GIVEN** 回放屏 `SpeedometerGauge` 重构为调 `OverlayCanvasPainter.drawSpeedometer`
- **WHEN** Compose `Canvas { drawIntoCanvas { c -> OverlayCanvasPainter.drawSpeedometer(c.nativeCanvas, cx, cy, radius, speedKmh) } }` 渲染
- **THEN** 渲染不抛异常，速度表显示与重构前一致（指针/刻度/读数同位置同值）

#### Scenario: 反例 — 双端各写一套绘制实现违反约束

- **GIVEN** 一次实现把导出端 overlay 绘制复制为独立函数（不复用 `OverlayCanvasPainter`，与回放端两套绘制代码）
- **WHEN** 检视绘制代码路径
- **THEN** 该实现违反本 requirement（MUST 单一共享绘制层）；测试 / review MUST 标记为不合规 —— 导出与回放 overlay 绘制 MUST 由同一组 `OverlayCanvasPainter` 函数产出

#### Scenario: 反例 — null overlay 数据降级不崩

- **GIVEN** overlay 数据缺失（speedKmh=null、lapNumber=null、currentLat=null）
- **WHEN** 调 `OverlayCanvasPainter` 各绘制函数
- **THEN** 绘制不崩；速度读数显示 "--"、圈号显示 "LAP --"、圈速显示 "--:--.---"、小地图仅画轮廓不画当前点（与回放端降级一致）

### Requirement: GL 离屏烧录管线（视频帧 + overlay 合成进像素）

系统 SHALL 用纯 platform API（`MediaExtractor` + `MediaCodec` decoder/encoder + GLES20 离屏渲染 + `MediaMuxer`）把原始视频帧与每帧 overlay 合成到每个像素，编码导出新 mp4。导出 MUST NOT 引入第三方编解码库（FFmpeg / mp4parser / media3-transformer）。

每帧 overlay 数据 MUST 由帧 PTS 经 `VideoTelemetrySync.frameWallClock` + `findNearestSampleIndex` + `VideoOverlayTelemetry.resolveCurrentLap` + `computeDeltaMs` 算出（复用既有纯函数），不得另起一套同步逻辑。

#### Scenario: 烧录管线把 overlay 合成进帧像素

- **GIVEN** 一段源 mp4（视频轨 + 音轨）+ 整 session 遥测样本 + 该圈 best reference
- **WHEN** 导出管线逐帧解码 → 每帧 PTS 算 overlay 数据 → 共享绘制层画 overlay Bitmap → GL 把视频帧纹理 + overlay Bitmap 纹理合成 → encoder 编码 → MediaMuxer 封装
- **THEN** 产出的新 mp4 每帧像素含烧死的 overlay 图层（任何播放器打开都自带数据，无需 app）；视频轨重编码、音轨直通 copy

#### Scenario: 每帧 overlay 数据复用既有同步纯函数

- **GIVEN** 帧 PTS=12340ms、videoStartedAtWallClock=T
- **WHEN** 管线算该帧 overlay 数据
- **THEN** frameWallClock = `VideoTelemetrySync.frameWallClock(T, 12340)`；样本 index = `findNearestSampleIndex(frameWallClock, sampleWallClocks)`；圈号/elapsed = `VideoOverlayTelemetry.resolveCurrentLap(frameWallClock, lapWindows)`；delta = `computeDeltaMs(...)`（与回放端同一函数得同结果）

#### Scenario: 反例 — 引入第三方编解码库违反约束

- **GIVEN** 一次实现为省 GL 样板而加 `media3-transformer` 或 FFmpeg 依赖做导出
- **WHEN** 检视 `feature/test/build.gradle.kts` 与 gradle/libs.versions.toml
- **THEN** 该实现违反本 requirement（MUST 纯 platform API，0 新编解码依赖）；导出 MUST 用 MediaCodec/GL/MediaMuxer/MediaStore platform API

#### Scenario: 反例 — GL/codec 失败降级不崩 app

- **GIVEN** 某机型 encoder 配置失败 / GL context 创建失败 / 源视频轨格式不支持
- **WHEN** 导出管线执行
- **THEN** 失败被 `runCatching` 捕获，删除半成品（MediaStore pending 项），通知/Toast "导出失败"，FileLogger.e 记录 codec/GL 错误，app 不崩溃

### Requirement: 按圈裁剪导出

系统 SHALL 按圈导出（一圈一文件），导出段 = 圈时间轴 [`lapStart - leadIn`, `lapEnd`]（`VideoTelemetrySync.lapPlayheadRange`）与视频覆盖段的交集，转成视频内 position（`playheadToVideoPosition`）。一期 MUST 只导出有视频画面覆盖的段（圈头/尾超视频覆盖段的黑帧段不导出）。

#### Scenario: 圈完整落在视频覆盖段内

- **GIVEN** 圈 [lapStart, lapEnd] 完整落在视频覆盖段 [videoStart, videoStart+duration] 内
- **WHEN** 导出该圈
- **THEN** 导出段 = [max(lapStart-leadIn, videoStart), lapEnd] 转成的视频 position；`MediaExtractor.seekTo(startPositionUs, SEEK_TO_PREVIOUS_SYNC)` 起、PTS > 终点 position 即 EOS 止；产出单圈成片

#### Scenario: 圈头早于视频起点（前导段超覆盖）

- **GIVEN** lapStart - leadIn < videoStart（圈起点前导 3 秒早于视频开始）
- **WHEN** 导出该圈
- **THEN** 导出起点钳到 videoStart（交集起点），前导超覆盖段不导出黑帧（一期）；导出文件从视频实际起点对应的 overlay 数据开始

#### Scenario: 反例 — 圈完全落在视频覆盖段外

- **GIVEN** 圈 [lapStart, lapEnd] 与视频覆盖段无交集（圈完全在视频时间窗之外，极罕见）
- **WHEN** 导出该圈
- **THEN** 交集为空，导出失败，Toast/通知 "该圈无视频画面"，不产生空文件，FileLogger 记录交集为空

### Requirement: 导出落 MediaStore 相册（minSdk28 兼容）

系统 SHALL 把编码完成的 mp4 写入 `MediaStore.Video`（Movies/BlazePush），用户可在系统相册看到并分享。API 29+ 走 scoped storage（content URI + IS_PENDING，无运行时权限）；API 28 走 `WRITE_EXTERNAL_STORAGE` 运行时权限 + 兼容路径。

#### Scenario: Android 10+ scoped storage 导出无需权限

- **GIVEN** 设备 API >= 29
- **WHEN** 导出完成写 MediaStore
- **THEN** 经 `insert(EXTERNAL_CONTENT_URI, ContentValues{DISPLAY_NAME, MIME_TYPE=video/mp4, RELATIVE_PATH=Movies/BlazePush, IS_PENDING=1})` 拿 URI → MediaMuxer 写 fd → 完成置 IS_PENDING=0；全程无运行时存储权限；文件在系统相册 Movies/BlazePush 可见

#### Scenario: Android 9（API 28）走 legacy 权限 + 兼容路径

- **GIVEN** 设备 API == 28
- **WHEN** 用户点导出
- **THEN** 先 `RequestPermission(WRITE_EXTERNAL_STORAGE)`（复用 `PermissionRequestOutcome` 范式）；授权后用 `DATA` 绝对路径（DIRECTORY_MOVIES/BlazePush）写 MediaStore（API 28 不支持 RELATIVE_PATH）；manifest `WRITE_EXTERNAL_STORAGE android:maxSdkVersion="28"` 限定仅 28 申请

#### Scenario: 反例 — 导出取消 / 写入失败清理半成品

- **GIVEN** 导出中途用户取消，或 MediaStore 写入因空间不足失败
- **WHEN** 中断 / 失败发生
- **THEN** `finally` 块 delete 已 insert 的 pending URI（不在相册留 0 字节坏文件）；Toast 提示取消/失败；FileLogger 记录清理结果

#### Scenario: 反例 — API 29+ 误申请 WRITE_EXTERNAL_STORAGE

- **GIVEN** 设备 API >= 29
- **WHEN** 用户点导出
- **THEN** MUST NOT 申请 `WRITE_EXTERNAL_STORAGE`（manifest maxSdkVersion=28 限定 + 代码按 SDK_INT 分流），直接走 scoped storage insert；若实现在 API 29+ 仍申请该权限则违反本 requirement

### Requirement: 前台 Service 执行 + 进度 UI

系统 SHALL 用前台 Service 执行导出 drain loop（不阻塞 UI、不随 Composable 销毁），通过通知 + UI 进度对话框展示百分比，支持取消。详情屏 SHALL 在有视频的圈行提供"导出带数据视频"入口（区别于现有"播放"入口）。

#### Scenario: 详情屏发起导出 + 进度展示

- **GIVEN** session 有视频，用户在某 VALID/BEST 圈行点"导出带数据视频"
- **WHEN** 启动 `VideoExportService`（startForegroundService 传 sessionId/lapIndex）
- **THEN** Service `startForeground` 带进度通知；UI 显示进度对话框（"导出中 N%" + 取消按钮）；drain loop 后台推进、回传已处理帧/总帧百分比；完成弹"已保存到相册" + 可拉起系统分享 Intent（ACTION_SEND video/mp4）

#### Scenario: 导出过程不阻塞 UI 且可取消

- **GIVEN** 导出正在 Service 中跑
- **WHEN** 用户点取消 / 切到其他屏
- **THEN** UI 不卡顿（导出在 Service 后台线程）；点取消 → Service 中断 drain loop（标志位）+ stopSelf + 清理半成品；切屏不中断导出（Service 独立于 Composable 生命周期）

#### Scenario: 反例 — 无视频的圈不显示导出入口

- **GIVEN** session 无视频（videoFilePath=null），或圈为 INVALID/INCOMPLETE
- **WHEN** 渲染详情屏圈行
- **THEN** 该圈行 MUST NOT 显示"导出带数据视频"入口（与"播放"入口同 gate）；点不到导出，不会启动无源的导出 Service
