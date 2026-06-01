## Why

录制视频在内部存储 `filesDir/video/` 越积越多，无任何回收，且无法单删视频。**Baseline（已查实）**：

- `TelemetrySessionEntity.videoFilePath: String?` 单路径；`TelemetryRepository.attachVideoToSession`（`core/data/.../TelemetryRepository.kt:293`）= `UPDATE ... SET videoFilePath=?`，**覆盖语义**——同 session 重录直接覆盖字段、**旧文件不删 → 孤儿永留**。
- `CameraRecordingEngine`（`feature/test/.../recording/CameraRecordingEngine.kt`）每次录制写 `filesDir/video/<ts>.mp4`；Finalize 时 `sessionId == null`（无 active lap session）→ "孤立视频不写库" → **文件留在磁盘、UI 永远摸不到 → 纯孤儿**。
- 删除只有 `deleteSession`（`:251`）级联删 session + 圈数据 + binary + 单个视频；**没有"只删视频、保留圈速成绩"** 的能力。

**用户场景**：手机存储稀缺；练习中误触"开始→stop"产生废片、同 session 重录、无 session 录制都留垃圾；想在成绩页单独删掉某场视频但保留圈速成绩。

**清理策略（user 拍板）**：**不做全盘目录扫描**（扫"删非引用文件"脆弱——DB 不一致即误删，且未来视频分片会有"轮换中未入库"的中间文件被误删）。改**生命周期驱动**：只在明确时刻删（重录覆盖、删 session、手动删、无 session 录制完成）。

## What Changes

- **重录即删旧**：`attachVideoToSession` 覆盖 `videoFilePath` 前，先删旧文件（源头断孤儿）。
- **成绩页"删视频"**：新增 `deleteSessionVideo(sessionId)`——删视频文件 + 置空 `videoFilePath`/`videoStartedAtWallClock`，**保留圈速成绩**；`LapSessionDetailScreen` 加"删除视频"入口（仅 hasVideo 时显示）。
- **无 session 录制自动删**：`CameraRecordingEngine` Finalize OK 时若 `sessionId == null` → 删该孤儿文件（UI 不可达的纯垃圾）。
- **抽公共删除 helper** `deleteVideoFileIfPresent`（白名单校验 + 删 + 日志），`deleteSession`/`attach`/`deleteSessionVideo` 复用。
- **明确不做**：全盘目录扫描清理；Room schema 改动；视频分片（一对多模型，见 deferred memo）。

## Capabilities

### New Capabilities

- `video-storage-cleanup`: 录制视频的生命周期删除（重录删旧 / 手动删视频保留成绩 / 无 session 自动删）与删除安全约束。

### Modified Capabilities

（无。`history-deletion`（deleteSession 级联）行为不变，仅内部抽 helper 复用。）

## Impact

- **模块**：`core/data`（`TelemetryRepository` + `TelemetrySessionDao`）、`feature/test`（`CameraRecordingEngine` + `LapSessionDetailScreen`）。
- **文件**：`TelemetryRepository.kt`（helper + attach 删旧 + deleteSessionVideo）、`TelemetrySessionDao.kt`（新增 `clearVideo` 置空 query）、`CameraRecordingEngine.kt`（无 session 删）、`LapSessionDetailScreen.kt`（删视频按钮 + 删后刷新）。
- **依赖/schema**：无新增依赖、**无 Room schema 改动**、无网络。纯本地存储治理。
- **协议边界**：MUST NOT 触碰 GPS 接收链路 / binary writer / 圈速逻辑 / crossing。
- **测试**：`deleteVideoFileIfPresent` 白名单 + IO 纯逻辑单测；`deleteSessionVideo` 经 fake DAO 单测（置空 + 文件删调用）。真机：重录不留旧、成绩页删视频后成绩仍在、无 session 录制不留文件。
- **执行模式**：road-test-first。复杂度 small-medium。
- **关联**：分片一对多模型延期，见 `docs/design/video-segmentation-data-model-deferred.md`（A 的删除钩子届时平移成按段删）。
