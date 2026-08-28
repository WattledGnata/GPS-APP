# video-segment-model Specification

## Purpose
TBD - created by archiving change video-segment-schema. Update Purpose after archive.
## Requirements
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

### Requirement: 按圈窗口选段契约

系统 SHALL 提供纯函数 `VideoSegmentSelector.selectForWindow(segments, windowStartMs, windowEndMs)`:返回与窗口 `[windowStartMs, windowEndMs]` 有 wallClock 重叠的段,按 `segmentIndex` 升序;段的有效区间为 `[startWallClock, endWallClock ?: +∞]`——`endWallClock == null`(ERROR 救援段时长未知)MUST 保守入选(漏选=救援段画面再次不可见,即 ②a 修的事故复发)。回放/导出 SHALL 经此函数选段,选段为空时行为与现状一致(该圈无录像)。

#### Scenario: 窗口在单段内(正例主路径)

- **WHEN** 段 A=[1000,5000]、B=[8000,12000],窗口=[2000,4000]
- **THEN** 返回 [A]

#### Scenario: 窗口跨两段(正例)

- **WHEN** 同上段集,窗口=[4000,9000]
- **THEN** 返回 [A, B](升序)

#### Scenario: 救援段 null endWallClock 保守入选(反例锁)

- **WHEN** 段 R=[3000, endWallClock=null](救援段),窗口=[100000,200000](远在 start 之后)
- **THEN** R MUST 入选——若实现把 null 当零长(`endWallClock ?: startWallClock`),本 scenario 断言失败,救援段永不可见

#### Scenario: 无覆盖返回空(正例)

- **WHEN** 段 A=[1000,5000],窗口=[6000,7000]
- **THEN** 返回空列表(调用方走"该圈无录像"现状路径)

### Requirement: 多段回放 wallClock 按段映射

回放屏 SHALL `setMediaItems(选中段升序列表)`;playhead wallClock SHALL = `selected[player.currentMediaItemIndex].startWallClock + player.currentPosition`(每段独立基准);段间 gap 由 playlist item 切换自然跳过(剪辑语义,MUST NOT 为 gap 插黑场假播)。

#### Scenario: 第二段播放中的映射(正例)

- **WHEN** 选中段 [A(start=1000), B(start=8000)],ExoPlayer currentMediaItemIndex=1、currentPosition=500
- **THEN** playheadWallClock = 8500(B 基准,非 A 基准 1500)

#### Scenario: 单段行为不回归(正例)

- **WHEN** 选中段仅 [A(start=1000)],currentPosition=2000
- **THEN** playheadWallClock = 3000,与 ②a 前单文件行为一致

### Requirement: 跨段导出 v1 明确拒绝

导出管线选段后:单段覆盖 SHALL 直接以该段为输入(`sourcePath = seg.filePath`,Clip 窗口计算基准 = `seg.startWallClock`,既有 `isLapFullyCovered` 完整覆盖 gate 原样);多段覆盖 SHALL fail 并给出明确文案("该圈横跨多段录像,导出暂不支持")+ `FileLogger.e`(含 "cross-segment" 字样),**MUST NOT 静默选某段导出**(降级段必然不完整覆盖,被既有 gate 拦截——做不可达降级不如诚实拒绝)。完整拼裁为 follow-up `video-export-cross-segment-concat`。

#### Scenario: 单段导出(正例主路径)

- **WHEN** 圈窗口被段 A 完整覆盖
- **THEN** 导出输入=A.filePath,映射基准=A.startWallClock,既有 clip/烧录链路不变

#### Scenario: 跨段明确拒绝带日志(反例锁)

- **WHEN** 圈窗口同时与 A、B 两段重叠
- **THEN** 导出 fail 文案含"横跨多段" + FileLogger.e 含 "cross-segment"——若实现静默选段继续导出,本 scenario 断言失败

### Requirement: playable 首播回写

回放首帧渲染成功(`onRenderedFirstFrame`)SHALL 对当前段执行 `updateSegmentPlayable(id, true)`(仅 `playable == null` 的段写,幂等);播放错误(`onPlayerError`)SHALL 写 `false`。`VideoSegmentDao` SHALL 新增 `updatePlayable(id, playable)` @Query。回写失败仅日志,MUST NOT 影响播放。

#### Scenario: 救援段首播成功收敛(正例)

- **WHEN** 段 playable=null,回放该段首帧渲染成功
- **THEN** 该段 playable 更新为 true

#### Scenario: 已知段不重复写(正例,幂等)

- **WHEN** 段 playable=true,再次播放成功
- **THEN** MUST NOT 触发 update(避免每次播放写库)

#### Scenario: 播放失败标记损坏(正例)

- **WHEN** 段 playable=null,ExoPlayer 抛 PlaybackException
- **THEN** 该段 playable 更新为 false(UI 灰显消费留 follow-up)

### Requirement: 按圈轮换分段(N=3)

录制中(`RecordingState.Recording`)每完成 **3 圈**(录制开始后计数,基线=进入 Recording 时的 completedLaps)系统 SHALL 自动切段:旧段 `stop()` 异步 finalize 入库(②a append 语义自动成段),立即起新段;录制状态 SHALL 保持 `Recording` 不经过 `Stopping`(用户视角连续录制)。段边界 SHALL 落在圈完成 crossing 通知时刻(memo M5)。非录制状态下圈完成通知 SHALL no-op。

#### Scenario: 第 3 圈完成触发轮换(正例)

- **WHEN** 录制中,录制开始后第 1、2 圈完成不切段,第 3 圈完成
- **THEN** 触发 rotateSegment,`video_segments` 最终出现 ≥2 行(旧段 + 新段),recordingState 全程 Recording

#### Scenario: 录制前已完成的圈不计数(正例,基线语义)

- **WHEN** session 已完成 2 圈后才开始录制,随后完成第 3 圈(录制后第 1 圈)
- **THEN** MUST NOT 触发轮换(计数=1,基线扣除录制前的 2 圈)

#### Scenario: 非录制状态通知 no-op(反例)

- **WHEN** recordingState=Idle/Stopping 时圈完成通知到达
- **THEN** 计数不变、不切段、不抛

### Requirement: per-recording 事件上下文隔离

每个 Recording 的 `outputFile/sessionId/wallClock` SHALL 由该 recording 的事件 listener 闭包持有;轮换时旧段 finalizing 与新段 starting 并存,新段 `VideoRecordEvent.Start` MUST NOT 污染旧段写库参数;旧段 `Finalize` 的 `activeRecording = null` 与状态机转移 MUST 仅在 `activeRecording` 仍指向本段时执行(轮换后旧段 Finalize 只写库)。

#### Scenario: 轮换期间旧段写库参数不被污染(反例锁,核心)

- **WHEN** 轮换发生:旧段(wallClock=W1)stop 后、其 Finalize 到达前,新段 Start 事件已写入 wallClock=W2
- **THEN** 旧段 Finalize 写库的 `videoStartedAtWallClock` MUST = W1——若实现仍读实例单字段(被 W2 覆盖),段时间轴错位,②c 选段/回放全错

#### Scenario: 旧段 Finalize 不误杀新段(反例)

- **WHEN** 轮换后新段已 Recording,旧段 Finalize 到达
- **THEN** `activeRecording` MUST 仍指向新段(不被置 null),recordingState 仍 Recording

#### Scenario: 最终 stop 行为不回归(正例)

- **WHEN** 用户停录(非轮换),Finalize OK
- **THEN** activeRecording 置 null + 状态转移 + attach 写库,与 ②b 前行为一致

### Requirement: 段时长硬上限兜底

单段录制时长达 **10 分钟**(`MAX_SEGMENT_DURATION_MS=600_000`,经 `VideoRecordEvent.Status` 的 recordedDuration 检查)SHALL 触发轮换(reason=duration-cap)——防单圈异常长/挂死不过线时单文件无限增长(memo M5 兜底)。

#### Scenario: 挂死不过线时段不无限长(正例)

- **WHEN** 录制中 10 分钟无任何圈完成
- **THEN** Status 检查触发轮换,旧段 ≤10min+1 个 Status 周期

### Requirement: 段间隙记录(memo M1)

轮换产生的新段 `Start` 时系统 SHALL 计算并以 FileLogger 落盘 `gap`(新段 wallClock − 旧段结束估计),供真机攒批量化单 Recorder 切段丢帧、决策双 Recorder 乒乓是否立项。**MUST NOT 静默轮换**(无 gap 日志 = M6 评估失去数据源)。

#### Scenario: 轮换 gap 落盘(反例锁)

- **WHEN** 任一轮换完成
- **THEN** debug_log 含 `segment gap=` 字样——grep 0 命中说明日志被删,M6 真机评估失效

