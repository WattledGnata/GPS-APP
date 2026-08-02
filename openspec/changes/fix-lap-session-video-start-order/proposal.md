## Why

圈速页当前把 Room Session 推迟到第二个有效 GPS 帧，但相机页在此之前已可点 REC。这使“进入圈速测试”、“Session 持久化”和“CameraX 启动”不是一个有序事务，可产生无法稳定绑定历史记录的视频。

RaceChrono 允许快进快出留下 0 秒或几秒 Session，说明 Session 的产品语义是“用户进入了一次圈速 attempt”，而不是“已收到两帧 GPS”。与其避免短 Session，更应保证任何录像在启动前就有可持久化的归属。

## What Changes

- 进入圈速页即立即创建 Room `LAP_SESSION`，不再等 GPS 或 REC。
- 快速返回仍收尾并保留 0 秒/几秒 Session，不静默删除。
- GPS 和 REC 共用一个互斥创建入口；CameraX 只接受已持久化的非空 `sessionId`。
- 录制增加 `Starting`，覆盖双击 REC、Start 迟到、Starting 期间返回和 Home。
- Finalize 后必须等视频绑定写库完成，才对外进入 Idle/允许离页。
- 新文件名写入 Session UUID；强杀导致 Finalize 未落库时，下次冷启动幂等恢复非空文件归属。
- 修复版本升级为 `1.0.8` / `versionCode 9`。

## Capabilities

### New Capabilities

- `lap-session-video-start-order`: 圈速 Session 与 CameraX 录像的有序启动、收尾和冷启动恢复。

### Modified Capabilities

- `incomplete-lap-session-recovery`: 启动收尾前先恢复新命名格式的未绑定视频。

## Impact

- `feature/test`: Session ViewModel、圈速页与 CameraRecordingEngine 状态机。
- `core/data`: 强杀后视频归属恢复。
- `app`: 启动恢复顺序和 1.0.8 版本号。
- 不修改 Room schema、RaceChrono BLE、Livetiming HTTP 和分段导出实现。
