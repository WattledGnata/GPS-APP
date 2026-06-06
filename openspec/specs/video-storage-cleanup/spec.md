# video-storage-cleanup Specification

## Purpose
TBD - created by archiving change video-storage-cleanup. Update Purpose after archive.
## Requirements
### Requirement: 重录覆盖前删除旧视频文件

**(②a 废止原行为)** `attachVideoToSession` 改为 append 语义(见 `video-segment-model` capability):同 session 再次 attach SHALL 新增 segment 行,**MUST NOT 删除任何旧段文件**——旧段是 `video_segments` 表登记的合法数据(停录再录 / ERROR 救援重录都是用户要保留的画面,2026-06-03 路测"圈 1 救援段被尾段覆盖"即原行为的事故)。原"从源头杜绝重录孤儿"诉求由以下承接:旧段有行引用不再是孤儿;用户主动清理走"成绩页删视频(全段)";session 删除走 deleteSession 全段 cascade。

#### Scenario: 同 session 重录两段都保留(原"删旧"scenario 语义反转)

- **WHEN** session 已有 segment(path=A),attach 新路径 B(B≠A)
- **THEN** 文件 A MUST 仍存在,`video_segments` 含 A、B 两行,session 旧字段=B(最新段)

#### Scenario: 首次 attach 无旧段(行为不变)

- **WHEN** session 无任何 segment,attach 路径 B
- **THEN** 新增 segmentIndex=0 行,MUST NOT 因无旧段报错

#### Scenario: 反例——删旧逻辑残留即数据丢失

- **WHEN** 实现残留"查旧 path → 删文件"分支(grep `attachVideoToSession-replaceOld` 命中 >0)
- **THEN** 重录场景静默删除合法旧段——测试断言"old file kept on re-record"失败

### Requirement: 成绩页单删视频保留圈速成绩

系统 SHALL 提供 `deleteSessionVideo(sessionId)`：删除该 session 视频文件（白名单）+ 置空 `videoFilePath`/`videoStartedAtWallClock`，但 MUST NOT 删除圈速 / crossing / binary / session 行。`LapSessionDetailScreen` MUST 在 hasVideo 时提供"删除视频"入口，删后刷新使回放入口消失、成绩仍在。

#### Scenario: 删视频后成绩保留
- **WHEN** 用户在成绩页点"删除视频"
- **THEN** 系统 SHALL 删视频文件 + 置空 video 字段；session 圈速 / crossing / binary MUST 仍在，UI 圈速列表不变

#### Scenario: 删后回放入口消失
- **WHEN** deleteSessionVideo 完成、UI 刷新
- **THEN** `hasVideo` SHALL 变 false，圈行的视频回放入口 MUST 消失

#### Scenario: 反例——MUST NOT 误删成绩
- **WHEN** deleteSessionVideo 执行
- **THEN** MUST NOT 调用 `deleteSession` / 删 crossing / 删 binary；圈速数据 MUST 完整保留

### Requirement: 无 session 录制完成后删除孤儿文件

`CameraRecordingEngine` 录制 Finalize OK 时，若 `sessionId == null`（无 active lap session），MUST 删除该视频文件（不写库、UI 不可达 = 纯垃圾），并埋日志。

#### Scenario: 无 session 录制自动删
- **WHEN** 录制 Finalize OK 且 sessionId==null
- **THEN** 系统 SHALL 删除刚落盘的文件，MUST NOT 留在 filesDir/video/

#### Scenario: 有 session 录制不删（正常关联）
- **WHEN** 录制 Finalize OK 且 sessionId 非空
- **THEN** 系统 SHALL 走 attachVideoToSession 正常关联，MUST NOT 删除该文件

#### Scenario: 反例——只删本次孤儿，不波及他人
- **WHEN** 无 session 录制删孤儿
- **THEN** 系统 MUST 只删本次 Finalize 的那一个文件路径，MUST NOT 扫描/删除 filesDir/video/ 下其他文件

### Requirement: 删除安全（白名单 + 不扫地 + 不误删在录文件）

所有视频文件删除 MUST 经统一 helper `deleteVideoFileIfPresent`：路径 canonicalPath MUST 含 `/video/` 或 `/telemetry/` 白名单才删（防路径穿越），不存在则 skip，删失败埋日志不抛。系统 MUST NOT 做全盘目录扫描删非引用文件；MUST NOT 删除正在录制中的文件。

#### Scenario: 白名单外路径拒删
- **WHEN** 待删路径 canonicalPath 不含 `/video/` 也不含 `/telemetry/`
- **THEN** helper MUST skip 删除（埋日志），MUST NOT 删该文件

#### Scenario: 不做全盘扫描
- **WHEN** 任何清理路径执行（重录/删 session/手动/无 session）
- **THEN** 实现 MUST NOT 遍历 filesDir/video/ 目录"删所有非 DB 引用文件"

#### Scenario: 反例——绝不删在录文件
- **WHEN** 某录制正在进行（RecordingState.Recording）
- **THEN** 任何清理 MUST NOT 删除该正在写入的文件（无 session 删只发生在该文件自己 Finalize 之后；attach 删的是旧路径）

