# 延期立项设计 Memo：视频分片数据模型（video-segmentation-data-model）

> 🟡 **②a 已消化(2026-06-07,round `video-segment-schema`),②b/②c 待续**:
> §9 建议的 3 子 round 拆分被 user L0 采纳(2026-06-07),N=3 圈/段已拍板。
> ②a(schema)已落地:`video_segments` 统一表(与 multi-video-per-session memo 合并,
> 加 playable 字段)+ Room **v8→v9**(memo 写的 v6→v7 已过期)+ attach append + 双写
> 向后兼容(§4.1"旧字段保留"路径)+ 全段 cascade + 存量迁移。M4 的 room-testing 要求
> 改按工程先例(SQL 字符串自检 + 真机升级安装攒批,见 round design Decision 4)。
> **波及发现**:取消"重录删旧"与 video-storage-cleanup 主 spec MUST requirement 冲突,
> 已走 delta spec MODIFIED 废止(立项 ②b/②c 时注意该 capability 已更新)。
> **②c 已消化(2026-06-07,round `video-segment-playback-export`)**:§4.3 按 wallClock 选段
> 落地(Selector 纯函数 + loader 切子表 + 回放多段 playlist 段感知状态机 + 导出单段/跨段拒绝
> + playable 首播回写);§3.3 的"跨段拼播"由黑屏 ticker 渡 gap 实现(时间轴保真,非 concat),
> 跨段导出拼裁留 follow-up `video-export-cross-segment-concat`。孤儿判定语义切换与旧字段
> 废弃**未做**(双写期间旧语义正确,推 ②b 后评估)。
> ✅ **②b 已消化(2026-06-07,round `video-segment-recording-rotation`)——本 memo 三子 round 全闭**:
> §4.2 按圈轮换落地(N=3 + per-recording 闭包上下文修轮换并发污染 + Status 时长兜底 600s
> + gap 观测日志[M1/M6,真机读数决策双 Recorder]);M5 段边界落圈完成通知时刻。
> 残留 follow-up:wire-segment-lap-index(lapIndex 填充)/ 双 Recorder 乒乓(gap 超阈值时)/
> video-export-cross-segment-concat / 孤儿判定语义切换 + 旧字段废弃(双写稳定一个版本周期后)。

**延期决策时间**：2026-06-02
**延期决策原因**：`video-storage-management`（round A，轻量清理）讨论中，user 指出"全盘扫删非关联视频不好 + 一个 session 只关联一个 video 的覆盖模型下个阶段必改 + 视频要分片避免单文件过大"。一对多分片数据模型会 ripple 进回放/导出 + Room 迁移 = 大工程，不属 A 范围，延期独立立项。
**建议 round 名**：`video-segment-data-model`（可拆：schema 迁移 → 录制轮换分段 → 回放/导出按段索引 三个子 round）
**源 round**：`recording-params-config-screen` / `video-storage-management`（round A）

---

## 1. 现状

### 1.1 当前视频数据模型（单路径覆盖）

- **存储字段**：`TelemetrySessionEntity`（`core/data/.../entity/TelemetrySessionEntity.kt:32`）
  - `videoFilePath: String? = null`
  - `videoStartedAtWallClock: Long? = null`
  - schema v6（`session-video-metadata-persist` round，`AppDatabase.kt` MIGRATION_5_6，`ALTER TABLE telemetry_sessions ADD COLUMN videoFilePath/videoStartedAtWallClock`）
  - **一个 session 行 = 至多一个视频路径**。
- **录制产物**：`CameraRecordingEngine.startRecording`（`feature/test/.../recording/CameraRecordingEngine.kt`）写 `filesDir/video/<System.currentTimeMillis()>.mp4`，单文件一次录制从 Start 到 Finalize。
- **关联写入**：`TelemetryRepository.attachVideoToSession`（`core/data/.../repository/TelemetryRepository.kt:293`）= `UPDATE telemetry_sessions SET videoFilePath=?, videoStartedAtWallClock=? WHERE sessionId=?`。**覆盖语义**：同 session 重录直接覆盖字段，旧文件不删 → 孤儿。
- **删除**：`deleteSession`（同文件 `:251`）级联删 session + crossings + binary + **单个** videoFilePath 文件（路径白名单 `/telemetry/` `/video/`）。
- **消费（回放/导出）**：`LapPlaybackLoader`（`feature/test/.../export/LapPlaybackLoader.kt`）进屏读 session metadata（含 `videoFilePath` + `videoStartedAtWallClock`），`LapVideoPlaybackScreen` 用 ExoPlayer 播**单文件**、按圈时间轴（圈起点前 3s ~ 圈终点后 3s，见 lead-out）定位 playhead；`VideoExportPipeline` 按圈窗口裁**单文件**烧 overlay 导出。**全部假设一个 session 一个视频文件**。

### 1.2 单路径模型的两个固有问题

1. **覆盖产孤儿**：重录覆盖 `videoFilePath`，旧文件留在 `filesDir/video/` 无人引用、永不删（round A 的"重录即删旧"是单路径下的补丁）。
2. **装不下分片**：一个 session 多段视频无处可放（字段只有一个）。

---

## 2. 数据证据

### 2.1 单文件体量（来自 recording-params memo §2.3）

| 清晰度/帧率 | 比特率 | 1 小时 | 30 分钟赛事 |
|---|---|---|---|
| 1080p/30 | ~16 Mbps | ~7.2 GB | ~3.6 GB |
| 4K/30 | ~45 Mbps | ~20 GB | ~10 GB |
| 4K/60 | ~80 Mbps | ~36 GB | ~18 GB |

**单文件 7-36 GB**：① 手机文件系统/MediaStore 对超大单文件有风险；② 导出/分享/拷贝整文件慢；③ 中途任何失败整段报废。

### 2.2 长录单文件整损坏风险（Phase 2 实战）

Phase 2 踩坑：停圈退出录制 `Finalize ERROR_SOURCE_INACTIVE(code=4)` → **moov atom 未写完 → 整个 mp4 损坏不可播**。单文件越长，一次失败损失越大（整场赛事录像全丢）。**分片（≤N 圈/段）把爆炸半径限制在一段**：某段坏了，其余段仍可播。RaceChrono 即采用 ≤3 圈/段策略。

### 2.3 孤儿现状

无任何孤儿清理（round A 前）。每次重录 / 无 session 录制都留文件。round A 上线后单路径下孤儿基本不再产生，但**模型不变**。

---

## 3. 方案对比

### 3.1 数据模型

| 方案 | 描述 | 优点 | 缺点 |
|---|---|---|---|
| **A：维持单路径**（现状） | `session.videoFilePath` 一个 | 简单，回放/导出零改 | 装不下分片；覆盖产孤儿 |
| **B：一对多 video_segments 表**（推荐） | 新表 FK→session，一 session 多段 row | append 不覆盖（零孤儿）；分片就绪；按段删；DB 驱动清理（不扫地） | Room 迁移 + 回放/导出改读多段 |
| **C：JSON 数组存 session 字段** | `videoSegmentsJson: String` | 不建表 | 违反 A56（拒绝 JSON 存结构化）；查询/级联难 |

**推荐 B**。C 撞 A56 红线。

### 3.2 分段触发策略

| 策略 | 描述 | 优点 | 缺点 |
|---|---|---|---|
| **按圈数（≤N 圈/段，推荐）** | 每过 N 圈终点线切新段（RaceChrono ≤3） | 段边界=圈边界，干净；回放按圈索引天然对齐 | 需录制引擎听圈 crossing 事件 |
| 按时长（每 X 分钟） | 定时切段 | 实现简单 | 段边界落在圈中间，回放跨段拼接麻烦 |
| 按文件大小（每 X GB） | 达阈值切段 | 控制单文件体量 | 边界不可预测，跨段拼接最麻烦 |

**推荐按圈数**（段边界落在圈终点线，回放/导出按圈选段最干净）。可叠加"硬上限时长/大小"兜底防单圈异常长。

### 3.3 回放/导出多段消费

| 方案 | 描述 | 优点 | 缺点 |
|---|---|---|---|
| **按 wallClock 索引选段（推荐）** | 每段记 `[startWallClock, endWallClock]`，回放/导出按目标圈窗口选覆盖的段 | 不预拼接，省存储；按圈回放只取相关段 | 跨段圈（圈窗口跨两段）需多段拼播 |
| 导出时 concat | 导出前 MediaMuxer 拼成整文件再裁 | 下游逻辑不变 | 拼接耗时 + 临时大文件 |

**推荐按 wallClock 索引**；跨段圈用 ExoPlayer ConcatenatingMediaSource 或导出期局部 concat。

---

## 4. 推荐方案 + 分析

### 4.1 数据模型

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
    val segmentIndex: Int,            // 段序（0 基，录制顺序）
    val filePath: String,             // filesDir/video/<sessionId>_<segIdx>_<ts>.mp4
    val startWallClock: Long,         // 本段首帧 wallClock（与样本同时钟域）
    val endWallClock: Long,           // 本段末帧 wallClock
    val durationMs: Long,
    val startLapIndex: Int? = null,   // 本段起始圈（按圈分段时）
    val endLapIndex: Int? = null,
)
```

`session.videoFilePath`/`videoStartedAtWallClock` **保留**（向后兼容 + 单段快路径），或迁移期并存、稳定后废弃（迁移决策见 §5）。

### 4.2 录制轮换（按圈分段）

`CameraRecordingEngine` 监听圈 crossing 事件（来自 `TestSessionViewModel` lap session）：每过 N 圈终点 → `Recorder` stop 当前段 → 立即 start 新段（同 `Recorder` 复用，最小化间隙）→ 每段 Finalize 写一行 `video_segments`。wallClock 锚点逻辑（VideoRecordEvent.Start 取 System.currentTimeMillis）每段独立取。**段间隙**（stop→start 之间丢帧）需评估（CameraX 连续分段能力，可能需双 Recorder 乒乓或 PendingRecording 预备）。

### 4.3 回放/导出按段索引

`LapPlaybackLoader` 改：给定目标圈窗口 `[lapStart-3s, lapEnd+3s]`（wallClock）→ 查 `video_segments` 选 `startWallClock <= 窗口end && endWallClock >= 窗口start` 的段（可能 1-2 段）→ 单段直播；跨段用 ExoPlayer 拼接。导出同理（裁相关段 + 必要时局部 concat）。

---

## 5. 实施约束（MUST 条款）

- **M1 段边界 wallClock 连续**：相邻段 `endWallClock(i) ≈ startWallClock(i+1)`，间隙 MUST 记录（用于回放跨段无缝/标注丢帧）。
- **M2 级联删全段**：`deleteSession` MUST 删该 session 所有 `video_segments` 文件 + row（ForeignKey CASCADE 删 row，文件需显式删，复用白名单路径校验）。
- **M3 round A 钩子平移**：A 的"重录即删旧 / 成绩页删视频 / 无 session 删"在分片模型下变成**按段**：删某段 = 删 row + 文件；"删整 session 视频" = 删全段。
- **M4 Room 迁移 v6→v7**：建 `video_segments` 表；存量 `videoFilePath != null` 的 session MUST 迁成一行 segment（segmentIndex=0，startWallClock=videoStartedAtWallClock，endWallClock 用文件时长推算或留 null 容忍）。迁移 MUST 有 room-testing 验证（参 v3 盲点 #3 不可执行测试）。
- **M5 按圈分段边界落圈终点**：段切换 MUST 在圈 crossing 时刻，不在圈中间（保证按圈回放选段干净）。硬上限时长/大小兜底防单圈异常。
- **M6 段间隙最小化**：stop→start 切段丢帧 MUST 评估真机实测；若 CameraX 单 Recorder 切段间隙过大，评估双 Recorder 乒乓。

## 6. 单元测试覆盖

- **段选择纯函数**：`selectSegmentsForWindow(segments, windowStart, windowEnd): List<VideoSegment>`——给定段列表 + 圈窗口，返回覆盖段（含跨段返回 2 段、窗口在单段内返回 1 段、窗口无覆盖返回空 3 case）。
- **Room 迁移 roundtrip**：room-testing 注入 v6 数据（含 videoFilePath）→ migrate v7 → 断言生成 segmentIndex=0 行 + 字段对齐。
- **级联删**：删 session → 断言 video_segments row 全删。
- **段边界 wallClock 连续性**：构造多段断言无重叠/间隙记录正确。
- 真机：实际切段丢帧、跨段回放无缝、导出多段拼接正确。

## 7. 与 round A（轻量清理）的协同关系

round A 在**单路径模型**上做：① 重录即删旧 ② 成绩页删视频 ③ 无 session 删。本 round 落地后：
- A 的三个钩子**平移成按段**（删段 = row + 文件；删 session 视频 = 全段）。
- A 不需推翻：A 解决的是"当前单路径下不堆垃圾 + 能手删"，本 round 把模型升级到一对多，A 的清理语义自然继承。
- **迁移衔接**：本 round v6→v7 迁移把 A 时代的单 videoFilePath 转成 segmentIndex=0 行，A 删过的（videoFilePath=null）迁移后无 segment，一致。

## 8. 不并入 round A 的理由

1. **ripple 大**：一对多改回放（`LapPlaybackLoader`/`LapVideoPlaybackScreen` 多段索引/拼播）+ 导出（`VideoExportPipeline` 多段裁拼）+ Room 迁移 + 录制引擎轮换 = 跨 recording/data/playback/export/UI 5 模块，large。
2. **Room schema migration**：属"强制升级 medium 流程的 5 例外场景"，需独立谨慎立项。
3. **A 的价值不依赖它**：A 小而安全先解 user 当前痛点（垃圾 + 单删），本 round 是 user 明确的"下个阶段"。
4. **录制轮换有真机不确定性**：段间隙丢帧需真机实测 + 可能双 Recorder 方案，不宜混入 A。

## 9. 立项节奏估算

**建议拆 3 个子 round（或一个 large round 内分 phase）**：

| 子 round | 内容 | 复杂度 | 估算 |
|---|---|---|---|
| `video-segment-schema` | `video_segments` 表 + Room v6→v7 迁移 + DAO + repository（attach 改 append 写段）+ 迁移测试 | medium | 0.7 天 |
| `video-segment-recording-rotation` | 录制引擎按圈轮换分段 + 段间隙真机评估 | medium-large | 1.2 天 |
| `video-segment-playback-export` | 回放/导出按 wallClock 选段 + 跨段拼播/拼裁 | medium | 1 天 |

**前置**：round A 归档 + Phase 1 视频管线稳定。**顺序**：schema → recording-rotation → playback-export（schema 先行，录制与回放可部分并行但回放依赖 schema）。

**复杂度判定**：整体 **large/architectural**（公共视频数据契约改造 + 跨 5 模块 ripple + Room migration）。按工程规则走 v3 标准（若届时非 road-test-first 模式）。
