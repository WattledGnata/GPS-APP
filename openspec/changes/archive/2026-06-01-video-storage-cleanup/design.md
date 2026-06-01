## Context

见 proposal Baseline。`filesDir/video/<ts>.mp4` 单文件录制；`session.videoFilePath` 单路径覆盖；`deleteSession` 含视频删除 + 白名单（`/telemetry/` `/video/`）逻辑（`TelemetryRepository.kt:264-280`）。`LapSessionDetailScreen` 已注入 `telemetryRepository`（`:77`）+ 有 `hasVideo`（`:212`），`session` 经 `LaunchedEffect(sessionId)` 加载（`:90`）。road-test-first 模式（CC 自审 + FileLogger + 真机攒批）。

## Goals / Non-Goals

**Goals:**
- 录制视频不再无限堆积（重录/无 session 孤儿在产生点即删）。
- 成绩页可单删视频、保留圈速成绩。
- 删除安全：绝不误删非视频/在录文件。

**Non-Goals:**
- 全盘目录扫描清理（user 明确否决）。
- Room schema 改动 / 视频分片一对多模型（deferred memo）。
- 按时长丢弃超短废片（user 选"都保留"，废片靠手动删 / 无 session 自动删覆盖）。

## Decisions

### Decision 1：生命周期驱动删除，不做全盘目录扫描

只在明确时刻删文件：重录覆盖、删 session、手动删视频、无 session 录制完成。

- **Alternative A（选中）·生命周期驱动**：DB/录制流程是唯一删除触发源，删的都是"已知该删"的文件，安全可推理。
- **Alternative B·全盘扫描删非引用文件**：user 明确否决——DB 不一致即静默误删；且未来分片有"轮换中未入库"中间文件会被当孤儿误删（撞 deferred memo §3 安全考量）。拒绝。

**Rationale**：安全 + 不与分片未来打架。

### Decision 2：重录覆盖前删旧文件（attach 内）

`attachVideoToSession` 先查旧 `videoFilePath`，若非空且 ≠ 新路径 → 删旧文件，再 UPDATE。

- **Alternative A（选中）·attach 删旧**：从源头断"重录孤儿"，无需事后扫。
- **Alternative B·留着等扫**：撞 Decision 1 拒绝的扫描。拒绝。

### Decision 3：手动删视频 = 置空字段 + 删文件（保留成绩）

`deleteSessionVideo(sessionId)`：查 entity → 删视频文件（helper）→ DAO `clearVideo`（`videoFilePath`/`videoStartedAtWallClock` 置 NULL）。圈速 / crossing / binary 不动。

- **Alternative A（选中）·置空+删文件**：成绩（圈数/best/topSpeed/crossing）全留，只去视频。
- **Alternative B·复用 deleteSession**：会连成绩一起删，不满足"保留成绩"。拒绝。

### Decision 4：无 session 录制 Finalize 时删孤儿

`handleVideoRecordEvent` Finalize OK 分支：`sessionId == null` → 删该文件（不写库、UI 不可达 = 纯垃圾）。

- **Alternative A（选中）·Finalize 删**：在产生点即清，最干净。
- **Alternative B·留着**：UI 永远摸不到 → 永久垃圾。拒绝。

### Decision 5：抽 `deleteVideoFileIfPresent(path)` helper

白名单校验（`/telemetry/` `/video/`）+ exists 检查 + delete + 日志，`deleteSession`/`attach`/`deleteSessionVideo` 复用，避免三处白名单逻辑漂移。

- **Alternative A（选中）·抽 helper**：DRY，白名单单点维护。
- **Alternative B·三处各写**：逻辑漂移风险（某处漏白名单 = 路径穿越）。拒绝。

### Decision 6：不改 Room schema（保留单路径模型）

单 `videoFilePath` 模型维持；分片一对多延期（deferred memo）。A 的删除钩子届时平移成按段删。

- **Alternative A（选中）·不改 schema**：A 小而安全，不 ripple 回放/导出。
- **Alternative B·现在上一对多表**：large，ripple 回放/导出 + migration（见 memo §8）。本 round 拒绝。

## Risks / Trade-offs

- **[Risk 1] 误删非视频/在录文件** → 所有删除走 `deleteVideoFileIfPresent` 白名单（路径含 `/video/` 或 `/telemetry/`）；attach 删的是**旧** path（非新录入的）；无 session 删的是**本次刚 Finalize** 的文件（已落盘完成，非在录）。绝不在"正在录制"时删任何文件。
- **[Risk 2] 删旧文件失败（占用/权限）** → `File.delete()` 失败不抛、埋 `FileLogger.e`，不阻塞 attach/录制主流程（旧文件最坏仍是孤儿，但不崩）。
- **[Risk 3] 手动删后 UI 不刷新** → `LapSessionDetailScreen` 删后 bump refresh key 重载 session → `videoFilePath=null` → hasVideo=false → 回放入口消失，成绩仍在。
- **[Trade-off] 单路径模型下"重录"仍只保留最后一段** → 本 round 接受（分片才解，deferred memo）。A 只保证不堆孤儿。

## Migration Plan

无 schema migration。存量孤儿（A 上线前已堆积的）本 round **不主动清**（不扫地）——只对 A 上线后的新录制生效；存量孤儿可由 user 删对应 session 时连带清，或留待分片阶段做一次性 reconcile（memo 提及）。Rollback：移除删除调用即回退到"只攒不删"，无数据损坏风险。

## Open Questions

无悬而未决设计问题。存量孤儿是否做一次性清理 = 非阻塞，留 deferred（A 不扫地是 user 明确决策）。
