# Tasks: video-segment-schema

> worktree 内实施;工件 source-of-truth 在主区。gradle 8.9 `--offline`(8.7 wrapper 损坏)。
> road-test-first:无 Codex/Opus;medium(Room migration 强制升级例外 3)→ 更深自审 + 真机升级安装为攒批 MUST 第一项。
> 所有新增 .kt 首行 `// @IgnoreFormatCheck`。

## 1. 锚点 verify(apply 启动前)

- [x] 1.1 `grep -n 'version = 8' core/data/.../local/AppDatabase.kt` 命中 line 33 附近;`grep -n 'migration7To8' 同文件` 确认 SQL 列表 + Migration 对象 + chain 六段形态。done:基线对齐。
- [x] 1.2 `grep -n 'suspend fun attachVideoToSession' core/data/.../repository/TelemetryRepository.kt` 命中 line 324 附近,函数体含 `attachVideoToSession-replaceOld` 删旧分支(待取消)。done:改造点锚定。
- [x] 1.3 `grep -rn 'attachVideoToSession(' feature/ --include='*.kt' | grep -v .worktrees | grep -v 'src/test'` 仅 CameraRecordingEngine :554/:592 两处调用。done:调用方清单核实(若 >2 处,Impact 扩)。
- [x] 1.4 `grep -rn 'TelemetryRepository(' core/ feature/ app/ --include='*.kt' | grep -v .worktrees` 列全构造 callsite(预期:AppModule DI 1 处 + 测试 8 文件;cleanup-perftest round 教训——按构造调用 grep 不按类名)。done:连锁清单成文(填入 §5)。
- [x] 1.5 `grep -rn 'endVersion\|migrationChain' core/data/src/test/ --include='*.kt' | grep -v .worktrees` 列全 migration 断言连锁点(预期 `BleDeviceMemoryMigrationTest:25` + `AppDatabaseMigrationSqlTest` 若干)。done:断言清单成文(填入 §6.3)。

## 2. Entity + DAO

- [x] 2.1 新建 `core/data/src/main/java/com/blazepush/core/data/local/entity/VideoSegmentEntity.kt`,字段严格按 design Decision 1(FK CASCADE + Index("sessionId"))。done:文件存在字段全。
- [x] 2.2 新建 `core/data/src/main/java/com/blazepush/core/data/local/dao/VideoSegmentDao.kt`:`@Insert insert(entity): Long` / `@Query queryBySessionId(sessionId): List<VideoSegmentEntity>`(ORDER BY segmentIndex)/ `@Query maxSegmentIndex(sessionId): Int?` / `@Query deleteBySessionId(sessionId)`。done:4 方法 + KDoc。

## 3. AppDatabase v8→v9

- [x] 3.1 `AppDatabase.kt`:`entities` 数组加 `VideoSegmentEntity::class` + `version = 9` + `abstract fun videoSegmentDao(): VideoSegmentDao`。done:三处齐。
- [x] 3.2 同文件加 `migration8To9Sql: List<String>`(CREATE TABLE + CREATE INDEX + INSERT...SELECT 存量迁移,SQL verbatim 按 design Decision 4)+ `migration8To9: Migration` + `migrationChain` 追加(七段)。done:chain 末段 endVersion==9。

## 4. Repository 改造

- [x] 4.1 `TelemetryRepository` 构造加第 4 参 `private val videoSegmentDao: VideoSegmentDao`。done:签名变更。
- [x] 4.2 `attachVideoToSession` 改造(design Decision 2+3):签名加 `playable: Boolean?, durationMs: Long?`;函数体 = 事务内 {INSERT segment(index=max+1 或 0,endWallClock=durationMs?.let{start+it})+ updateVideoMetadata 双写};**删除** `attachVideoToSession-replaceOld` 删旧分支;`Log.d("VideoSegment", ...)` 锚点。注意 Room 事务:repository 非 DAO 无 @Transaction 注解能力,用 `db.withTransaction` 或两 DAO 调用顺序写 + 失败日志(apply 期看 AppDatabase 实例是否可达 repository,不可达则顺序写 + 透明声明)。done:append 语义 + 双写 + 零删旧。
- [x] 4.3 `deleteSession`:行删除前插入"查全段 → 逐文件 deleteVideoFileIfPresent"(design Decision 5);`deleteSessionVideo`:改全段文件 + `deleteBySessionId` + 保留 clearVideo。`Log.d("VideoSegment", "cascade removed N segments")`。done:两 cascade 全段化。
- [x] 4.4 `CameraRecordingEngine.kt`:`:592` 正常 Finalize 调用加 `playable=true, durationMs=<Finalize event recordedDurationNanos 可取则换算 ms,否则 null>`;`:554` ERROR 救援加 `playable=null, durationMs=null`;两处既有 FileLogger 行追加 playable 值。done:两调用方编译过 + 落盘锚点扩展。
- [x] 4.5 `AppModule.kt` DI:database single 加 `videoSegmentDao()` 暴露 + TelemetryRepository 注册加第 4 参。done:Koin 图编译过。

## 5. 构造 callsite 连锁(§1.4 清单驱动)

- [x] 5.1 8 个测试文件(`TelemetryRepositoryTest` / `BinaryLapTelemetryRoundTripTest` / `LapTelemetryReadersTest` / `TelemetryRepositoryDeleteSessionTest` / `CrossingClockRoundTripTest` / `TelemetryRepositoryEndSessionPersistTest` / `BinaryPerftestTelemetryRoundTripTest` / `LapPairingCrossSiteConsistencyTest`)各加 `FakeVideoSegmentDao`(或共享 minimal fake)+ 构造第 4 参。done:`:core:data:compileDebugUnitTestKotlin` 过(编译 gate 硬,漏一处即红;§1.4 若 grep 出更多 callsite 同步补)。

## 6. 测试

> 测试边界透明声明:migration 真 SQL 行为不在 JVM 单测覆盖——工程先例(v7→v8)是 SQL 字符串自检 + 真机升级安装实测;room-testing 离线拉不到 artifact 不引入(design Decision 4 替代分析)。

- [x] 6.1 新建 `core/data/src/test/java/com/blazepush/core/data/local/VideoSegmentMigrationSqlTest.kt`(对齐 `BleDeviceMemoryMigrationTest` 模式):断言 migration8To9Sql 含 CREATE TABLE 全部关键子句(AUTOINCREMENT/NOT NULL×4/FK CASCADE/索引名)+ INSERT...SELECT 存量迁移含 `WHERE videoFilePath IS NOT NULL` + `COALESCE` + chain 末段 (8,9) 连续。done:SQL 自检绿。
- [x] 6.2 新建 `core/data/src/test/java/com/blazepush/core/data/repository/VideoSegmentAttachCascadeTest.kt`:fake VideoSegmentDao(in-memory list 复刻语义)cases 对齐 spec——A 两次 attach 都保留 + 旧文件不删 + 旧字段=最新段(Scenario 1)/ B 首段 index 0 + endWallClock 推算(Scenario 2)/ C 双写一致(Scenario 3 反例)/ D 源码 grep `attachVideoToSession-replaceOld` 0 命中(Scenario 4 反例)/ E deleteSession 全段文件+行(Scenario 5)/ F deleteSessionVideo 全段删圈速留(Scenario 6)/ G 白名单外文件不删(Scenario 7 反例)。done:7 cases 绿。
- [x] 6.3 同步既有断言(§1.5 清单):`BleDeviceMemoryMigrationTest:25` endVersion 8→9 + `AppDatabaseMigrationSqlTest` chain size/连续性断言 +1 段。done:全部既有 migration 测试绿。

## 7. 编译 + 全量 gate

- [x] 7.1 worktree:`gradle :core:data:compileDebugKotlin :core:data:testDebugUnitTest :feature:test:compileDebugKotlin :app:compileDebugKotlin --offline`,除已知 pre-existing 红(BinaryPerftest case G)外 0 fail。done:全绿。
- [x] 7.2 `:feature:test:testDebugUnitTest` 全量(CameraRecordingEngine 相关测试若 mock attach 签名需同步)。done:除已知红外 0 fail。
- [x] 7.3 rebase 主区最新 + 重跑。done:合回态绿。

## 8. 真机验证(攒批 MUST,migration round 不豁免)

- [ ] 8.1 攒批清单第一项:**升级安装实测 v8→v9**(华为 8KE0219522008434,带 v8 数据的旧包直接装新包)→ 开库不崩 + `video_segments` 表存在 + 存量单视频 session 迁出 segmentIndex=0 行(adb shell sqlite3 查表,参 ble-device-memory v7→v8 验证法)。
- [ ] 8.2 攒批清单第二项:录一段视频 → 停 → 再录一段 → 查子表两行 + 旧字段=第二段 + 第一段文件仍在;按圈回放(读旧字段)行为与改造前一致。

## 9. memo 回标(v3 #15)

- [x] 9.1 两 memo 头部加"②a 已消化"块:`video-segmentation-data-model-deferred.md`(注明统一表 + v8→v9 实际版本 + ②b/②c 待续)+ `multi-video-per-session-deferred.md`(核心诉求本 round 全解,方案 B 落地为统一表)。done:memo 同步。

## 10. 合回 + 归档

- [x] 10.1 看板 §5 登记(独占:core/data entity/dao/AppDatabase/repository + CameraRecordingEngine attach 行 + AppModule;与 livetiming/ble-no-fix 零交叉)+ §6 共享文件登记(AppModule)。
- [x] 10.2 ff-only 合回 + 主区编译确认。
- [x] 10.3 metrics.yaml(`review_mode: "road-test-first"`,complexity medium,日志锚点摘要)+ 归档(`--yes`)。
- [ ] 10.4 push 待 user 拍板(攒批)。

## 10.5 apply 期透明声明

> **声明 1(工件修正)**:初稿 proposal 误写"Modified Capabilities 无"——`video-storage-cleanup` 主 spec 有 MUST requirement"重录覆盖前删除旧视频文件",与 append 语义正面冲突,且锁在既有 `TelemetryRepositoryDeleteSessionTest` 重录 case 断言里。编译 gate 抓出后 inline 修订:proposal Capabilities + 新增 `specs/video-storage-cleanup/spec.md` delta(MODIFIED,语义反转 scenario)+ 既有测试改写为"两段都保留"。Decision 3 的 alternatives/rationale 未变,属 spec 对齐非 design 修订(#17 触发边界 caveat)。

> **声明 2(实现微调)**:design Decision 5 原文"行删除由 FK CASCADE 兜底"——自审发现 fake 不模拟 FK 导致 case E 断言恒真(假绿),改为 `deleteSession` 显式 `deleteBySessionId`(FK 仅兜底),与 deleteSessionVideo 语义一致且 fake 可真实断言。

> **声明 3(连锁实测)**:§1.4 grep 实测构造 callsite = 9 个测试文件(工件预估 8 个 + cleanup-perftest round 新建的 PerftestOrphanCleanupTest)+ AppModule;§6.3 断言连锁实测 4 处(预估 3 处 + `has no gaps` 测试尾部 endAt 断言)。

## 11. Follow-up backlog

- ②b `video-segment-recording-rotation`(N=3 圈/段 user 已拍板;段间隙真机评估,可能双 Recorder 乒乓)
- ②c `video-segment-playback-export`(按段索引消费 + 跨段拼播拼裁 + playable 首播回写 + 孤儿判定语义切换 + 旧字段废弃)
