# Proposal：session-video-metadata-persist

## Why（问题溯源 + baseline + 用户场景）

### 问题溯源

Phase 2 视频管线的核心诉求是"视频帧↔遥测对齐"——即在回放时把视频 PTS（presentation timestamp）映射到遥测时间轴，
实现"视频画面 + 速度曲线 + GPS 轨迹"三路同步回放。这要求在 session 维度持久化两个字段：

1. **视频文件路径（videoFilePath）**：录制结束后，视频文件的 absolute path，供回放时打开文件。
2. **视频首帧 wallClock 锚点（videoStartedAtWallClock）**：录制首帧回调时刻的 `System.currentTimeMillis()`，
   与 binary 样本的 `absoluteTsMs = session.startTs + tsDeltaMs` 同一时钟域。
   未来映射公式：`遥测时间 = videoStartedAtWallClock + videoPTS_ms`。

### 当前 baseline

- `TelemetrySessionEntity`（schema v5）：无视频相关字段。
- `TelemetrySession`（domain model）：同上，无视频字段。
- `TelemetryRepository.deleteSession()`：删 binary file 用 `/telemetry/` canonical-path 白名单，无视频删除路径。
- AppDatabase：version = 5，migrationChain 覆盖 v2→v5。

### 用户场景

- **场景 A（未来 round 3）**：用户录制一圈后进入 session 详情，App 播放视频同步高亮遥测曲线。
  — 依赖：session row 有 videoFilePath + videoStartedAtWallClock，round 3 录制引擎写入。
- **场景 B（未来 round 3）**：用户删除 session，App 连带清理视频文件，不留存储垃圾。
  — 依赖：deleteSession 能找到视频路径并安全删除（本 round 铺管道）。
- **场景 C（本 round）**：无视频的旧 session（v5 数据库）迁移后，新字段为 null，不影响任何现有功能。

## Scope

**本 round 做**：
- TelemetrySessionEntity schema v5 → v6：加两个 nullable 字段
- TelemetrySession domain model：同步加字段
- TelemetryRepository.toDomain() mapper：同步
- AppDatabase.migration5To6 + migrationChain 更新
- TelemetryRepository.attachVideoToSession()：round 3 用以写入视频元数据
- TelemetryRepository.deleteSession()：扩展白名单 `/video/` + 连带删视频文件 + FileLogger 埋点
- AppModule 顺手修 P2（fallbackFrom 去掉 `2`）
- 单测：migration SQL 断言 + deleteSession 视频白名单测试

**本 round 不做**：
- 录制引擎（round 3 `camera-recording-and-gps-sync`）
- 视频回放 UI（Phase 2 后续）
- 视频文件实际写入 videoFilePath / videoStartedAtWallClock（round 3 调 attachVideoToSession 写）
