# Proposal: video-segment-schema

## Why

当前视频数据模型是**单路径覆盖**:`TelemetrySessionEntity.videoFilePath/videoStartedAtWallClock`(schema v6 引入)一个 session 至多挂一个视频,`TelemetryRepository.attachVideoToSession`(`TelemetryRepository.kt:324-339`)UPDATE 覆盖 + round A 补丁"覆盖前删旧文件"。固有缺陷已被真机路测坐实(2026-06-03,session `e7ee771d`):第 1 段 1.15GB 覆盖圈 1-2 画面的 ERROR 救援段,被第 2 段仅 5 秒的 24MB 尾段覆盖——**按圈回放时无画面可对齐,救援段白救**。同时单文件长录风险(1080p/30 一小时 ~7.2GB;Finalize ERROR moov 未写完=整段报废)要求未来按圈分段(对标 RaceChrono ≤3 圈/段),单字段装不下分段。

两份 deferred memo(`docs/design/multi-video-per-session-deferred.md` 2026-06-04 + `docs/design/video-segmentation-data-model-deferred.md` 2026-06-02)的 schema 字段重叠 80%,user 2026-06-07 L0 拍板:**合并为统一一张表,拆 3 子 round 串行(②a schema → ②b 按圈轮换 N=3 → ②c 跨段拼播拼裁),本 round 是 ②a**——建表 + migration + append 写入,通过"双写旧字段=最新段"保持 16 个消费文件零改动,立刻解决覆盖丢段痛点。

## What Changes

- **新表 `video_segments`**(`VideoSegmentEntity`,统一两 memo 超集 schema):`id PK autoGenerate / sessionId FK CASCADE + index / segmentIndex / filePath / startWallClock / endWallClock? / durationMs? / startLapIndex? / endLapIndex?(②b 填) / playable?`(Finalize OK=true,ERROR 救援=null 未知,首播回写留 ②c)
- **Room v8→v9 migration**:建表 + 存量 `videoFilePath != null` 的 session 迁成 `segmentIndex=0` 行(`startWallClock=videoStartedAtWallClock`,end/duration null 容忍);对齐工程 migrationChain 模式(`AppDatabase.kt:333` SQL 字符串列表 + chain 追加)
- **`attachVideoToSession` 语义改 append**:INSERT 子表新段(segmentIndex 自增)+ 同步 UPDATE 旧字段=本段(双写,消费方零改动)+ **取消 round A"覆盖前删旧文件"**(多段模型下旧段是合法数据,M3 平移);签名加 `playable: Boolean?`,调用方 `CameraRecordingEngine` 两处(`:554` ERROR 救援传 null /`:592` 正常传 true)同步
- **cascade 扩展**:`deleteSession` / `deleteSessionVideo` 删该 session 全部 segment 文件(复用 `deleteVideoFileIfPresent` 白名单)+ rows(FK CASCADE 删行,文件显式删)
- **`TelemetryRepository` 构造加第 4 参 `VideoSegmentDao`** → AppModule DI + 全部测试构造 callsite 连锁同步(v3 #14,apply 期 grep 列全)
- **测试**:migration SQL 自检(`AppDatabaseMigrationSqlTest` 工程先例模式)+ **两处既有断言连锁同步**(`BleDeviceMemoryMigrationTest:25` `endVersion==8`→9 + `AppDatabaseMigrationSqlTest` 链 size 断言)+ append/双写一致/cascade/存量迁移 cases

**不做(后续子 round)**:②b 录制引擎按圈轮换切段(N=3,user 已拍板)/ ②c 回放导出按段索引 + 跨段拼播 + 孤儿判定语义切换 + 旧字段废弃。

**②a 交付边界透明声明**:本 round 保的是**数据**——救援段/重录段全部入子表、文件不再被删,零丢失;但回放/导出仍读旧字段(=最新段),旧段"存而暂不可见"。"按圈回放圈 1 有画面"的**体验**闭环在 ②c(按 wallClock 选段)交付。

## Capabilities

### New Capabilities

- `video-segment-model`: 视频段一对多数据模型——video_segments 表、append 写入语义、双写向后兼容、全段 cascade 删除、存量单路径迁移。

### Modified Capabilities

- `video-storage-cleanup`: **废止"重录覆盖前删除旧视频文件"requirement**(round A 单路径下的防孤儿补丁)——多段模型下旧段是子表登记的合法数据,MUST NOT 删;"不堆垃圾"诉求由"成绩页删视频(全段)"+ deleteSession 全段 cascade 承接。其余 requirement(成绩页单删/白名单/不全盘扫描)语义平移到全段,孤儿判定切换仍留 ②c。

> apply 期修正记录(2026-06-07):工件初稿误写"Modified Capabilities 无";编译 gate 跑既有 `TelemetryRepositoryDeleteSessionTest` 重录 case 时暴露该行为锁在 video-storage-cleanup 主 spec normative + 测试断言里。Decision 3(取消删旧)本身的 alternatives/rationale 不变,属 spec 对齐 inline 修订,metrics.yaml 透明声明。

## Impact

- `core/data/src/main/java/com/blazepush/core/data/local/entity/VideoSegmentEntity.kt` — 新建
- `core/data/src/main/java/com/blazepush/core/data/local/dao/VideoSegmentDao.kt` — 新建(insert/queryBySessionId/deleteBySessionId/maxSegmentIndex)
- `core/data/src/main/java/com/blazepush/core/data/local/AppDatabase.kt` — entities 注册 + version=9 + migration8To9 + chain 追加
- `core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt` — 构造第 4 参 + attach 改造 + 两 cascade 扩展
- `feature/test/src/main/java/com/blazepush/feature/test/recording/CameraRecordingEngine.kt` — 两处 attach 调用加 playable 实参
- `feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt` — DI 注入第 4 参
- `core/data/src/test/` — 新测试 + 8 个测试文件 TelemetryRepository 构造连锁 + 2 处 migration 断言连锁
- **零改动**:回放/导出/详情屏等 16 个 videoFilePath 消费文件(双写保证旧字段=最新段)

### 协议兼容性

不涉及 GPS 接收链路 / replay / RaceChrono BLE 公共协议;纯接收端本地 Room。

### 复杂度与 review 模式

**medium**(Room schema migration 命中强制升级例外 3)——road-test-first 模式下意味着:CC 主会话深度自审(本 round 已拆出 ②b/②c 控 scope)+ FileLogger 锚点 + 真机升级安装为攒批 MUST 项;不调 Codex/Opus 子 agent(user 2026-05-29 拍板模式)。
