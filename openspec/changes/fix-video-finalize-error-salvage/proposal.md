# Proposal: fix-video-finalize-error-salvage

## Why

2026-06-03 路测:23:02:54 开录 4K,23:06:03 `VideoRecordEvent.Finalize ERROR code=4`(SOURCE_INACTIVE,camera source 被系统掐),**1.15GB 视频文件成孤儿**——文件完整躺在 `files/video/1780498974420.mp4`,但 ERROR 分支(feature/test/.../recording/CameraRecordingEngine.kt:521-529)**不调 `attachVideoToSession`**,DB 无记录,UI 不可达,用户感知"视频丢失"(实际覆盖圈 1 画面)。同 session 第二段录像(24MB,仅尾部 5 秒)正常入库,反而占据了单 `videoFilePath` 字段。

两层问题:
1. **数据救援缺失(本 round 修)**:ERROR ≠ 文件不可用——CameraX Recorder 对 SOURCE_INACTIVE 等错误仍 finalize 已写入数据(昨晚文件 1.15GB 字节完整)。ERROR 分支直接放弃 attach 是把可救的数据黑洞化。
2. **SOURCE_INACTIVE 根因未锁定(本 round 埋诊断,下次路测定位)**:第一段录 3 分 09 秒死(圈 1 完成后 28 秒),第三段录 3 分 28 秒活——排除"时长杀";死亡发生在 BLE 断开前 3 分钟——排除断链因果;录制期 page 1 已离开 composition 但 isRecording 时 VideoCapture 续跑是设计内行为。现有日志无 camera 开关事件,根因(vivo 热管理 / 其他进程抢 camera / 驱动错误)无从区分。

## What Changes

- Finalize ERROR 分支救援:文件存在且 size>0 且 sessionId 非空 → 照常 `attachVideoToSession`(日志标注"ERROR 降级救援");无 session 孤儿沿用 OK 分支删除语义;清空 `_capturedWallClock/_capturedSessionId` 脏字段(现 ERROR 分支不清)。
- bind 处增加 `cameraInfo.cameraState` observer 诊断埋点:CLOSING/CLOSED + error code(CAMERA_IN_USE / FATAL / DISABLED 等)全落 FileLogger——下次路测 SOURCE_INACTIVE 时直接给出系统层死因。
- 沉淀 deferred memo `docs/design/multi-video-per-session-deferred.md`:单 `videoFilePath` 字段装不下一个 session 多段录像(昨晚实景:救活首段后会被尾段覆盖),涉及 Room schema(强制 medium 流程),独立立项。

非目标:SOURCE_INACTIVE 根因修复(证据不足,埋点就位后由路测数据驱动);多段视频 schema(deferred memo)。

## Capabilities

### New Capabilities
- `video-finalize-error-salvage`: Finalize ERROR 时的视频数据救援与 camera 死因诊断可观测。

### Modified Capabilities
<!-- 无:video-storage-cleanup 的孤儿删除 requirements 不变(ERROR 无 session 同样删);video-overlay-playback 不变(救回的视频走既有 wallClock 对齐路径) -->

## Impact

- **代码**:`CameraRecordingEngine.kt` 单文件(ERROR 分支重构 + cameraState observer)。
- **不碰**:Room schema、回放对齐、导出链路。
- **行为变化**:ERROR 结束的录像若有 session 将出现在 session 详情(可能损坏不可播——比黑洞强,播放器报错可感知);后续 OK 段仍会覆盖(单字段现状,memo 展开)。
