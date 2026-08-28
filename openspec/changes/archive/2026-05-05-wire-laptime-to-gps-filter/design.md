## Context

本 round 起源于 `add-lap-session-phase1` round §8 真机验证：T40 simulator 单次播放 `tianfu_track_replay_5hz.json` → 华为 `8KE0219522008434` lap_live，2742 reject 中唯一一帧真 invalidating（`WrongDirection`）来自单帧 GPS 位置 outlier，prev→cur 矢量与 s1 gate 反向 `directionScore = -1.157`。

baseline 数据流割裂：
- **加减速通道**：`TestSessionViewModel.kt:342-345` 已用 `gpsDataFilter.process(gpsData)` 输出 → `updatePreTriggerBuffer / updateLaunchStatus / processFilteredData`
- **圈速通道**：`TestSessionViewModel.kt:347` 直喂 raw `gpsData` 到 `bridgeGpsToLapTiming` → `lapTimingEngine.processSample`，**完全绕过 9 帧滚动 median + 物理约束 + 位置-速度一致性 + 失联重置守卫**

核心约束（既有 baseline）：
- `core/domain/.../GpsDataFilter.kt:12-145` 是接收端主滤波器，9 帧滑动窗口中位数 + bearing 循环均值 + 加速度物理约束 + 位置-速度一致性 + dt > 200ms 重置
- `FilteredGpsData` 数据类（`GpsDataFilter.kt:374-388`）含 `latitude / longitude / speed / bearing / timestamp / isAnomaly / isPositionAnomaly` 等字段，**filter 不滤 timestamp**
- `TestSessionViewModel.bridgeGpsToLapTiming(gpsData: GpsData)` 当前签名（line 766）；函数体内构造 `currentSample = gpsData.toLapGpsSample()`（line 781），并在 telemetry binary 写入分支（line 822-861）以 `gpsData.latitude/longitude/speed/bearing` 为 sample 字段（A round `fix-lap-binary-ts-hygiene` 已合回，`tsDeltaMs` anchor 与 `header.startTs` 同源）
- `LapLiveStateDeriver.LAP_INVALIDATED_DEBOUNCE_MIN_COUNT = 3`（`LapLiveStateDeriver.kt:60`）+ `LAP_INVALIDATED_DEBOUNCE_WINDOW_MS = 1_000L`（line 59）：当前在 1 秒窗口内累计 ≥3 个 invalidating event 才弹 banner，line 58 注释明确"filter 接通后阈值可降至 1"

**与并行 round 边界**（看板 §5 W4 行 + §6 共享文件登记）：
- A. `fix-lap-binary-ts-hygiene` 已 archived（archive/2026-05-02，commit `599562e`）→ 解锁 `repository.activeSessionStartTs` property + bridge 内 anchor 修正块；本 round **不动 anchor 公式**，只动入参
- W1 / W2 / W3 与本 round 函数级 0 交叉（W1 改 `core/data/.../TelemetryRepository.kt` 加 reader 方法 + 新 entity；W2 改 `feature/test/.../ui/components/` 新建 chart；W3 改 `core/domain/.../usecase/LapAlignment.kt` 新建 pure function；本 round 改 `bridgeGpsToLapTiming` 入参 + `LapLiveStateDeriver` 常量）
- 本 round 启动 worktree `.worktrees/wire-laptime-to-gps-filter` from HEAD `e2a42a1`，主区工件 source-of-truth

**详细设计源**：`docs/design/laptime-gps-filter-integration-deferred.md`（9 章完整 memo，本 design 是 memo 的 spec-driven 化提炼，不重复推导，决策 1-2 直接 cite memo §3 与 §4）。

## Goals / Non-Goals

**Goals:**
1. 圈速通道接通 `GpsDataFilter`，让单帧 GPS 位置 outlier 经 9 帧 median 后不再让 detector 看到反向矢量
2. 在 filter 接通的契约里写死"不 skip 帧 + 不滤时间戳"两条 normative 约束，防止后续 refactor 误改
3. 解锁 `LAP_INVALIDATED_DEBOUNCE_MIN_COUNT` 阈值降回 1，单次真 invalidating 即弹 banner，恢复"原本应该有的"实时反馈语义
4. 5 cases 单测端到端覆盖 filter→detector 行为契约（jitter outlier / lap duration / anomaly 不丢点 / bearing wrap / warmup）

**Non-Goals:**
1. **不**改 `GpsDataFilter` 内部算法（窗口大小 9 / 物理约束阈值 2.5G / 位置-速度比阈值 3.0 等参数完全不动；只在调用侧接通）
2. **不**改协议（RaceChrono BLE / replay JSON / Room schema 全保持）
3. **不**改加减速通道行为（`updatePreTriggerBuffer / processFilteredData` 不动）
4. **不**做 filter 接通后真机长录制 lap duration 与 raw 对比的精度回归（设计 memo §4.2 数学论证 + 5 cases 单测已覆盖；user 可在 review 后拍板是否额外真机 gate）
5. **不**重构 `bridgeGpsToLapTiming` 内部三段式守卫（A38 + A34 死码清理 + A18 verbose 日志降级等历史决策原样保留）
6. **不**改 `LapLiveStateDeriver` 其他常量（`LAP_INVALIDATED_DISPLAY_WINDOW_MS = 5000L` / `LAP_INVALIDATED_DEBOUNCE_WINDOW_MS = 1000L` / `MIN_SATELLITES_FOR_FIX = 6` 等保持）

## Decisions

### Decision 1：方案 2「仅替换位置字段」vs 方案 1「skip 整帧」

**选择**：方案 2 ——「仅替换 `lat/lon/speed/bearing`，**不 skip 任何帧**」（含 `isAnomaly = true` 帧）。

**Alternatives considered**：

| 方案 | 描述 | 拒绝理由 |
|---|---|---|
| 方案 1：skip 整帧 | `if (filtered.isAnomaly \|\| filtered.isPositionAnomaly) return` | (a) detector 该帧丢点；(b) 高速段 200km/h × 200ms ≈ 11m 真空；(c) `crossingProgress` 插值精度退化（线段跨度 80ms 而非 40ms）；(d) 连续异常时心跳事件中断，UI lapLiveState 几何信号断流 |
| **方案 2：替换字段（选）** | `cleaned = gpsData.copy(lat=filtered.lat, ...)` 喂 detector | jitter 帧位置被 median 拉回窗口中位数 → detector 看到稳定方向矢量；不丢点；timestamp 保 raw 让 `crossingProgress = lerp(prev_ts, cur_ts, t)` 用真实 GPS 帧时间，圈时插值精度不受影响 |
| 方案 3：双写（detector 喂 raw + filter 接 secondary 报警） | 加新字段标记"filter 认为这帧 anomaly"，detector 仍喂 raw | 没有解决根因（detector 仍看 raw outlier，仍误判 WrongDirection）；只增加观测维度 |
| 方案 4：detector 内部加 outlier 拒绝 | `LapTimingEngine.processSample` 自己加 9 帧 median | 重复 `GpsDataFilter` 算法 + detector 与加减速通道滤波参数会漂移；违反 SSOT |

**Rationale**：方案 2 的关键洞察来自设计 memo §4.2：`lap_duration = T_闭圈 - T_开圈`，filter ~160ms 滞后是窗口中点滞后（与车速无关），开/闭圈两次过线滞后量相等，**相减抵消**。方案 1 反而损伤精度（gap 处插值失真）。

### Decision 2：替换字段精确范围（`lat/lon/speed/bearing` only）

**选择**：仅替换 `latitude / longitude / speed / bearing` 四个字段；保留 raw `timestamp / isTimeSynced / altitude / satelliteCount / hdop / vdop / fixQuality` 等所有元信息字段（参 `core/domain/.../model/GpsData.kt` 实际字段定义）。

**Alternatives considered**：

| 方案 | 描述 | 拒绝理由 |
|---|---|---|
| **A：仅 4 字段（选）** | `gpsData.copy(latitude=, longitude=, speed=, bearing=)` | timestamp 保 raw → `crossingProgress = lerp(prev_ts, cur_ts, t)` 精度不变；isTimeSynced 保 raw → bridge 第一段守卫"未同步帧整帧 skip"语义不变；hdop/vdop/satelliteCount/fixQuality 保 raw → confidence 计算保留原信号 |
| B：用整个 `FilteredGpsData` 转回 GpsData | `FilteredGpsData.toGpsData()` | filter 计算的 `confidence` 不是 GpsData 字段，转换有损；filter 不读 hdop/vdop/satelliteCount/fixQuality，这些字段会在转换中丢失或退化为 default |
| C：连 timestamp 一起替换为 filter median 时间 | `cleaned.timestamp = filtered.timestamp` | filter 不滤 timestamp（`FilteredGpsData.timestamp = raw.timestamp` 直传），技术上等价但语义混乱；万一未来 filter 改成滤时间戳本 round 会无声破坏 detector 插值精度 |

**Rationale**：filter 的语义就是「位置稳定化」，时间字段是 detector 的精度根基（`crossingProgress` 插值依赖），守恒才安全。明确写死 4 字段而不是「除某字段外全替换」可以让 review 一眼看清楚改动 surface。

### Decision 3：保持 `bridgeGpsToLapTiming` 函数签名不变（在 collect block 构造 cleaned 后再传入）

**选择**：在 `viewModelScope.launch` 内 collect block 复用既有 `filteredData` 变量构造 `cleaned`，再以 `cleaned` 传入 `bridgeGpsToLapTiming(cleaned)`。函数签名 `private suspend fun bridgeGpsToLapTiming(gpsData: GpsData)` **保持不变**。

**Alternatives considered**：

| 方案 | 描述 | 拒绝理由 |
|---|---|---|
| **A：collect 内构造 cleaned（选）** | `val cleaned = gpsData.copy(...); bridgeGpsToLapTiming(cleaned)` | 函数签名稳定，bridge 内部完全不知道 filter 接通；diff surface 局限在 collect block 几行 |
| B：bridge 加第二参数 `bridgeGpsToLapTiming(raw, filtered)` | 函数内自行 copy | 改函数签名 + 更多调用方 + 测试 fixture 都要改；本 round 唯一调用方就是 collect block，加参数是过度设计 |
| C：bridge 内重新调用 `gpsDataFilter.process(gpsData)` | 函数内拿 filter 实例（已 inject） | **同一帧 process 两次会破坏 filter 9 帧滚动窗口**（重复 add 让 median 偏移），是 critical bug；MUST NOT |
| D：collect block 用 if 分支，TestMode == LapDebug 才走 cleaned | 不影响其他 mode | TestMode 切换在 ViewModel state 内频繁改，collect block 加分支让数据流难读；bridge 自己有 mode 守卫（line 768 `if (_currentMode.value != TestMode.LapDebug || !isLapRecording) return`），冗余 |

**Rationale**：函数签名稳定让本 round diff surface 最小（< 15 行 in collect block + 0 行 in bridge function body）。如果未来需要让 bridge 知道 filter 元信息（如 isAnomaly 用于日志），再考虑加参数；本 round 不做。

### Decision 4：telemetry binary 写 cleaned 位置（与 detector 看到的轨迹一致）

**选择**：`bridgeGpsToLapTiming` line 844-852 内 `TelemetrySample` 字段（`lat / lon / speedKmh / bearingDeg`）随 cleaned 入参一起写 cleaned 位置；`tsDeltaMs` 仍为 `System.currentTimeMillis() - sessionStartTs`，与 A round anchor 同源契约 0 冲突。

**Alternatives considered**：

| 方案 | 描述 | 拒绝理由 |
|---|---|---|
| **A：binary 写 cleaned（选）** | sample 字段与 detector 看到的一致 | 回放重建 prev→cur 矢量复现 detector 判定（diff 调试关键能力）；binary 是真实"detector 输入流"快照，不是"GPS 真实采集流"快照 |
| B：binary 写 raw，detector 喂 cleaned | 两个数据流分叉 | 回放时无法复现 detector 判定（看 raw binary 的人看到 outlier 帧但 detector 当时拒绝它，调试时一头雾水）；引入两套语义 |
| C：binary 同时写 raw + cleaned 双字段 | sample 字段加倍 | 改 binary header / sample 编码（违反"不改协议"）；存储 2 倍体量；过度设计 |

**Rationale**：binary 的语义是"detector 输入流的可回放快照"，应跟数据流走。如果未来需要"GPS 真实采集流"做 filter 算法对比，再单独立项加 raw binary 通道（参考 deferred memo `speed-curve-real-data-persistence-deferred.md` 的双轨思路），本 round 不预留。

**回滚成本**（不可逆决策透明声明）：
- telemetry binary 持久化存档跨 round 跨版本。本 round 之后写入的 binary 都是 cleaned 字段，**历史 binary 无法回溯 raw 位置**。如未来立项 `verify-laptime-filter-precision-real-device`（tasks §10 提到）或类似需求需要 raw 路径做精度 baseline 对比，必须重新真机长录。
- 替代追溯手段：(a) `adb logcat` dump 覆盖 BLE 链路 raw 数据（FileLogger 在 verbose 级别打印 `bridgeGpsToLapTiming` 入参 lat/lon，覆盖期内可重建 raw 流），(b) replay JSON（`feature/test/src/main/assets/replay/*.json`）作为已知 raw 轨迹的可重放基准，(c) simulator 端 NMEA log 在端到端测试中保留 raw 流。
- 本 round 接受此成本：lap detector 判定是当前最高频 debug 需求；以"看 binary 复现 detector 判定"为主要诉求，raw 真实采集流不属于 hot path。

### Decision 5：去抖阈值降至 1 在本 round 同步落地（不分独立 round）

**选择**：`LAP_INVALIDATED_DEBOUNCE_MIN_COUNT` 由 3 降至 1，与 filter 接通**同一 commit 落地**。

**Alternatives considered**：

| 方案 | 描述 | 拒绝理由 |
|---|---|---|
| **A：本 round 同步降（选）** | 一个 commit 里 filter 接通 + 阈值降 | filter 接通即"jitter 不再产生 invalidating event"，阈值仍为 3 是死代码兜底；同步降可让 review 一次性看清"接通 + 阈值"协同关系；line 58 注释里"filter 接通后阈值可降至 1"的承诺一次兑现 |
| B：分两阶段（先接通，观察一段再降） | 真机灰度 | 没有灰度机制（debug 阶段单机）；多一次 round 反而增加 review 成本 |
| C：保留 3 不降 | filter 接通就是双重保险 | 真反向冲线时本应单帧弹 banner，保留阈值 3 让 banner 至少延迟 3 帧（120ms）才弹，损害实时反馈 |

**Rationale**：filter 接通从根因消除 jitter 后，去抖阈值的"防误闪"目的已被根因消除。保留 3 反而把"真实 1 帧反向"硬性等到 3 帧才弹，破坏 banner 的"即时性"语义。同 commit 落地是契约对齐。

### Decision 6：单元测试位置 —— `feature/test/src/test/.../usecase/LapFilterIntegrationTest.kt`（端到端纯函数测试）

**选择**：在 `feature/test/src/test/java/com/blazepush/feature/test/usecase/` 新建 `LapFilterIntegrationTest.kt`，端到端调 `GpsDataFilter` + `LapTimingEngine` 两个纯类，**不依赖 ViewModel / Robolectric / Android Context**。

**Alternatives considered**：

| 方案 | 描述 | 拒绝理由 |
|---|---|---|
| **A：feature/test usecase 端到端（选）** | new file 测 filter→detector 链 | 两类都是纯函数，单测无 Android 依赖；可以构造 GpsData 序列直接断言 detector 输出 accepted/reason；测 5 case 直接对应 spec scenario |
| B：核心放 `core/domain/.../GpsDataFilter` 单测，detector 部分用 mock | 拆两个文件 | filter 已有自己的单测（`GpsDataFilterTest.kt`，假定存在）；本 round 关键是"两类协同行为"不是各自单元行为；mock 加重负担 |
| C：测 ViewModel 内 collect block | `TestSessionViewModelTest.kt` 加 case | ViewModel 测试需要 Robolectric / mock 大量 collaborator（`telemetryRepository / gpsDataViewModel / trackCatalog / lapTimingEngine`）；信噪比低 |
| D：真机 instrumentation test | `androidTest/` | filter 是纯算法，instrumentation 是 overkill；CI 跑不动；与 user "不强制真机"决策冲突 |

**Rationale**：filter + detector 都是纯函数，端到端单测最干净。新文件名 `LapFilterIntegrationTest` 明确说明 scope（filter 与 lap timing 的集成行为，不是 filter 单元 / detector 单元）。

### Decision 7：filter warmup 期（前 9 帧未填满）行为契约 —— "fallback 到 raw"由 filter 本身保证

**选择**：依赖 `GpsDataFilter.process` 在窗口未填满时的既有行为（line 50-62 协议未同步守卫早退 / 窗口数量不足时输出 raw 速度等），**不在 ViewModel 层加额外 warmup 守卫**。spec 用反例 scenario 锁死「session 起点前 9 帧 detector 接收到的 cleaned 帧位置数据 MUST 与 raw 一致或 filter 内部 fallback 后等价」。

**Alternatives considered**：

| 方案 | 描述 | 拒绝理由 |
|---|---|---|
| **A：依赖 filter 内置（选）** | filter `process` 已有 fallback | filter 内部已规定窗口未满时输出语义；ViewModel 不需要重复守卫 |
| B：ViewModel 加帧计数器，前 9 帧喂 raw，第 10 帧起喂 cleaned | 显式 warmup gate | 重复 filter 内部计数；ViewModel 与 filter 状态机割裂；filter 的"协议未同步早退"逻辑（line 50）会让计数语义不准 |
| C：filter warmup 期 detector 也走"首样本"分支（previousSample = null） | 强制 warmup | 改 detector 内部状态语义；session 起点 9 帧每帧都"首样本"会让 detector 完全不工作 9 帧 |

**Rationale**：filter 已经是 baseline 黑盒，warmup 行为是 filter 的合约责任；ViewModel 接通后不应越权重复守卫。spec 反例锁住"warmup 期不出现 NPE / 异常"即可。

### Decision 8：`isAnomaly == true` 帧的处理 —— 完全透明喂 detector

**选择**：filter 输出 `isAnomaly = true` 或 `isPositionAnomaly = true` 时，cleaned 帧仍喂 detector（与 Decision 1 方案 2 配套），**不写日志降噪**。

**Alternatives considered**：

| 方案 | 描述 | 拒绝理由 |
|---|---|---|
| **A：完全透明（选）** | bridge 不知道 anomaly 标记 | 与 Decision 3「函数签名不变」一致；bridge 内部不引入 filter 元信息 |
| B：bridge 接 anomaly 标记 + log warning | 加日志 | 改函数签名（违反 Decision 3）；日志频率高（A18 战役已经把 bridge 日志降级到 verbose），加 anomaly warn 反而恶化 |
| C：anomaly 帧从 binary 中标记（额外字段） | 改协议 | 违反"不改协议"边界；过度设计 |

**Rationale**：anomaly 帧的位置已被 median 修正（这正是 filter 的工作），detector 看到的是稳定矢量，不需要额外通知。日志监控由 filter 单元测试 + 真机长录回放分析负责。

## Risks / Trade-offs

### Risk 1：filter ~160ms 滞后让"贴近 startfinish gate 的弯道"圈时偏差

**风险**：开/闭圈滞后量相等的前提是"过线点速度变化平稳"。如果 startfinish gate 设计在弯道上（比如低速进出弯），开圈时刻速度低 → filter 滞后偏大；闭圈时刻速度高 → filter 滞后偏小；相减不抵消。

**Mitigation**：
- TFIC LPCC 预置赛道 startfinish gate 在直线段（设计 memo §4.2 假设成立）
- spec R1 scenario 2 仅锁直线段过线 lap duration 差 < 50ms；**弯道过线偏差边界不在本 round 单测覆盖范围**（spec 不写 dead scenario，避免 v3 高频盲点 #13"卸责借口"）。如未来真机暴露弯道场景偏差 > 50ms，独立立项 follow-up round `verify-laptime-filter-precision-real-device`（与 §10 follow-up backlog 对齐）
- 长期：未来真机长录可以加 raw vs filtered 双路 lap duration 对比（不在本 round scope）

### Risk 2：filter 9 帧 median 在直线高速段对真"反向冲线"延迟检测

**风险**：真反向冲线持续多帧 prev→cur 反向矢量，filter median 也会跟随，但前 9 帧 reverse 触发的 invalidating event 可能延迟 ~160ms 才被 detector 看到。

**Mitigation**：
- 设计 memo §1 量化：filter 滞后 = 窗口中点位置滞后 = 4×40ms（在 25Hz 下） = 160ms
- 真反向冲线持续多帧 → detector 仍会在第 5 帧 median 反向后产生 invalidating event → `LAP_INVALIDATED_DEBOUNCE_MIN_COUNT = 1` 让单 event 即弹 banner
- 总 banner 延迟 ≤ 200ms（filter 滞后 + 1 帧 detector）；真反向冲线场景人眼观察 200ms 延迟不可感知
- spec scenario 锁死「连续 5 帧真反向冲线 → 在第 5-7 帧（含 filter 滞后）触发 LAP_INVALIDATED banner」

### Risk 3：anomaly 帧不 skip 让 detector 看到 filter 修正后的"假位置"

**风险**：filter 把 outlier 位置 median 拉回窗口中位数，detector 看到的是"虚构位置"，圈时 binary 里位置不再是真实采集点。

**Mitigation**：
- Decision 4 明确 binary 写 cleaned，与 detector 看到的一致 → 回放 binary 重建 prev→cur 矢量复现 detector 判定（这是 feature 不是 bug）
- 真实 GPS 采集流如未来需要，独立立项加 raw binary 通道（不阻塞本 round）
- spec scenario 锁死「连续 5 帧 isAnomaly = true 时 detector 仍收 5 帧样本（无 ts gap）」

### Risk 4：bearing 跨 0°/360° 边界 filter 处理不当导致 detector 误判

**风险**：bearing 359° → 1° 的循环跳变，naive median 会输出 180° 而非 0°，detector 看到瞬间反向。

**Mitigation**：
- baseline `GpsDataFilter` 的 bearing 滤波已经使用循环均值（`atan2(mean(sin), mean(cos))`，参考 `core/domain/.../GpsDataFilter.kt` 既有实现）
- spec scenario 锁死「构造跨 0°/360° 边界 bearing 序列，cleaned 输出在期望象限内（不出现 180° 中点伪影）」
- filter 已有自己的单测覆盖 bearing wrap-around；本 round 增加端到端 case 锁住 detector 看到的 cleaned 输出在合理范围

### Risk 5：阈值降至 1 让真反向冲线 banner 更激进，与 user 历史预期不符

**风险**：user 在 add-lap-session-phase1 真机时验收过"3 阈值不闪 banner"，本 round 降到 1 可能让 user 在某些场景看到 banner 频率上升。**补充：filter 内置 `dt > 200ms 重置`（`GpsDataFilter.kt:74` 附近）—— GPS 失联恢复后第一帧位置走 fallback（windowSize 从 0 重新累计，前 2 帧 cleaned 等于 raw；`window.size >= 3` 才开始 median，line 111-113）。换言之 filter 接通**不能完全消除**单帧 invalidating event 的可能性：失联恢复后第一帧若位置偏出 raw 实际位置 + 矢量反向，detector 会立刻输出 invalidating，阈值 1 + 5 秒 `LAP_INVALIDATED_DISPLAY_WINDOW_MS` → banner 显示 5 秒影响 user 体验。**

**Mitigation**：
- filter 接通**同时**降阈值，jitter 已被根因消除 → 大多数场景 banner 频率不应上升（仅在失联恢复 + 反向位置噪声叠加时残留）
- 失联恢复路径单帧污染概率：BLE 通断 + GPS fix 重获通常间隔 > 1s，叠加单帧矢量反向噪声更小（< 5% 估计），不足以阻塞本 round
- L1/L2 review 期 user 可以拍板"先合 filter 接通保留阈值 3 + 后续 round 降至 1"分阶段，本 round Decision 5 已列 alternative B
- 真机 sanity check（如 user 决定走真机）覆盖：(a) 普通跑圈不闪 banner（filter 消除 jitter）；(b) 故意逆向通过 startfinish gate 弹 banner（阈值 1 反馈即时）；(c) 模拟 BLE 通断 + 复连后第一帧反向位置（如有条件），verify banner 5 秒衰减后回归正常
- 若失联恢复 grace period 实测频繁触发 → 独立 follow-up round 立项 `lap-invalidated-grace-period-on-resync`（filter 失联恢复后 N 帧不计入 invalidating event）；本 round 不预留代码 hook，按 v3 盲点 #1 "scope 收紧不够"原则不在工件 scope 内承诺

### Risk 6：5 cases 单测只覆盖 detector 行为，不覆盖 binary 写入字段

**风险**：本 round 改了 binary sample 字段（Decision 4 的 cleaned 写入），但单测在 `feature/test/src/test/.../usecase/` 不接触 `TelemetryRepository`，binary 字段值不在测试覆盖范围。

**Mitigation**：
- A round (`fix-lap-binary-ts-hygiene`) 已建立 `BinaryLapTelemetryRoundTripTest.kt` 8 cases 含 case H 源码 grep gate；本 round 真正能 catch 回退的 grep gate 是**调用现场**（collect block 内 `bridgeGpsToLapTiming(...)` 实参），已在 `tasks.md §5.1 + §5.4 + spec R5 scenario 3` 覆盖。`bridge` 函数体内字段引用（如 `lat = gpsData.latitude` line 847）变量名永远是函数参数名 `gpsData`，无论入参是 raw 还是 cleaned，源码 grep gate 在函数体内 trivially pass 无保护价值（v3 高频盲点 #7）——所以**不**承诺函数体内 grep gate
- 不另起 round trip test：现有 binary round trip 测试覆盖 sample 编解码格式，字段值是否 cleaned 是上游接线问题，调用现场 grep gate + spec R5 scenario 3 contract test 已构成双层保护（前者 catch 调用现场不接通，后者 catch 接通后字段值不一致）

### Trade-off：spec 内嵌"真机 sanity check"是 OPTIONAL 还是 MUST

| 选项 | 优点 | 缺点 |
|---|---|---|
| OPTIONAL（user 拍板） | 与 v3 review 高频盲点 #13「dead spec / 卸责借口」对齐——明确写"user 可拍板跳过"是透明降级 | filter 接通虽是纯算法但 binary 写入路径确实变了，跳过真机时 user 自担风险 |
| MUST（强制） | 安全 | 真机串行验证排队拖慢 round；本 round 与 W1/W2/W3 都 ready 时排队压力大 |
| 推到 follow-up round | 真机验证延期 | follow-up round 没 codebase 改动只剩"看现状"，立项动机弱 |

**当前选择**：spec 写 OPTIONAL + risks 透明声明 + tasks §6 真机验证步骤标 `[user 拍板]`。**user 在 ff 输出后可以拍板提升为 MUST 或维持 OPTIONAL**。

## Migration Plan

**部署**（worktree → 主区）：
1. worktree `.worktrees/wire-laptime-to-gps-filter` 内 apply（参 tasks.md §1-4）
2. worktree 内跑 `./gradlew :feature:test:compileDebugKotlin :feature:test:testDebugUnitTest :app:compileDebugKotlin`，全绿
3. user 授权后 `git commit`（按看板 §3 checklist；不 `--amend`；不 `--no-verify`；Conventional Commits）
4. `git fetch origin && git rebase feature/track-tech-v2`（W1/W2/W3 与本 round 文件级 0 交叉，rebase 应自动 merge 通过）
5. rebase 后再次跑编译 + 测试
6. 切回主区 `git checkout feature/track-tech-v2 && git merge feature/wire-laptime-to-gps-filter --ff-only`
7. 主区编译确认合回态全绿
8. user 拍板：跳过真机 OR 走真机 sanity check（华为 8KE0219522008434）
9. user 触发 Codex L2 review（Codex 双线 + Opus 子 agent）
10. review pass → 归档 → push 顺序由 user 拍板

**回滚策略**：
- 若 review 期发现根因问题（filter 接通破坏 detector 行为 / binary 写入异常等），revert 单 commit 即可（本 round diff < 30 行，commit 边界清晰）
- 若仅阈值改动有问题，可单独 cherry-pick `LAP_INVALIDATED_DEBOUNCE_MIN_COUNT` 那一行回滚为 3，filter 接通保留

**风险等级**：**low**（小 round + 纯接线 + 无 schema / 无协议改 + 5 cases 单测覆盖 + Decision 4 binary 字段对齐）

## Open Questions

1. **真机验证是否提升为 MUST**：本 design 列为 OPTIONAL（risks Trade-off 节）；user 在 ff 输出后拍板。
2. **A round 真机被跳过的先例是否适用**：A round (`fix-lap-binary-ts-hygiene`) user 拍板"跳过真机——下游 UI 全被 F/I 绕开不依赖窗口过滤"。本 round 改的是 detector 上游，下游 UI（lap_live banner / DELTA tile）会感知到行为变化（banner 不再闪 + 阈值降至 1 后真反向 banner 即时弹）；但变化是"消除回归"+"恢复实时性"，不是"引入新行为"。user 应据此拍板。
3. **是否需要在 commit message body 里明确"filter 滞后 ~160ms 对 lap duration 影响在直线段过线 < 50ms"的可量化结论**：建议明确，便于 Codex review 与未来归档检索。
4. **失联恢复 grace period 是否本 round 内做**（Risk 5 续）：filter `dt > 200ms 重置`后第一帧 fallback 到 raw，反向位置噪声会立刻触发 banner 5 秒。**当前 design 不在本 round 包**，理由是 (a) 触发概率估计 < 5%（BLE 通断 + 反向噪声叠加），(b) 修订方向涉及 LapLiveStateDeriver 内部状态（"最近一次 BLE/GPS 失联恢复时间"需 ViewModel 上行），属于跨模块改动 + 与"圈速通道接通 filter"主题不同 + 第一原则"scope 收紧"。建议 user 拍板：
   - (A) 维持 design 现状 + 真机暴露后立项 follow-up round
   - (B) 本 round 内加 grace period（scope 上升 medium → large + 改 LapLiveStateDeriver 入参签名 + 多 1-2 个 cases 单测）
   - (C) 阈值不降至 1，保留 3 兜底（推翻 Decision 5；本 round 内 R4 全部测试 case + tasks §4 改动需回滚为阈值=3 期望值——commit 历史可逆，回滚成本仅 ~30 分钟）

   **design 倾向 (A)**（理由：触发概率低 + scope 收紧 + filter 接通的根因消除是主目标，grace period 是边界优化），但 user 拍板。如 user 选 (C)，本 round 仍能合回作为"filter 接通"半成果，banner 行为保持兼容，不影响 Phase 1 主线推进。
