# Deferred Memo: multi-video-per-session(一个 session 多段录像)

> ✅ **已消化(2026-06-07,round `video-segment-schema` ②a,commit 见 archive)**:
> 核心诉求(救援段/重录段不丢)全部落地——方案 B 的 `session_videos` 子表与
> `video-segmentation-data-model` memo 的 `video_segments` 合并为统一一张表(user L0 拍板),
> 字段取超集(含本 memo 的 playable 三态语义);attach 改 append + 双写旧字段向后兼容;
> §2 路测实景(e7ee771d 救援段被尾段覆盖)不再发生。schema migration 实为 v8→v9
> (memo 沉淀时版本号已过期)。playable 首播回写留 ②c `video-segment-playback-export`。

> 起源:2026-06-04,fix-video-finalize-error-salvage round 期间决策延期。
> 下次立项直接读本 memo 起草 proposal/design。

## 1. 现状

`telemetry_sessions` 表单字段挂视频:`videoFilePath TEXT` + `videoStartedAtWallClock INTEGER`(schema 见 AppDatabase)。`CameraRecordingEngine` Finalize OK → `attachVideoToSession` 直接 UPDATE 覆盖。一个 session 内多段录像(停了再录 / ERROR 后重录)只有**最后一段**入库。

## 2. 数据证据(2026-06-03 路测实景)

session `e7ee771d`(23:02-23:09,2 圈):
- 第 1 段:23:02:54 开录,23:06:03 Finalize ERROR code=4,1.15GB(`files/video/1780498974420.mp4`),覆盖圈 1 画面——修复前黑洞,fix-video-finalize-error-salvage 后会 attach 但仍会被第 2 段覆盖;
- 第 2 段:23:09:25-23:09:30,24MB 仅 session 尾部 5 秒,实际入库的就是它——按圈回放圈 1/圈 2 时无画面可对齐。

## 3. 方案对比

| 方案 | 说明 | 评估 |
|---|---|---|
| A 保持单字段+覆盖策略 heuristic(更长/更早优先) | 无 schema 改 | 任何 heuristic 都有反例(故意重录 vs 意外分段),拒绝 |
| B `session_videos` 子表(sessionId FK, path, startedAtWallClock, durationMs, playableFlag) | 标准一对多 | 推荐;迁移简单(新表+旧字段数据搬迁) |
| C 文件名约定扫目录(运行时枚举 video/ 按 wallClock 归属) | 零 schema | DB 与文件系统双真相源,孤儿清理(video-storage-cleanup)语义冲突,拒绝 |

## 4. 推荐方案 + 分析

方案 B。回放对齐:现有 wallClock 对齐算法(`videoStartedAtWallClock` 与遥测 absoluteTsMs 差值)天然支持多段——每圈选「startedAtWallClock ≤ 圈起点 < startedAtWallClock+duration」的段;无命中段显示"该圈无录像"。存储:子表行数 = 录像段数(个位数/session),无压力。`playableFlag`:Finalize OK=true,ERROR 救援=null(未知,首播时回写)。

## 5. 实施约束(MUST)

- Room schema migration(@Database version bump)→ **强制 medium 流程**(CLAUDE.md 升级例外 3);
- 旧 `videoFilePath` 字段迁移后保留只读一个版本周期(回滚安全)再删;
- `attachVideoToSession` 改 INSERT 子表;消费方盘点:回放屏 / 导出链路 / video-storage-cleanup 孤儿判定(从"session 无 videoFilePath"改"子表无行")——**盲点 #16 共享字段 drift 检查必跑**;
- 一段录像跨多圈是常态,子表段与圈是时间窗口关系不是外键关系。

## 6. 单元测试覆盖

迁移 SQL 测试(AppDatabaseMigrationSqlTest 模式);多段选段算法纯函数单测(圈起点落段内/段间隙/重叠段);cleanup 孤儿判定新语义。

## 7. 与当前 round 协同

fix-video-finalize-error-salvage 已让 ERROR 段入库(单字段,可被覆盖)+ attach 日志可见覆盖动作;本 memo round 落地后救援段不再丢。

## 8. 不并入当前 round 的理由

schema migration 强制 medium 流程 + 消费方跨回放/导出/清理三链路,改动面是当前 round(单文件 ERROR 分支)的 5 倍以上;救援与 schema 解耦可独立交付。

## 9. 立项节奏估算

medium,约 1 天(migration 0.25 + attach/消费方改造 0.5 + 测试 0.25)。建议 round 名 `multi-video-per-session`。
