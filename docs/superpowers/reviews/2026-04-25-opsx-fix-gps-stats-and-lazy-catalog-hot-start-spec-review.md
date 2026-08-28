# fix-gps-stats-and-lazy-catalog-hot-start specs review

## 0. TL;DR

`openspec validate fix-gps-stats-and-lazy-catalog-hot-start --strict` 已通过，但 specs 暂不建议进入 tasks。当前有 2 个 P1 契约缺口：A28 断言了当前代码不存在的 `_dataStats` 状态；A37 保留同步 `getTrack`，但没有明确禁止冷缓存 `getTrack(TFIC)` 触发 asset IO。

## 1. Findings

### Finding 1 — [P1] gps-runtime-stats spec 断言了不存在的 `_dataStats`

- **位置**：`openspec/changes/fix-gps-stats-and-lazy-catalog-hot-start/specs/gps-runtime-stats/spec.md:5-84`
- **问题**：spec 多处写 `GpsDataViewModel._dataStats` / `_dataStats.value.frequency` / `_dataStats.value`。但当前 `GpsDataViewModel` 只有 `_dataQuality: MutableStateFlow<DataQuality>`，`DataStats` 是 `updateDataStats` 内构造后传给 `DataQualityEvaluator.calculateQuality(data, stats)` 的局部值，并没有 `_dataStats` / `dataStats` StateFlow。照 spec 写 tasks 会逼实施方新增一个未在 proposal/design 中拍板的 public/internal 状态，或导致测试无法编译。
- **要求**：spec 必须二选一并写清楚：
  - 方案 A（推荐）：契约落在现有对外状态 `dataQuality.value.frequency` / `dataQuality.value.packetLoss` 上；测试通过 fake/spying `DataQualityEvaluator` 或直接观察 `dataQuality` 验证 `DataStats` 口径。
  - 方案 B：明确新增 `dataStats: StateFlow<DataStats>` 是本 change 的 public API 变更，并同步 proposal/design/Impact/测试。若不想扩 scope，不应选 B。

### Finding 2 — [P1] 同步 `getTrack` 仍可能留下冷启动 IO 入口

- **位置**：`openspec/changes/fix-gps-stats-and-lazy-catalog-hot-start/specs/track-catalog-hot-start/spec.md:3-21`
- **问题**：spec 要求 `getTrack(trackId)` 保持同步，理由写“单 track 查询无 IO 成本”。但当前 `ReplayAlignedTrackCatalog.getTrack(TFIC_TRACK_ID)` 会访问 `replayAlignedTrack by lazy`，首次调用同样触发 asset 读 + Gson parse。design D5 已提出冷缓存时 `getTrack` 返回 fallback、只由 suspend `getAllTracks` 初始化 replay cache；但 spec 没有把这条写成 Requirement/Scenario。这样 implementation 可以保留一个同步冷启动 IO 后门，却仍满足当前 spec 的 `getAllTracks` 契约。
- **要求**：在 `track-catalog-hot-start` 加明确契约：`ReplayAlignedTrackCatalog.getTrack(TFIC_TRACK_ID)` MUST NOT 在 cache 未初始化时调用 `ReplayTrackSource.loadReplayJson/loadTrackVbo`；冷缓存同步 `getTrack` 返回 fallback TFIC，只有 `getAllTracks()` 可触发 replay asset 加载。新增一个 Scenario 用 fake source counter 验证 cold `getTrack` 调用次数为 0。

### Finding 3 — [P2] IO 线程名断言过脆

- **位置**：`openspec/changes/fix-gps-stats-and-lazy-catalog-hot-start/specs/track-catalog-hot-start/spec.md:56-61`
- **问题**：Scenario 用线程名 `DefaultDispatcher-worker` 或包含 `IO` 来判定 asset 读取在 IO 线程池。线程名是 kotlinx.coroutines/JVM 实现细节，不适合作为长期硬契约；不同 Kotlin/coroutines 版本或测试 runner 下可能变。
- **要求**：改成更稳的断言：记录调用 `catalog.getAllTracks()` 前的 caller thread，并断言 `ReplayTrackSource.loadReplayJson()` 不在 caller/Main/test thread 执行；源码层另用 grep/AST 锁定 `withContext(Dispatchers.IO)`。如果后续 tasks 能注入 dispatcher，也可用 test dispatcher 做确定性断言。

## 2. Notes

- `openspec validate --strict` 通过只说明 delta 结构有效，不代表上述语义可核销。
- design.md D5 已经覆盖了 Finding 2 的正确方向，问题是 spec 还没同步成可核销 Requirement。

## 3. Verdict

暂不放行进入 tasks。修完 Finding 1 和 Finding 2 后可重提 mini review；Finding 3 可随同修订，但不单独阻塞。

## 4. Round 2 mini review

### 4.1 Finding closure

- Finding 1 closed：`gps-runtime-stats` 已把观察点从不存在的 `_dataStats` 改为现有 `dataQuality.value.frequency` / `dataQuality.value.packetLoss`，并新增“不引入 dataStats StateFlow API”场景，避免扩大 scope。
- Finding 2 closed：`track-catalog-hot-start` 已新增 `ReplayAlignedTrackCatalog.getTrack(trackId)` 冷缓存不触发 IO 的 Requirement，覆盖 TFIC 冷缓存、TFIC 热缓存、非 TFIC 冷缓存 3 个场景。
- Finding 3 closed：IO runtime 断言已从线程名字面匹配改为“不等于 caller / Main / TestScheduler 线程”，并用源码断言锁 `withContext(Dispatchers.IO)` 位置。

### 4.2 Validation

`openspec validate fix-gps-stats-and-lazy-catalog-hot-start --strict` PASS。

### 4.3 Verdict

Round 2 通过。可以进入 tasks 工件。
