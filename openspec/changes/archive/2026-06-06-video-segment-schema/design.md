# Design: video-segment-schema

## Context

代码现状(2026-06-07 全部实际 grep 核实):

- schema **v8**(`AppDatabase.kt:33`),migration 架构 = `migrationXToYSql: List<String>`(internal,供 JVM 测试断言)+ `Migration` 对象 + `migrationChain: List<Migration>`(`:333`,v2→v8 六段)统一注册,AppModule `addMigrations(*AppDatabase.migrationChain.toTypedArray())`(`AppModule.kt:66`)+ `fallbackToDestructiveMigrationFrom(1)`
- `attachVideoToSession`(`TelemetryRepository.kt:324-339`):查旧 path → 不同则删旧文件(round A"重录即删旧")→ `sessionDao.updateVideoMetadata` UPDATE 覆盖
- attach 调用方**仅 `CameraRecordingEngine` 两处**:`:554`(Finalize ERROR 救援,fix-video-finalize-error-salvage round)+ `:592`(Finalize OK)
- `deleteSession`(`:251-265`):session 行 + crossings + binary + 单 `videoFilePath` 文件;`deleteSessionVideo`(`:294-299`):单文件 + `clearVideo` 置空字段
- `videoFilePath` 生产消费方 16 文件(含 `core/domain/TelemetryModels.kt` domain model、回放 `LapPlaybackLoader`/`LapVideoPlaybackScreen`、导出 `VideoExportPipeline` 等)——全部假设单文件
- `TelemetryRepository` 构造 3 参 `(context, sessionDao, crossingDao)`;测试侧 8 个文件直接构造(cleanup-perftest round 刚盘点过 fake 清单)
- migration 断言连锁点:`BleDeviceMemoryMigrationTest.kt:25` `assertEquals(8, last.endVersion)` + `AppDatabaseMigrationSqlTest` migrationChain size/连续性断言
- 真机数据证据:2026-06-03 session `e7ee771d` 救援段(1.15GB 圈 1-2 画面)被尾段(24MB 5 秒)覆盖

设计依据:`docs/design/video-segmentation-data-model-deferred.md` + `docs/design/multi-video-per-session-deferred.md`(两 memo schema 合并,user 2026-06-07 L0 拍板;memo 写的 v6→v7 版本号已过期,实为 v8→v9)。

## Goals / Non-Goals

**Goals:**

- 一对多 `video_segments` 表落地,append 写入——救援段/重录段不再被覆盖,零孤儿
- 存量单路径数据无损迁移(v8→v9)
- 消费方(回放/导出/详情屏 16 文件)**零改动**——双写保持旧字段语义
- 全段 cascade 删除(deleteSession / 成绩页删视频)

**Non-Goals:**

- 不做按圈轮换分段录制(②b,N=3 user 已拍板)
- 不做回放/导出按段索引、跨段拼播、playable 首播回写(②c)
- 不切换 video-storage-cleanup 孤儿判定语义、不废弃旧字段(②c;双写期间旧语义仍正确)
- 不动 binary 遥测持久化与公共协议

## Decisions

### Decision 1: 统一表 schema = 两 memo 字段超集

```kotlin
@Entity(
    tableName = "video_segments",
    foreignKeys = [ForeignKey(
        entity = TelemetrySessionEntity::class,
        parentColumns = ["sessionId"], childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class VideoSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val segmentIndex: Int,              // 段序 0 基,append 时取 max+1
    val filePath: String,
    val startWallClock: Long,           // 每段必有(VideoRecordEvent.Start 取 currentTimeMillis)
    val endWallClock: Long? = null,     // 正常 Finalize = start + durationMs;ERROR 救援未知 → null
    val durationMs: Long? = null,
    val startLapIndex: Int? = null,     // ②b 按圈轮换填;本 round 恒 null
    val endLapIndex: Int? = null,
    val playable: Boolean? = null,      // true=Finalize OK;null=ERROR 救援未知(首播回写留 ②c)
)
```

- 替代:两 memo 各建各表 → 拒绝:字段 80% 重叠,两次 migration 纯浪费,user L0 已拍板合并
- 替代:JSON 数组存 session 字段 → 拒绝:撞 A56 红线(memo 3.1 C)
- end/duration nullable 理由:ERROR 救援段 moov 损坏拿不到时长;multi-video memo 的 `playableFlag` 三态语义原样保留

### Decision 2: 双写向后兼容——append 子表 + 旧字段同步更新为"最新段"

`attachVideoToSession` 改为:INSERT `video_segments` 新行(segmentIndex = 现有 max+1)+ 照旧 `updateVideoMetadata` 更新 `session.videoFilePath/videoStartedAtWallClock` = 本段。效果:16 个消费文件读旧字段 = "最新一段",与改造前行为一致(它们本来就只见得到最后 attach 的那段),**零改动零风险**;子表已积累全段数据,②c 切换消费方后废弃旧字段写入。

- 替代:本 round 直接切全部消费方读子表 → 拒绝:16 文件跨 5 模块 ripple(回放拼播/导出拼裁是 ②c 的真机不确定项),一口吃成 large/architectural,违背拆 round 决策
- 替代:旧字段立即废弃置 null → 拒绝:消费方全炸;memo §4.1 本就推荐"迁移期并存"
- 双写一致性:两次 DAO 写包 `@Transaction`(repository 方法标注)防半写

### Decision 3: attach 取消"覆盖前删旧文件",签名加 `playable: Boolean?`

round A 的"重录即删旧"是单路径防孤儿补丁;多段模型下旧段进了子表 = 合法数据(这正是 multi-video memo 的核心诉求:救援段不丢),删除走"成绩页删视频(全段)"或 deleteSession。`attachVideoToSession(sessionId, videoFilePath, videoStartedAtWallClock, playable: Boolean?, durationMs: Long?)` 两个新参;调用方 CameraRecordingEngine `:592` 正常路径传 `playable=true, durationMs=实测时长(Finalize event 可取则传,否则 null)`、`:554` ERROR 救援传 `playable=null, durationMs=null`。

- 替代:保留删旧逻辑 → 拒绝:append 模型下"删旧"删的是子表已登记的合法段,直接造成数据丢失(本 round 要修的 bug 换个姿势复发)
- durationMs 取值:CameraX `VideoRecordEvent.Finalize` 的 `recordingStats.recordedDurationNanos` 可取(纳秒→毫秒);ERROR 分支 stats 不可信传 null——apply 期核实该 API 实际形态,取不到统一传 null(nullable 容忍,②c 首播回写兜底)

### Decision 4: migration v8→v9 = 建表 + 存量 INSERT...SELECT

```sql
CREATE TABLE IF NOT EXISTS video_segments (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    sessionId TEXT NOT NULL,
    segmentIndex INTEGER NOT NULL,
    filePath TEXT NOT NULL,
    startWallClock INTEGER NOT NULL,
    endWallClock INTEGER,
    durationMs INTEGER,
    startLapIndex INTEGER,
    endLapIndex INTEGER,
    playable INTEGER,
    FOREIGN KEY(sessionId) REFERENCES telemetry_sessions(sessionId) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS index_video_segments_sessionId ON video_segments(sessionId);
INSERT INTO video_segments (sessionId, segmentIndex, filePath, startWallClock, playable)
    SELECT sessionId, 0, videoFilePath, COALESCE(videoStartedAtWallClock, 0), 1
    FROM telemetry_sessions WHERE videoFilePath IS NOT NULL;
```

- 存量迁移行 `playable=1`:能被 attach 写入旧字段的段都走过 Finalize OK 或救援入库,历史上用户已在回放屏播过 → 按可播处理;错判代价 = ②c 首播回写纠正,无数据损失
- `COALESCE(videoStartedAtWallClock, 0)`:v6 起两字段同写,理论无"path 非空但 wallClock 空"行,COALESCE 0 防御性容忍脏行(0 = epoch 起点,排序仍稳定)而非 migration 崩溃
- **手写 CREATE TABLE 与 Room 期望 schema 精确一致是头号风险**(列序/NOT NULL/FK/INDEX 任一不符 → 升级用户开库抛 "Migration didn't properly handle"),见 Risks;Room FK 命名/索引名按 Room 生成规约(`index_video_segments_sessionId`)
- 替代:room-testing 真库迁移测试(memo M4 原文)→ 拒绝引入:工程先例(v7→v8 ble-device-memory / pending_lap_uploads)均为 SQL 字符串自检 + 真机升级安装攒批实测,离线环境拉不到 room-testing artifact;沿先例并在 tasks 透明声明

### Decision 5: cascade 全段删除

`deleteSession`:FK CASCADE 自动删子表行,但**文件必须显式删**——删行前先 `videoSegmentDao.queryBySessionId(sessionId)` 取全段 path 逐个 `deleteVideoFileIfPresent`(复用白名单);旧字段单文件删除逻辑保留(双写期间与 segment 0..n 中最新段同路径,二次 delete no-op)。`deleteSessionVideo`(成绩页删视频):删全段文件 + `videoSegmentDao.deleteBySessionId` + 照旧 `clearVideo` 置空旧字段。

- 替代:依赖 FK CASCADE 顺带删文件 → 不可能:SQLite 不管文件系统;显式删是唯一路径

### Decision 6: 日志锚点(road-test-first MANDATORY)

| 位置(模块) | 手段 | 内容 |
|---|---|---|
| attach append(core/data) | `Log.d("VideoSegment", ...)` | sessionId/segmentIndex/playable/双写完成 |
| migration8To9(core/data) | 无运行时日志(migration 内不打) | 由真机升级安装后查表验证 |
| cascade 全段删(core/data) | `Log.d("VideoSegment", ...)` | 删除段数 |
| CameraRecordingEngine 两调用方(feature/test) | 既有 `FileLogger.d(TAG, "attachVideoToSession...")` 锚点扩展 | 追加 playable 实参值落盘 |

core/data 用 `Log.d`(FileLogger 模块边界不可达,cleanup-perftest round Decision 5 同款);落盘锚点靠 CameraRecordingEngine 既有 FileLogger 行扩展。

## Risks / Trade-offs

- [手写 CREATE TABLE 与 Room 期望 schema 不一致 → 升级用户开库崩] → SQL 严格按 Decision 4(AUTOINCREMENT/NOT NULL/FK/索引名全按 Room 生成规约);单测断言 SQL 字面量含全部关键子句;**真机升级安装(v8 旧包 → v9 新包)是攒批 MUST 第一项**,与 ble-device-memory v7→v8 同款验证法
- [FK CASCADE 在 Room 默认 enforcement 下才生效,若 PRAGMA 被关 → 孤儿行] → Room 默认开 FK;cascade 删除路径不依赖 FK(deleteSessionVideo 显式 deleteBySessionId),FK 只是 deleteSession 的兜底冗余
- [双写两次 DAO 写非原子 → 半写不一致] → repository 方法加 `@Transaction`(Room runInTransaction);失败整体回滚 + FileLogger 由调用方落盘
- [TelemetryRepository 构造加参波及 8 个测试文件] → 编译 gate 天然硬(cleanup-perftest round 实证连第 8 个非标准命名 fake 都抓得出);apply 期 grep `TelemetryRepository(` 列全
- [取消删旧后存储增长] → 有意语义变化:多段=合法数据;存储治理是 `storage-quota-and-cleanup-ui`(路线图 §7 占位)与 ②c 孤儿语义切换的事
- [migration 断言连锁漏改 → 既有测试红] → tasks 显式列两处(`BleDeviceMemoryMigrationTest:25` + `AppDatabaseMigrationSqlTest` 链断言);编译/测试 gate 兜底

## Migration Plan

v8→v9 strict migration(migrationChain 追加,不动 destructive fallback 范围);部署即生效,存量单视频 session 迁出 segmentIndex=0 行。回滚 = revert commit + 数据库降级不支持——但 v9 只增表,革命面小:revert 后 v8 代码不识 `video_segments` 表(Room 容忍多余表),旧字段双写期间数据无损,回滚安全(memo M 条款"旧字段保留一个版本周期"的设计意图)。

## Open Questions

(无——schema 字段/双写/取消删旧/migration SQL/连锁清单全部已决;durationMs 的 CameraX API 形态留 apply 期核实,nullable 设计已容忍两种结果。)
