# Lap timing 数据流接入 GpsDataFilter — 延期立项设计 memo

**状态**：deferred，将作为独立 round `wire-laptime-to-gps-filter` 立项

**起源**：`add-lap-session-phase1` round §8 真机验证发现 `LAP INVALIDATED` banner 偶发误触发，溯源到 lap timing 数据流绕过 baseline `GpsDataFilter`

**已诊断**：本 round 已加 3-event 去抖兜底；filter 接通是根本性消除路径

---

## 1. 现状

`feature/test/.../viewmodel/TestSessionViewModel.kt:170-176`：

```kotlin
gpsDataViewModel.gpsData.collect { gpsData ->
    val filteredData = gpsDataFilter.process(gpsData)
    updatePreTriggerBuffer(filteredData)        // 加减速通道：用 filtered ✓
    processFilteredData(filteredData)           // 加减速通道：用 filtered ✓
    bridgeGpsToLapTiming(gpsData)               // ← 圈速通道吃 raw，绕过整个 filter
}
```

baseline `core/domain/usecase/GpsDataFilter.kt` 提供：
- 9 帧滚动窗口中位数滤波（speed / lat / lon）
- bearing 循环均值（跨 0°/360° 边界正确收敛）
- 物理约束（加速度 > 2.5G / 减速 > 3.0G → `isAnomaly = true`）
- 位置-速度一致性（`v_implied = Δd/Δt` vs reported speed → `isPositionAnomaly`）
- 失联重置（dt > 200ms 重置 prev 基准）
- 协议未同步守卫（`!isTimeSynced` 早退）

**加减速测试通道经过 filter，圈速通道直达 detector**。

## 2. 真机数据证据（2026-05-01）

T40 + simulator replay `tianfu_track_replay_5hz.json` 单次播放 → 华为 8KE0219522008434 lap_live：

| 指标 | 数量 |
|---|---|
| 总 reject 数（含 NoIntersection 心跳） | 2742 |
| **真 invalidating（WrongDirection）** | **1 帧** |
| accepted（成功过线） | 0（数据段未含完整一圈） |

唯一一帧 reject：

```
prev=(30.489698, 104.4325576, ts=953053414520)
current=(30.4896991, 104.4325696, ts=953053414560)
gate = s1（sector 1）
accepted=false  reason=WrongDirection  directionScore=-1.157
```

**分析**：单帧 GPS 跳点恰好让 prev→cur 矢量与 s1 gate 正向反向，directionScore = -1.157 强烈反向。raw GpsData 直接喂 detector → 一刀认作"反向冲线"。如果 lat/lon 经过 9 帧 median，那帧 outlier 会被中位数剔除（中位数对单帧异常天然鲁棒），方向矢量保持正向。

## 3. 接入方案对比

### 方案 1：用 isAnomaly 标记 skip 整帧

```kotlin
val filtered = filter.process(gpsData)
if (filtered.isAnomaly || filtered.isPositionAnomaly) return
```

- detector 这一帧收不到样本（**真的丢点**）
- 连续 anomaly 时高速段出现 GPS 真空：200km/h × 200ms ≈ 11m 真空段
- crossingProgress 插值精度受影响（线段跨度变 80ms 而非 40ms）

### 方案 2（推荐）：仅替换位置值，不 skip

```kotlin
val filtered = filter.process(gpsData)
val cleaned = gpsData.copy(
    latitude = filtered.latitude,
    longitude = filtered.longitude,
    speed = filtered.speed,
    bearing = filtered.bearing,
)
val currentSample = cleaned.toLapGpsSample()
```

- 每帧都喂 detector（不丢点）
- jitter 帧位置被 median "拉回"窗口中位数 → detector 看到稳定方向矢量
- 单调运动下 filter 后 Δ ≈ raw Δ（median 在单调序列下输出 delta 与原始 delta 一致），仅整体引入 ~160ms 位置滞后

### 对比表

| 维度 | 方案 1 skip | **方案 2 替换** |
|---|---|---|
| 是否丢真实采集点 | 是 | 否 |
| detector 每帧收样本 | 否（anomaly 时） | 是 |
| jitter 屏蔽 | 完全（直接剔除） | 强（中位数压制） |
| 高速 gap 风险 | 有 | 无 |
| 圈时间精度 | 受影响（gap 处插值失真） | 不受影响（160ms 全局滞后抵消） |

## 4. 圈速场景适配性分析（方案 2）

### 4.1 ~160ms 位置滞后从哪来

- `windowSize = 9` 帧 @ 25Hz = 360ms 跨度
- median 取排序后第 5 大的元素（共 9 个）
- 在均匀时间分布下，第 5 帧时间位置 = 窗口最新帧 - 4×40ms = **t - 160ms**

### 4.2 为什么 lap duration 精度不受影响

```
T_开圈_observed = T_开圈_real + 160ms
T_闭圈_observed = T_闭圈_real + 160ms

lap_duration_observed
  = T_闭圈_observed - T_开圈_observed
  = T_闭圈_real - T_开圈_real    ← 160 项抵消
```

前提：开圈 / 闭圈滞后量相等（filter 滞后是窗口中点时间，跟车速无关）。在直线段（startfinish gate 通常设计在直线段）严格成立；弯道速度急剧变化时偏差 < 50ms（远低于圈速比赛 1ms 分辨率需求）。

### 4.3 detector 内部精度保护

```
过线时刻 = lerp(prev_ts, cur_ts, crossingProgress)
                 ↑ raw 时间戳，filter 不改时间字段
```

filter 只滤位置（lat / lon / speed / bearing），**不滤 timestamp**。所以：
- 过线时刻插值用真实 GPS 帧时间
- prev / cur 同源滤波 → 相对运动正确 → crossingProgress 计算正确
- 唯一影响：检测到过线的"那一帧"位置滞后约 160ms，时间戳正确

## 5. 实施约束（独立 round 立项时的硬契约）

1. **MUST 用方案 2**：替换 4 字段 `latitude / longitude / speed / bearing`（与 §3 方案对比一致），**MUST NOT** skip 任何帧。**MUST NOT** 替换 `timestamp`（detector 插值精度依赖 raw 时间戳）。lat/lon median 是消除单帧 GPS jitter 导致 WrongDirection 误判的核心——detector `directionScore = movement · passUnit` 由 prev/cur 的 lat/lon 差完全决定，bearing 字段不在该计算路径上。
2. **MUST NOT** 因 `filtered.isAnomaly == true` 跳过 `bridgeGpsToLapTiming` 内任何处理路径
3. detector 收到的相邻帧 `timestampMillis` 间隔 MUST 与 raw 一致（filter 不改 timestamp）
4. filter 接通后，`LapLiveStateDeriver.LAP_INVALIDATED_DEBOUNCE_MIN_COUNT` 阈值可由 3 降回 1（去抖只是 jitter 兜底；filter 接通后 jitter 已被根本性消除）

## 6. 单元测试覆盖建议

新 round `wire-laptime-to-gps-filter` 的测试套件 MUST 含以下 scenario：

- **`single jitter outlier does not trigger WrongDirection`**：构造 8 帧正常 + 1 帧位置 outlier（如 lat 跳偏 1 度），验证经过 filter 后 detector 输出 accepted=true 或 NoIntersection（不输出 WrongDirection）
- **`lap duration unaffected by filter lag`**：构造一段完整圈数据（开圈 / 闭圈两次过 startfinish），分别用 raw 和 filter 后跑一遍，验证 lap duration 差 < 50ms
- **`anomaly frames not dropped`**：构造连续 5 帧 isAnomaly=true，验证 detector 仍收到 5 帧样本（不出现 ts gap）
- **`bearing wrap-around handled correctly`**：构造跨 0°/360° 边界 bearing 序列，验证 filter 输出与 detector 判定都正确
- **`filter warmup tolerated`**：session 起点前 9 帧窗口未填满，detector 用 raw fallback，验证不出现 NPE / 异常

## 7. 与本 round 去抖兜底的关系

本 round (`add-lap-session-phase1`) 已实施 `LapLiveStateDeriver` 去抖：

```
LAP_INVALIDATED 触发条件 = 最近 1 秒内 ≥ 3 个 invalidating event
```

去抖与 filter 接通**正交**：

- **去抖**：UI 层兜底，区分单帧 jitter（1 个 event）vs 真反向（多帧 event）
- **filter**：数据流根因消除，让 jitter 不进入 detector

filter 接通后，jitter 不再产生 invalidating event，去抖阈值可降回 1（即"任何一次真 invalidating 都立刻弹"），UI 层不再需要兜底逻辑。两者协同，不冲突。

## 8. 不立刻并入 add-lap-session-phase1 的理由

- 本 round 主题是"UI 接通 + 派生 + 持久化"，filter 接通是数据流改造，主题不同
- filter 接通涉及 `bridgeGpsToLapTiming` 的 baseline 语义改动，需独立单元测试覆盖
- 本 round commit message body 已经有 5 个 capability 改动，再叠加 baseline 数据流改造 review 难读
- 当前去抖兜底已让 banner 不闪，体验可接受

## 9. 立项节奏

预计独立 round 工件量：
- proposal / design / specs / tasks
- 代码改动 ~20 行
- 单元测试 ~5 个 case
- 估时：1-2 天工件 + 半天实施 + 半天 Codex review

---

**索引位置**：`openspec/changes/add-lap-session-phase1/tasks.md` §10 follow-up backlog 引用本文件
