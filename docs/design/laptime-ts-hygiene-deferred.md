# Lap session binary telemetry 时间轴 hygiene — 延期立项设计 memo

**状态**：deferred，将作为独立 round `fix-lap-binary-ts-hygiene` 立项

**起源**：`add-lap-session-phase1` round §8 真机验证发现 LapSessionDetailScreen 的 TOP SPEED 显示 `--`，溯源到 lap session binary writer 的 `tsDeltaMs` 字段被 raw GPS 协议时间与真壁钟混合污染

**已诊断**：本 round 已用 `readPerformanceSamples`（不过滤时间窗口）作为 detail 屏 quick fix；baseline 修复是数据流根因消除路径

---

## 1. 现象

T40 simulator → 华为 8KE0219522008434 跑完一段 replay → HOLD TO END → Snackbar `View Record` → 进入 LapSessionDetailScreen：

- BEST LAP / TOTAL LAPS / VALID LAPS 等正常
- **TOP SPEED 显示 `--`**（之前用 `readLapSamples` 过滤窗口的实现）

## 2. 根因（baseline 真 bug）

### 2.1 写入侧时间轴混合

`feature/test/.../viewmodel/TestSessionViewModel.kt:562`：

```kotlin
tsDeltaMs = gpsData.timestamp - lapAnchorTs
//          ↑ 协议 epoch ms       ↑ 真壁钟（System.currentTimeMillis）
//          RaceChronoParser 拼接的本地  bridgeGpsToLapTiming 进入时记录
//          hourStartMillis + 协议小时内 ms
```

两个时间轴：
- `gpsData.timestamp`：协议解析的 epoch ms，依赖接收端"当前小时起点"+ 协议字段（小时内 ms / 2）
- `lapAnchorTs`：`System.currentTimeMillis()`（真壁钟）

simulator 协议时间字段以 mod 3,600,000 编码（原因见 `gps-app/CLAUDE.md` "公共协议不可改动边界"），解码后的 epoch ms 在 simulator 重启 / 跨小时时会跳变；真壁钟稳定。两者**不在同一时间轴**。

### 2.2 entity / header 与写入时差混合

```kotlin
// TelemetryRepository.startSession()
val startTs = System.currentTimeMillis()              // 真壁钟
sessionDao.insert(TelemetrySessionEntity(startTs = startTs, ...))
writer.open(filePath, type, startTs)                  // header.startTs = 真壁钟
```

```kotlin
// TelemetryRepository.endSession()
sessionDao.updateEndTs(sessionId, System.currentTimeMillis())  // 真壁钟
```

entity.startTs / entity.endTs / header.startTs 全部真壁钟。`sample.tsDeltaMs` 却是"协议ts - 真壁钟"。

### 2.3 reader 重建 absoluteTs 时换轴

```kotlin
// LapTelemetryReader.read(filePath, lapStartTs, lapEndTs)
val absoluteTs = header.startTs + sample.tsDeltaMs
//             = 真壁钟 + (协议ts - 真壁钟)
//             = 协议ts
.filter { absoluteTs in lapStartTs..lapEndTs }  // 协议ts vs 真壁钟范围
//                                              永远 false
```

filter 100% reject 所有样本 → `readLapSamples` 返回空 list → `maxOf{speedKmh}` → null → UI `--`。

### 2.4 影响面

| 调用点 | 现状 | 影响 |
|---|---|---|
| `LapSessionDetailScreen` 用 `readLapSamples` 派生 TOP SPEED | 永远空 | TOP SPEED 显示 `--` |
| 任何按时间窗口截取 lap segment 的未来 UI（如 Analysis Mode 单圈轨迹） | 永远空 | 完全不可用 |
| Records LAPS sub-tab session row 期望的"圈分段"读取 | 永远空 | 完全不可用 |
| `readPerformanceSamples`（顺序读不过滤） | 仍可用 | 全程数据可读，但失去按圈过滤能力 |

也就是说：**当前 lap session binary 数据"能存能全读，但不能按时间窗口过滤取某一圈"**。

## 3. baseline 修复方案

### 3.1 方案 A（推荐）：bridge 层改用真壁钟差

`TestSessionViewModel.bridgeGpsToLapTiming` line 562：

```kotlin
// 改前（bug）
tsDeltaMs = gpsData.timestamp - lapAnchorTs

// 改后（同时间轴）
tsDeltaMs = System.currentTimeMillis() - lapAnchorTs
```

- 改动：1 行
- 影响：tsDeltaMs 变成"接收侧真壁钟差"，与 header.startTs 同源
- reader filter：absoluteTs = 真壁钟 + 真壁钟差 = 真壁钟，与 entity.startTs/endTs 同源 → filter 正确
- 副作用：tsDeltaMs 不再反映 GPS 协议时间，回放精度依赖接收侧时钟（不依赖 GPS 协议时钟）。在 25Hz 推流 + 接收侧单调时钟下，相邻样本的 tsDeltaMs 差稳定 ~40ms，足够圈速精度

### 3.2 方案 B：reader 不重建 absoluteTs

让 reader filter 改为相对时间（lapStartTs / lapEndTs 也是相对 session 起点的偏移）。

- 改动：3 行（reader + caller）
- 影响：API 语义改 absoluteTs → relativeTs，所有 caller 同步改
- 副作用：caller 必须自己换算，更繁琐

方案 A 更干净，1 行改动 + 不影响 reader 接口。推荐。

### 3.3 方案 C：写入侧改用协议时间但 entity 也用协议时间

让 entity.startTs / entity.endTs / header.startTs 全部用 `gpsData.timestamp`（协议解析的 epoch ms）。

- 改动：3 处
- 影响：entity 时间字段不再是真壁钟，依赖 GPS 协议同步状态（`isTimeSynced` 决定时间字段是否可信），未同步时 entity 写入 sentinel ts
- 副作用：UI 层显示 session 时间需要兼容 sentinel；多设备时钟同步问题暴露
- 不推荐：偏离"接收侧用真壁钟，发送侧用协议时间"的常见架构

## 4. 单元测试覆盖建议

新 round `fix-lap-binary-ts-hygiene` 的测试套件 MUST 含以下 scenario：

- **`writer-reader round trip with same clock domain`**：写入 N 帧 sample（lapAnchorTs=10000，tsDeltaMs=0/40/80/...），endSession，readLapSamples(filePath, 10000, 10000+N×40)，验证返回 N 帧，speedKmh 正确
- **`readLapSamples filter rejects out-of-window samples`**：写入 100 帧（持续 4 秒），调 readLapSamples 截取中间 2 秒 [11000, 13000]，验证返回 50 帧
- **`readPerformanceSamples returns all samples regardless of window`**：与方案 A 兼容性确认
- **`startTs / endTs / header.startTs all in same clock domain`**：grep `System.currentTimeMillis` 在 startSession / endSession / writer 三处，验证唯一时钟来源

## 5. 与本 round quick fix 的关系

本 round (`add-lap-session-phase1`) 的 quick fix：

```kotlin
// LapSessionDetailScreen.kt 派生 topSpeed
telemetryRepository.readPerformanceSamples(sess.binaryFilePath)  // 顺序读不过滤
```

避开了 readLapSamples 的窗口过滤，但**没有解决**baseline 的 tsDeltaMs 时间轴混合。任何依赖时间窗口过滤的未来功能（如单圈轨迹回放、sector 分段）仍然不可用。

baseline 修复后，本 round 的 quick fix 可恢复用 `readLapSamples`（精确按 session 时间窗口过滤），无需改 detail 屏代码。

## 6. 实施约束（独立 round 立项时的硬契约）

1. **MUST 用方案 A**（bridge 层 1 行改 tsDeltaMs 公式），不动 reader / writer 接口
2. **MUST NOT** 让 entity.startTs / entity.endTs / header.startTs 出现协议时间（保持真壁钟单一时钟域）
3. **MUST** 加 round trip 单元测试：写入 + 读出，验证 absoluteTs 落在 entity 时间窗口内
4. **MUST** grep 自检：`bridgeGpsToLapTiming` 不再出现 `gpsData.timestamp - lapAnchorTs` 的减法（同时间轴减法应是 `System.currentTimeMillis() - lapAnchorTs`）
5. **MUST** 检查 simulator replay 路径（`SimulatorViewModel.startReplayDataUpdate`）下 tsDeltaMs 计算是否同样需要修正

## 7. 不立刻并入 add-lap-session-phase1 的理由

- 本 round 主题是"UI 接通 + 派生 + 持久化"，baseline 时间轴 hygiene 是数据写入路径改造，主题不同
- baseline 改动需要独立 round trip 单元测试（写一段 + 读一段 + 验证 absoluteTs），跟"UI 接通"测试不同
- 当前 quick fix 让 detail 屏的 TOP SPEED 工作起来；按时间窗口过滤的需求要等到 Analysis Mode 才出现，时间窗够立项

## 8. 立项节奏

预计独立 round 工件量：
- proposal / design / specs / tasks
- 代码改动 ~1 行（核心）+ 测试套件
- 单元测试 ~4 个 case
- 估时：1 天工件 + 半天实施 + 半天 Codex review

## 9. 与"`wire-laptime-to-gps-filter`"follow-up 的关系

两条 follow-up 各自独立：

| Round | 解决问题 |
|---|---|
| `fix-lap-binary-ts-hygiene`（本 memo） | binary 写入读取时间轴对齐，让 readLapSamples 正常工作 |
| `wire-laptime-to-gps-filter` | lap timing 数据流接 GpsDataFilter，jitter 消除 |

**不冲突**：filter 接通改的是 detector 接收的 lat/lon/speed/bearing，不影响 timestamp。tsDeltaMs hygiene 修复的是 binary 写入时间字段。两者改动点不重叠，可独立推进。

---

**索引位置**：`openspec/changes/add-lap-session-phase1/tasks.md` §10 follow-up backlog 引用本文件
