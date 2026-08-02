## Context

`TelemetryRepository.startSession()` 先插入 `endTs = startTs`、`lapCount = 0` 的占位行；只有内存中的 active writer 存在且用户走到 `endSession()`，才会关闭 writer、配对 StartFinish crossing 并写回汇总。进程异常结束后 writer 状态不可恢复，但 Room 中的 crossing、video segment 和 binary 路径仍然存在。

本次现场 session 正是该形态：8 条 accepted StartFinish crossing 可配对为 7 圈，5 个 `playable=true` 视频段已关联，但 session 汇总仍为占位值。恢复必须直接消费持久化证据，不能依赖已丢失的 ViewModel/Recorder 状态。

## Goals / Non-Goals

**Goals:**

- 在覆盖升级后的首次启动、以后每次冷启动及每次进入 Records 的 LAPS 子页时，恢复此前进程留下的未闭环 LAP_SESSION。
- 与正常 `endSession()` 使用相同的 accepted StartFinish 真壁钟配对口径。
- 恢复过程幂等、无损、失败隔离，并避免与本次进程中新建 session 竞争。
- 保留既有视频关联；物理 MP4 仍在时，恢复后的 session 可继续被历史/详情消费。

**Non-Goals:**

- 本轮不实现 Home 后持续后台计时、Foreground Service 或返回键体验改造。
- 不扫描或自动关联未登记的孤立 MP4；不修复已损坏视频容器。
- 不从 Livetiming 服务端反向下载圈速，不触发任何上传或补传。
- 不修改 Room schema、RaceChrono BLE 协议或 Livetiming HTTP 契约。

## Decisions

### Decision 1：用现有占位不变量识别未闭环 session

新增 DAO 查询 `sessionType = 'LAP_SESSION' AND endTs <= startTs AND startTs < processStartedAt`。`endTs == startTs` 是现有 startSession 占位契约；使用 `<=` 兼容潜在脏数据。进程启动时捕获 cutoff，异步恢复只处理 cutoff 之前的行，避免用户快速开始新计时后被启动任务误收尾。

拒绝仅按 `lapCount == 0` 判断，因为正常结束但零圈的 session 也可能合法存在。

### Decision 2：提取共享的持久化汇总计算

将 accepted StartFinish crossing 配对和 binary 最高速度计算收敛到 repository 内部 helper，正常 `endSession()` 与恢复路径共享同一口径：

- crossing 按 `crossingWallClockTimestampMs ?: Long.MAX_VALUE` 排序；
- 只有相邻两条均有真壁钟才形成一圈；
- `lapCount = durations.size`，`bestLapMs = durations.minOrNull()`；
- binary 可读时取正数最高速度，否则 `topSpeedKmh = null`。

恢复不要求 active writer；它直接读取 entity 指向的 binary 文件和 crossing 表。

### Decision 3：结束时间只取本地持久化证据

候选结束时间依次来自：最后一条非空 crossing wallClock、video segment 的 `endWallClock`（无 end 时使用 `startWallClock + durationMs`，再退到 start）、以及 `session.startTs + binary.max(tsDeltaMs)`。仅接受位于 `[startTs, recoveryNow]` 的候选，最终取最大值；没有有效候选时写 `startTs + 1`，保证闭环查询可见且不把“下次启动时间”伪装成实际驾驶结束时间。

### Decision 4：逐条幂等写回，保留全部原始证据

每条候选在计算前后再次确认仍未闭环，再调用现有 `updateSummary`。不删除 crossing、binary、video_segments 或 MP4，不调用 Livetiming。单条失败用 `runCatching` 隔离并继续下一条；返回结构化恢复摘要供 Application 记录。

### Decision 5：Application 启动后台触发

`BlazePushApplication.onCreate()` 在 Koin 完成后捕获 `processStartedAt`，使用现有 `appScope` 调用恢复。恢复失败只写 FileLogger，不阻塞主界面和既有 Livetiming flush。

### Decision 6：进入 LAPS 子页时主动复查

恢复入口由进程级 coordinator 统一持有 `processStartedAt` cutoff 和互斥锁。Application 启动与 Records/LAPS 的实际进入信号都调用同一 coordinator；并发信号串行执行，先完成的恢复会令后续扫描自然得到零候选。LAPS 触发仍使用进程启动时的固定 cutoff，MUST NOT 改用点击 tab 的当前时刻，否则可能把本进程中尚在计时的 session 提前闭环。

触发同时观察外层 Pager 的 `settledPage == Records` 与 Records 内部 `selectedSegment == LAPS`：Pager 预组合相邻页面不会误触发；从其他底部页签回到已选中 LAPS 的 Records，或从 PERFORMANCE 切到 LAPS，都会重新检查。恢复后的 Room Flow 自动刷新列表，不要求重建页面。

## Risks / Trade-offs

- [恢复时 binary 仍可能截断或 header 未最终回写] → 圈数与最佳圈以事务写入的 crossing 为真相源；binary 失败只损失 top speed，不阻止恢复。
- [物理视频已被系统或用户清除但 DB 行仍在] → 本轮只保留关联，不宣称文件存在；详情消费继续按既有文件存在性处理。
- [异常 session 没有两次 StartFinish crossing] → 仍闭环为 0 圈并保留所有数据，避免每次启动无限重试；不删除现场证据。
- [异步恢复与新 session 竞争] → cutoff 限定只恢复进程启动前创建的行。
- [冷启动与 LAPS 入口同时触发] → 进程级 coordinator 互斥串行；写前二次确认和候选条件共同保证幂等。
- [旧脏时间戳污染 endTs] → 候选时间必须不晚于 recoveryNow，非法值被丢弃。

## Migration Plan

1. 以相同包名和签名覆盖安装，新版本首次冷启动扫描旧未闭环 session。
2. 现场 session 将由 8 次 StartFinish 配对恢复为 7 圈；视频段元数据保持原样。
3. 回滚旧 APK 不会逆转已写回的合法 summary，也不会删除任何原始文件。

## Open Questions

无。本轮明确不扩展到后台持续计时与孤立 MP4 扫描。
