## ADDED Requirements

### Requirement: 进入圈速页即创建持久化 Session

系统 SHALL 在用户确认进入圈速页时立即创建 Room `LAP_SESSION`，不得以 GPS 首帧、过线或 REC 作为 Session 创建前置条件。

#### Scenario: GPS 尚未发帧
- **WHEN** 用户进入圈速页但尚未收到同步 GPS 帧
- **THEN** Room 已有本次 attempt 的 LAP_SESSION 占位行

#### Scenario: GPS 与 REC 并发
- **WHEN** GPS 写入和 REC 同时请求 active Session
- **THEN** 两者等待同一创建任务，且只插入一条 Session

### Requirement: 快速退出保留短 Session

系统 MUST 在快速退出时先完成 Session 创建再收尾，并且 MUST NOT 仅因时长为 0 秒或数秒而自动删除该 Session。

#### Scenario: 进页后立即返回
- **WHEN** 用户在 GPS 首帧和 REC 之前立即结束
- **THEN** 系统调用 `endSession`，历史中保留该 0s/几秒 Session

### Requirement: CameraX 启动必须后于 Session 持久化

系统 MUST 只在获得已持久化的非空 `sessionId` 后启动 CameraX；若 Session 已结束或换代，必须 fail closed。

#### Scenario: REC 早于 Room insert 完成
- **WHEN** 用户点 REC 时 Session insert 仍在进行
- **THEN** REC 等待 insert 完成并复核 active Session 后才调 CameraX start

#### Scenario: REC await 期间结束 Session
- **WHEN** REC 正在等 Session，用户同时结束圈速
- **THEN** CameraX 不得启动新录像

### Requirement: Starting 与 Finalize 持久化属于录制状态机

系统 SHALL 用 `Starting` 表示 CameraX start 已请求但 Start 事件未到；Finalize 只有在视频归属写库完成后才能转 Idle 或通知离页。

#### Scenario: Starting 期间重复点 REC
- **WHEN** 引擎处于 Starting
- **THEN** 重复 REC 被忽略，不创建第二个 Recording

#### Scenario: Starting 期间返回
- **WHEN** 返回或 END 发生在 Start 事件前
- **THEN** 引擎进入 Stopping，迟到 Start 不得将其改回 Recording

#### Scenario: Finalize 事件已到但绑定未落库
- **WHEN** CameraX Finalize 已产生非空文件但 `attachVideoToSession` 仍在执行
- **THEN** UI 仍视为停止中，不得提前退出或显示保存完成

### Requirement: 强杀后可恢复新录像归属

系统 MUST 在 CameraX start 前把 Session UUID 写入新 MP4 文件名；冷启动 SHALL 幂等恢复非空、Session 存在且尚未绑定的新格式文件。

#### Scenario: 录制中强杀
- **WHEN** 进程在 Finalize/绑定写库前被杀，且新命名 MP4 非空
- **THEN** 下次启动将该文件以 `playable = null` 绑定回文件名指定的 Session

#### Scenario: 旧时间戳孤儿文件
- **WHEN** MP4 文件名没有 Session UUID
- **THEN** 恢复流程不猜测归属、不自动绑定

#### Scenario: 空文件或 Session 不存在
- **WHEN** 新命名文件为空或文件名中的 Session 不存在
- **THEN** 恢复流程跳过该文件
