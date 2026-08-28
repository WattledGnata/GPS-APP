# video-segment-model Specification

> 新 capability(change `video-segment-schema` 引入,系列 ②a):视频段一对多数据模型——video_segments 表、append 写入、双写向后兼容、全段 cascade、存量迁移。②b(按圈轮换)/②c(按段消费)在此契约上扩展。

## ADDED Requirements

### Requirement: video_segments 表与 Room v8→v9 migration

系统 SHALL 新增 `VideoSegmentEntity`(表名 `video_segments`,字段按 design Decision 1:id 自增 PK / sessionId FK CASCADE + index / segmentIndex / filePath / startWallClock 非空 / endWallClock、durationMs、startLapIndex、endLapIndex、playable 可空),`@Database` version SHALL 升至 9,migration8To9 SHALL 加入 `AppDatabase.migrationChain`(SQL 字符串列表模式对齐 `migration7To8Sql` 先例)。migration SHALL 把存量 `videoFilePath IS NOT NULL` 的 session 各迁出一行 segment(`segmentIndex=0, playable=1, startWallClock=COALESCE(videoStartedAtWallClock,0)`)。手写 CREATE TABLE 的列定义、NOT NULL、FK、索引名(`index_video_segments_sessionId`)MUST 与 Room 注解生成的期望 schema 精确一致。

#### Scenario: 存量单视频 session 迁移(正例)

- **WHEN** v8 库中 session S 有 `videoFilePath='/data/.../video/123.mp4', videoStartedAtWallClock=1700000000000`;执行 migration8To9
- **THEN** `video_segments` 出现一行:`sessionId=S, segmentIndex=0, filePath='/data/.../video/123.mp4', startWallClock=1700000000000, playable=1, endWallClock/durationMs/startLapIndex/endLapIndex 均 NULL`
- **AND** session 行旧字段原样保留(双写期间继续有效)

#### Scenario: 无视频 session 不产生 segment 行(正例)

- **WHEN** v8 库中 session T 的 `videoFilePath IS NULL`;执行 migration8To9
- **THEN** `video_segments` 无 sessionId=T 的行

#### Scenario: migration 链断言连锁同步(反例锁)

- **WHEN** version bump 到 9 后跑既有 `BleDeviceMemoryMigrationTest`(`assertEquals(8, last.endVersion)`)与 `AppDatabaseMigrationSqlTest` migrationChain size/连续性断言
- **THEN** 两处断言 MUST 已同步更新(chain 末段 endVersion==9、size+1);若实施漏改,既有测试红——这是有意的连锁 gate,MUST NOT 用放宽既有断言以外的方式绕过

### Requirement: attachVideoToSession 改 append 语义 + 双写向后兼容

`TelemetryRepository.attachVideoToSession(sessionId, videoFilePath, videoStartedAtWallClock, playable: Boolean?, durationMs: Long?)` SHALL:(1) INSERT `video_segments` 新行,`segmentIndex = 该 session 现有 max(segmentIndex)+1`(无行则 0),`endWallClock = durationMs?.let { startWallClock + it }`;(2) 照旧 UPDATE session 旧字段 = 本段(消费方零改动);两步 SHALL 在同一事务内。**MUST NOT 删除任何旧段文件**(round A"覆盖前删旧"在多段模型下取消——旧段是子表登记的合法数据)。调用方 `CameraRecordingEngine` 正常 Finalize SHALL 传 `playable=true`,ERROR 救援分支 SHALL 传 `playable=null`。

#### Scenario: 同 session 两次录制都保留(正例,核心痛点)

- **WHEN** session S 第一次录制 attach(path=A, playable=null ERROR 救援),第二次录制 attach(path=B, playable=true)
- **THEN** `video_segments` 有两行:`(S, 0, A, playable=NULL)` 与 `(S, 1, B, playable=1)`
- **AND** 文件 A 仍存在(未被"删旧"逻辑删除)
- **AND** session 旧字段 = B(最新段,消费方读到与改造前一致)

#### Scenario: 首段 attach segmentIndex 从 0 开始(正例)

- **WHEN** session S 无任何 segment 行,attach(path=A, playable=true, durationMs=60000, startWallClock=W)
- **THEN** 新行 `segmentIndex=0`,`endWallClock=W+60000`

#### Scenario: 双写一致性(反例锁)

- **WHEN** attach 完成后分别读子表最新行与 session 旧字段
- **THEN** 两者 filePath 与 startWallClock MUST 相等——若实现漏掉旧字段 UPDATE(双写断),16 个消费文件读到陈旧段,本 scenario 断言失败

#### Scenario: 删旧逻辑残留(反例)

- **WHEN** 检查 `attachVideoToSession` 实现源码
- **THEN** MUST NOT 存在"查旧 path → deleteVideoFileIfPresent"分支(grep `attachVideoToSession-replaceOld` tag 0 命中)——残留即重录场景静默删合法旧段

### Requirement: 全段 cascade 删除

`deleteSession(sessionId)` SHALL 在删 session 行前查询该 session 全部 segment,逐个对 `filePath` 执行白名单文件删除(复用 `deleteVideoFileIfPresent`),行删除由 FK CASCADE 兜底;`deleteSessionVideo(sessionId)` SHALL 删全段文件 + `deleteBySessionId` 显式删行 + 照旧 `clearVideo` 置空旧字段。

#### Scenario: deleteSession 清全段(正例)

- **WHEN** session S 有 3 段(文件 F0/F1/F2 都存在),调用 `deleteSession(S)`
- **THEN** 3 个文件全删 + `video_segments` 无 S 行 + session 行/crossings/binary 照旧清除

#### Scenario: 成绩页删视频保留圈速(正例)

- **WHEN** session S 有 2 段,调用 `deleteSessionVideo(S)`
- **THEN** 2 文件 + 2 行全删,旧字段置 NULL;session 行/crossings/binary MUST 原样保留

#### Scenario: 白名单防穿越继承(反例)

- **WHEN** 某 segment 行 filePath 被构造为白名单外路径(无 `/video/` 或 `/telemetry/`),调用 `deleteSessionVideo(S)`
- **THEN** 该文件 MUST NOT 被删除(deleteVideoFileIfPresent 白名单 skip),行照常删除
