## Why

`add-lap-session-phase1` round §8 真机验证暴露：圈速通道（`bridgeGpsToLapTiming`）直接消费 raw `GpsData`，**绕过 baseline `GpsDataFilter`**，单帧 GPS jitter 在 startfinish gate 处偶发触发 `WrongDirection` 误判，UI 弹 `LAP INVALIDATED` banner。

真机数据证据（2026-05-01 T40 simulator replay `tianfu_track_replay_5hz.json` 单次播放 → 华为 8KE0219522008434 lap_live）：

| 指标 | 数量 |
|---|---|
| 总 reject（含 NoIntersection 心跳） | 2742 |
| **真 invalidating（WrongDirection）** | **1 帧** |
| accepted | 0（数据段未含完整一圈） |

唯一一帧 reject：`prev=(30.489698, 104.4325576)` → `cur=(30.4896991, 104.4325696)`，`directionScore = -1.157` 强烈反向——单帧位置 outlier，9 帧 median 必能压制。

`add-lap-session-phase1` 已加 3-event 去抖兜底（`LapLiveStateDeriver.LAP_INVALIDATED_DEBOUNCE_MIN_COUNT = 3`），UI 层不再误闪，但**数据流根因仍在**——filter 接通是根本性消除路径。

Phase 0（数据层闭合）已 archived；Phase 1 第一个 round `lap-data-readers` 已铺好读取契约。本 round 是 Phase 1 期间任意时机插入的"数据流 hardening"，与 W1/W2/W3 函数级 0 交叉，半天 scope 独立闭环。

## What Changes

- `feature/test/.../viewmodel/TestSessionViewModel.kt:347` 处的 `bridgeGpsToLapTiming(gpsData)` 入参由 raw `GpsData` 切到经 `GpsDataFilter.process(gpsData)` 滤波后的 cleaned 副本：仅替换 `latitude / longitude / speed / bearing` 四个字段，**保留** `timestamp / isTimeSynced` 等元信息（filter 不滤时间字段）。
- **MUST NOT skip 任何帧**：即使 `filtered.isAnomaly == true` 或 `filtered.isPositionAnomaly == true`，cleaned 帧仍喂 detector（filter 已用 median 把 outlier 位置拉回窗口中位数；丢点会让 200km/h × 200ms ≈ 11m 真空段污染 crossingProgress 插值）。
- `LapLiveStateDeriver.LAP_INVALIDATED_DEBOUNCE_MIN_COUNT` 由 `3` 降回 `1`：filter 接通后 jitter 已从数据流消除，去抖阈值不再需要兜底；保留 `LAP_INVALIDATED_DEBOUNCE_WINDOW_MS = 1_000L` 仅作"窗口内最多弹一次 banner"的节流。同步更新 line 58 注释，把"filter 接通后阈值可降至 1"从 follow-up 提示改为现状描述。
- 新增 `feature/test/src/test/.../usecase/LapFilterIntegrationTest.kt`（5 cases，纯函数测试 `GpsDataFilter` + `LapTimingEngine` 端到端，无 Robolectric / Android Context 依赖）：
  - `single jitter outlier does not trigger WrongDirection`
  - `lap duration unaffected by filter lag`
  - `anomaly frames not dropped`
  - `bearing wrap-around handled correctly`
  - `filter warmup tolerated`
- 既有 `LapLiveStateDeriverTest.kt` 中针对 `LAP_INVALIDATED_DEBOUNCE_MIN_COUNT = 3` 的 expected 值同步降至 1（含正反例两组：1 帧 invalidating 即触发 banner / 0 帧不触发）。

## Capabilities

### New Capabilities
- `lap-timing-gps-filter-pipeline`: 圈速通道接通 `GpsDataFilter` 的数据流契约——锁死"仅替换位置字段、不 skip 帧、bridge 时间戳保 raw、去抖阈值降至 1"四条 normative 约束，并为日后 review/refactor 提供反例 scenario 防回退。

### Modified Capabilities
（无。`LapLiveStateDeriver` 去抖阈值降回 1 是 filter 接通后的协议级解锁，与 filter 接通契约同源，归到新 capability 内一并锁死，不另列已有 capability 的 modification。）

## Impact

**受影响代码**：
- `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`（collect block line 337-352 + `bridgeGpsToLapTiming` line 766-）
- `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapLiveStateDeriver.kt`（常量 line 60 + 注释 line 58）
- `feature/test/src/test/java/com/blazepush/feature/test/usecase/LapFilterIntegrationTest.kt`（新建，5 cases）
- `feature/test/src/test/java/com/blazepush/feature/test/usecase/LapLiveStateDeriverTest.kt`（已存在；调整去抖 expected 值）

**协议兼容性**：
- **不改** RaceChrono BLE 协议（主包 `0003-...` / 时间包 `0004-...` 字段与编码无任何变化）
- **不改** replay JSON schema（`feature/test/src/main/assets/replay/*.json` 不动）
- **不改** Room schema（`AppDatabase.version` / 任何 entity / migration 不动）
- **不改** telemetry binary 文件 header / sample 编码（`TelemetrySample` data class + 写入字节布局保持）

**双端任务边界**：本 round **仅接收端**改动（`gps-app`）；发射端 `simulator` 模块 0 行 diff。

**数据流副作用**：
- telemetry binary `/telemetry/<sessionId>.bin` 内 sample 的 `lat/lon/speedKmh/bearingDeg` 由 raw 切到 cleaned 版本（与 detector 看到的轨迹一致；回放重建 prev→cur 矢量复现 detector 判定）。`tsDeltaMs` 仍由 raw `System.currentTimeMillis()` 与 `repository.activeSessionStartTs` 派生，与 A round (`fix-lap-binary-ts-hygiene`) 的 anchor 同源契约 0 冲突。
- `crossing_events` 表的 `wallClockMs` 来源不变（`fix-lap-crossing-clock-hygiene` round 已闭环）；过线时刻插值用 raw timestamp，精度不受 filter ~160ms 滞后影响（开/闭圈两次过线滞后量相等，相减抵消，详见 design.md Decision 2）。

**Review v3 复杂度评级**：
- 复杂度：**medium**（边界 case；总 diff ~180 行 + 单 module + 无 schema 改 + telemetry binary 字段语义改动 + LAP_INVALIDATED 去抖阈值降级 + 5 个 Requirements 含反例 grep gate）
- L1 推荐 2-3 轮（按看板 §7 复杂度→轮数表与 plateau 信号判定收敛；第 2 轮聚焦 spec scenarios 与生产代码语义对齐 + grep gate 实际命中行为）；L2 1 轮
- 真机验证：**user 拍板是否走真机**（filter 9 帧 median 是纯算法 + 5 cases 单测覆盖足够；真机仅复测 banner 不再因单帧 jitter 闪 + 阈值降至 1 后 banner 在真反向冲线时仍如预期弹）
