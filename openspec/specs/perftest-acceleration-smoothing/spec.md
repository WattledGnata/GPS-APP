# perftest-acceleration-smoothing Specification

## Purpose

性能测试（0-100 加速 / 100-0 制动）加速度信号的平滑差分、统计方向区分（maxAcceleration / maxDeceleration）、UI 渲染契约。

由 round `smooth-perftest-acceleration-curve` 创建（2026-05-03 归档）：替代原相邻一阶差分（满屏高频尖刺），统一离线统计与 UI 曲线的加速度计算入口为 5 点 Savitzky-Golay 中心差分；同时拆分 maxAcceleration（正向最大加速 G）/ maxDeceleration（负向最大制动 G 绝对值）字段语义，UI 按 testTemplateId 二选一渲染，V1 brake 存量记录走 "—" + "V1 record" 副标降级。

## Requirements

### Requirement: AccelerationSmoother 实现 5 点 Savitzky-Golay 中心差分

`core/domain/.../usecase/AccelerationSmoother` MUST 提供纯函数 `compute(speedSamples: List<TimedSpeedSample>): List<Double>`，输入按时间升序的速度序列（含 timestamp 与 speed），输出每点对应的纵向加速度（单位 m/s²，正向加速 > 0、制动 < 0），长度与输入一致。

**等间距假设**：算法基于均匀采样 SG 多项式拟合的教科书系数（Press et al., Numerical Recipes §14.8）。`compute` 内部 MUST：

1. 对输入序列计算所有相邻帧 `dt[i] = (t[i+1] - t[i]) / 1000.0`（秒）
2. 取 `dt_median = median(dt)` 作为系数缩放基准
3. 检查每帧 dt 是否落在 `[0.8 × dt_median, 1.2 × dt_median]` 区间（即偏差 < 20%）
4. 全部帧合规 → 走 5 点 SG 路径（系数表见下，分母统一用 `dt_median`）
5. 任一帧偏差 ≥ 20% → 整段输入退化到 3 点 SG 路径（系数见「退化系数表」N=3 行；当 N ≥ 5 但 dt 不均匀时仍用 3 点 SG 形式逐点计算，使用各点局部 dt）；MUST NOT 直接套用 5 点 SG 中心系数

**5 点 SG 一阶导数系数表**（dt = `dt_median`，速度单位 m/s）：

| 帧位置 | 系数（分子 / 分母） |
|---|---|
| 中心 i = 2 ~ N−3 | `[-2, -1, 0, 1, 2] / (10 · dt)` |
| 前边界 i = 0 | `[-25, 48, -36, 16, -3] / (12 · dt)` |
| 前边界 i = 1 | `[-3, -10, 18, -6, 1] / (12 · dt)` |
| 后边界 i = N−2 | `[-1, 6, -18, 10, 3] / (12 · dt)` |
| 后边界 i = N−1 | `[3, -16, 36, -48, 25] / (12 · dt)` |

边界系数 MUST 保持 5 阶精度，禁止退化为 3 点 SG。

**退化系数表（输入长度 N < 5 时使用，速度单位 m/s）**：

| N | 系数 |
|---|---|
| 0 / 1 | 返回 `List(N) { 0.0 }`（无足够数据，避免抛异常） |
| 2 | `a[0] = a[1] = (v[1] - v[0]) / dt`（两点直接差分） |
| 3 | i=0: `(-3v[0] + 4v[1] - v[2]) / (2·dt)`；i=1: `(v[2] - v[0]) / (2·dt)`；i=2: `(v[0] - 4v[1] + 3v[2]) / (2·dt)` |
| 4 | i=0: `(-11v[0] + 18v[1] - 9v[2] + 2v[3]) / (6·dt)`；i=1: `(-2v[0] - 3v[1] + 6v[2] - v[3]) / (6·dt)`；i=2,3 用 4 点 backward 对称系数 |

`speed` 输入单位 km/h，函数内部 `v_m_s = speed_kmh / 3.6` 后再代入系数。

#### Scenario: 等间隔 25Hz 匀加速序列

- **GIVEN** `speedSamples` 为 10 个等间隔 40ms 的速度序列，速度从 0 km/h 线性增至 90 km/h（每帧 +10 km/h，恒定 a = 10/3.6/0.04 ≈ 69.4 m/s²）
- **WHEN** 调用 `AccelerationSmoother.compute(speedSamples)`
- **THEN** 返回列表长度 = 10
- **AND** 中心区间（index 2..7）每点 `accel ≈ 69.4 m/s²`，与理论值偏差 < 0.5 m/s²

#### Scenario: 5 点 SG 抑制高频噪声

- **GIVEN** 合成"匀加速 + 高频小噪声"序列：基线 a = 5 m/s²，叠加每帧 ±0.1 km/h 量化噪声（25Hz × 200 帧）
- **WHEN** 计算朴素相邻差分 `a_naive` vs `AccelerationSmoother.compute` 的 `a_sg`
- **THEN** `a_sg` 相对真值 5 m/s² 的 RMSE 较 `a_naive` 降幅 ≥ 70%

#### Scenario: 输入长度小于 5 走退化系数表

- **GIVEN** `speedSamples` 长度为 0、1、2、3、4 的边界情况，且全部等间距 40ms
- **WHEN** 调用 `compute`
- **THEN** 长度 0/1 返回 `List(N) { 0.0 }`
- **AND** 长度 2-4 按上文「退化系数表」输出；构造解析可解的等加速度序列（v(t) = a·t + v0）时每点偏差 < 0.5 m/s²
- **AND** 任一长度都 MUST NOT 抛异常或越界

#### Scenario: 反例 — 非均匀 dt（单帧偏差 ≥ 20%）拒绝走 5 点 SG 中心系数

- **GIVEN** 5 点速度序列 `[10, 20, 30, 40, 50] km/h`，timestamp `[0, 40, 240, 280, 320] ms`（第 2 帧 dt=200ms，其它 40ms，dt_median=40ms，第 2 帧偏差 +400% ≥ 20%）
- **WHEN** 调用 `AccelerationSmoother.compute`
- **THEN** 输出 MUST NOT 由 5 点 SG 中心系数 `[-2,-1,0,1,2]/(10·dt_median)` 直接生成（该路径会得出错误的加速度估计）
- **AND** 应走退化路径（按「等间距假设」第 5 条）

#### Scenario: 5 点 SG 自身可压低单点速度跳变（无需级联 median）

- **GIVEN** 真值匀速 50 km/h（21 帧等间距 40ms），第 10 帧被注入单点跳变到 55 km/h（其余 20 帧保持 50 km/h）
- **WHEN** 直接调用 `AccelerationSmoother.compute`（**不做 median 预处理**）
- **THEN** 第 10 帧及邻近 ±2 帧的 |accel| 均 < 8 m/s²（裸跳变 5 km/h / 0.04s = 34.7 m/s² 被 5 点 SG 分摊到 5 帧后压低 ≥ 4 倍）
- **AND** 距离跳变点 ≥ 3 帧的所有点 |accel| < 0.5 m/s²（SG 5 点窗口外不受影响）

注：本 scenario 锁死 SG **自身**的 spike 抑制能力，**与产线 GpsDataFilter 9 点 median 解耦**。Binary 持久化路径上 binary 内的 speed 已是 outputSpeed（即已过 9 点 median，见 `TestSessionViewModel.kt:646` `speedKmh = filteredData.speed`），离线 SG 不再做二次 median 级联（避免双 median 失真）。GpsDataFilter warmup 阶段（前 8 帧 + previousRaw=null 早退）的起步跳变属于另一根 capability `improve-gps-filter-startup-warmup`（follow-up backlog）。

### Requirement: 离线 G 值统计与 UI 曲线 MUST 共用 AccelerationSmoother

`core/domain/.../usecase/CalculateResultUseCase` 的 `calculateAccelerations` MUST 调用 `AccelerationSmoother.compute`，不得保留独立的相邻一阶差分循环；`feature/test/.../ui/components/SpeedChart.kt` 中 `GForceChart` 内部 G 值序列 MUST 调用 `AccelerationSmoother.compute`，不得在 Composable 内部独立差分。

相同输入 `dataPoints` 在两处的输出（按 m/s² 与 G 单位互转后）MUST 逐点一致。

#### Scenario: CalculateResultUseCase 与 GForceChart 输出对齐

- **GIVEN** 同一份 `List<GpsDataPoint>`(≥ 5 点)
- **WHEN** `CalculateResultUseCase` 计算 `accelerations` 序列；`GForceChart` 计算 `gForcePoints` 序列
- **THEN** 两序列在每个 index 处的 `accel(m/s²)` 与 `gForce × 9.81 (m/s²)` 数值偏差 < 1e−6

#### Scenario: 旧相邻一阶差分代码已移除

- **GIVEN** 实施完成后的 `CalculateResultUseCase.kt` 与 `SpeedChart.kt` 源码
- **WHEN** 对源码做静态检查
- **THEN** 不存在直接 `(curr.speed - prev.speed) / 3.6 / dt` 形式的 G/加速度计算（`AccelerationSmoother` 内部除外）

### Requirement: GpsDataFilter 实时加速度 MUST 基于 outputSpeed

`core/domain/.../usecase/GpsDataFilter.kt` 的 `calculateAcceleration` MUST 使用 `outputSpeed`（9 点 median 后的速度）而非 `previousRaw.speed` / `current.speed` 计算 dv，确保同一帧的输出 `speed` 与 `acceleration` 来自同源平滑速度。

实时路径 MUST NOT 引入 SG 等需要"未来邻居"的算法（保留逐帧低延迟特性，避免 trigger 判定延迟 ≥ 80ms）。

**新缓存 `previousOutputSpeed: Double?` 与既有状态机交互的 invariants**（与 A12/A13/A14 baseline `fix-gps-data-filter-signal-loss-and-anomaly-hygiene` round 锁定的语义对称）：

- **生命周期同 previousRaw**：`dt > 200ms` 触发 A12 重置时，`previousOutputSpeed` MUST 同时清空为 null（与 `previousRaw = null` 同步）；`reset()` 方法 MUST 一并清空
- **异常帧不更新缓存**：当本帧 `isAnomaly == true` 或 `isPositionAnomaly == true` 时，MUST NOT 把本帧的 outputSpeed 写入 `previousOutputSpeed`（与 A13 "异常帧不更新 previousRaw" 对称）
- **warmup 退化声明**：当 `speedWindow.size < 3` 时 outputSpeed 退化为 raw.speed；此时 acceleration 退化为 raw 一阶差分，本 round 不强制覆盖该退化（warmup 期 trigger 判定阈值 ±0.1G 粗糙，不受影响）

#### Scenario: 速度与加速度同源（稳态 9 帧后）

- **GIVEN** `GpsDataFilter` 连续处理 ≥ 9 帧等间距、非异常的 GPS 输入产出 `frames: List<FilteredGpsData>`
- **WHEN** 检查 `frames[i]` 的 `speed` 与 `acceleration`（i ≥ 1，且 i 与 i-1 都非异常）
- **THEN** `frames[i].acceleration ≈ (frames[i].speed - frames[i-1].speed) / 3.6 / ((frames[i].timestamp - frames[i-1].timestamp) / 1000.0)`，偏差 < 1e-6 m/s²

#### Scenario: 实时 trigger 行为有限退化（warmup 期偏差 ≤ 80ms）

- **GIVEN** `TestSessionViewModel.checkAccelerationTrigger` 与 `checkBrakingTrigger` 既有触发阈值（±0.1G 量级 + `isMoving = filteredData.speed > 1.0` 短路）
- **WHEN** 同一段 GPS 输入流分别走旧（raw speed）与新（outputSpeed）计算路径
- **THEN** trigger 触发帧 index 偏差 ≤ 2 帧（80ms）
- **AND** 偏差物理来源：warmup 期 speedWindow 含前置 standstill 帧（speed ≈ 0.5 km/h）与起步加速帧时，9 点 median 把 outputSpeed 拉到 < 1.0 km/h（相对 raw 的滞后），让 `isMoving` 短路在加速首 1-2 帧仍判 false，trigger 计数延后启动
- **AND** 稳态期（speedWindow 全部稳态加速帧后）trigger 偏差应 = 0
- **AND** 真机场景（非合成 fixture）下加速段持续时间远大于 80ms，最终 trigger 时机肉眼无差异

注：原阈值 < 40ms 是基于"raw 与 outputSpeed 仅在 median 平滑层差异"的简化假设，未考虑前置 standstill 帧对 median 的拉低耦合。此处放宽到 80ms 反映实际算法行为。如果未来 follow-up 升级 GpsDataFilter warmup 路径（`improve-gps-filter-startup-warmup` backlog），此偏差可恢复到 < 40ms。

#### Scenario: dt > 200ms 重置时 previousOutputSpeed 一并清空

- **GIVEN** `GpsDataFilter` 已稳态处理 ≥ 10 帧，`previousOutputSpeed` 非 null
- **WHEN** 下一帧 `timestamp - previousRaw.timestamp > 200ms`（信号丢失重连）
- **THEN** 该帧 `acceleration == 0.0`（previousOutputSpeed 已清空，无 dv 可算）
- **AND** 该帧之后再连续处理 9 帧后，`acceleration` 非 0（缓存重新填充）

#### Scenario: 异常帧 MUST NOT 更新 previousOutputSpeed

- **GIVEN** 5 帧匀速 50 km/h（非异常） + 1 帧 isAnomaly = true（速度跳变 100 km/h）+ 5 帧匀速 50 km/h
- **WHEN** 处理至第 7 帧（异常帧后第一帧正常）
- **THEN** 第 7 帧的 `acceleration` 基于 "第 5 帧 outputSpeed" 与 "第 7 帧 outputSpeed" 计算（第 6 帧异常被跳过），dt 跨越异常帧时段
- **AND** `acceleration` 接近 0 m/s²（两帧匀速 50 km/h）

### Requirement: TestResult / TestRecordEntity 区分 maxAcceleration 与 maxDeceleration

`core/domain/.../model/TestModels.kt` 的 `TestResult` MUST 同时持有 `maxAcceleration: Double` 与 `maxDeceleration: Double` 两个字段：

- `maxAcceleration` 严格语义为"采样窗口内 dv/dt > 0 区间最大值（G）"，0-100 加速测试有正值、100-0 制动测试为 0.0
- `maxDeceleration` 严格语义为"采样窗口内 dv/dt < 0 区间最小值的绝对值（G）"，100-0 制动测试有正值、0-100 加速测试为 0.0
- `CalculateResultUseCase` 内部 MUST NOT 对加速度做 `Math.abs()`

`core/data/.../entity/TestRecordEntity` MUST 增列 `maxDeceleration: Double` 默认 0.0。Room schema 触发 v4 → v5 升级。

**Migration 策略**：debug 阶段走 `fallbackToDestructiveMigration()` destructive fallback，不写 strict migration。装新包时 v4 schema test_records 表会被 Room 重建（数据清空）。决策依据：本工程当前在 debug 期，存量 V1 测试记录可接受清空。上线前必须补回严格 migration（参照 v3→v4 既有 `migration3To4` pattern）作为 follow-up（`restore-strict-migrations-pre-release` backlog）。

#### Scenario: 0-100 加速测试 maxDeceleration = 0

- **GIVEN** 一份纯加速段 dataPoints（速度从 0 单调升至 100 km/h）
- **WHEN** `CalculateResultUseCase` 计算 `TestResult`
- **THEN** `result.maxAcceleration > 0`
- **AND** `result.maxDeceleration == 0.0`

**真实场景注**：本 scenario 的 GIVEN 限定为"纯加速段 dataPoints"。真实 acc_0_100 session 通常包含 endSpeed=100 之后的尾段（车继续行驶或减速），此时 `result.maxDeceleration > 0` **不违反 spec**。UI 按 `testTemplateId` 二选一渲染（`acc_0_100` → 仅渲染 `maxAcceleration`），尾段计算出的 `maxDeceleration` 不展示给用户，是内部不可见状态。后续若需要"严格按 startSpeed→endSpeed 子段统计"，立项 follow-up backlog。

#### Scenario: 100-0 制动测试 maxAcceleration = 0

- **GIVEN** 一份纯制动段 dataPoints（速度从 100 单调降至 0 km/h）
- **WHEN** `CalculateResultUseCase` 计算 `TestResult`
- **THEN** `result.maxAcceleration == 0.0`
- **AND** `result.maxDeceleration > 0`（绝对值 G）

#### Scenario: 旧 abs 污染已修复

- **GIVEN** 实施完成后的 `CalculateResultUseCase.kt:calculateAccelerations` 源码
- **WHEN** 对源码做静态检查
- **THEN** 函数体内不存在 `Math.abs(dv / dt)` 或等价的绝对值操作

#### Scenario: Room v4 → v5 走 destructive fallback（debug 阶段决策）

- **GIVEN** 一份 round 启动前的 v4 schema test_records 表（含 maxAcceleration 但无 maxDeceleration），且其中含若干 V1 测试记录
- **WHEN** 装新包（schema v5）启动 app
- **THEN** Room 检测到 missing migration v4 → v5，按 `fallbackToDestructiveMigration()` 决策**重建 test_records 表**
- **AND** 表结构含 `maxDeceleration REAL NOT NULL DEFAULT 0.0`
- **AND** 存量 V1 记录全部清空（test_records 行数 = 0）
- **AND** 启动后用户重新跑测试可正常持久化新字段
- **AND**（上线前 follow-up）补回严格 migration 后该 scenario 改为 "保留存量记录"

#### Scenario: 100-0 制动存量记录 UI 显式降级 "—"

- **GIVEN** 一条存量 V1 记录 `testTemplateId == "brake_100_0"` AND `maxDeceleration == 0.0` AND `maxAcceleration > 0`（旧 abs 污染数据）
- **WHEN** UI（PerformanceResultScreen / RecordsHomeScreen）渲染该记录的 PEAK G tile
- **THEN** tile 数值显式显示 "—"（非 "0.00G"），副标显式标注 "V1 record" 或等价文案
- **AND** UI MUST NOT 用 `maxAcceleration` 字段值作为 fallback 显示（避免 V1 abs 污染语义错位为加速 G）
- **AND** 该 record 在 V2 算法重新跑测后，新 `maxDeceleration` 字段被填充，UI 自动恢复正常显示

注：本 scenario 体现"放弃存量重算"决策（Q2 决议 + Non-Goals）。存量 V1 brake 记录的真实制动 G 不可恢复，UI 显示 "—" 优于显示错值或 V1/V2 双语义。后续 backlog `recompute-historical-perftest-stats` 若立项实施，则可消除该降级。

### Requirement: GForceChart 边界值 MUST clip 而非 drop

`feature/test/.../ui/components/SpeedChart.kt` 的 `GForceChart` MUST 把 `|gForce| > 3.0` 的值 clip 到 ±3.0（按符号保留），而非通过 `return null` 从绘图序列中剔除。

#### Scenario: 单点超 3G clip 到 3G

- **GIVEN** 一份含单点 4.5G 的 G 值序列
- **WHEN** `GForceChart` 渲染折线
- **THEN** 该点在绘图序列中的值 = 3.0（保留符号）
- **AND** 折线连续无断点（不跳过该点）

#### Scenario: 单点 −5G clip 到 −3G

- **GIVEN** 一份含单点 −5G 的 G 值序列
- **WHEN** `GForceChart` 渲染折线
- **THEN** 该点在绘图序列中的值 = −3.0
- **AND** 折线在该点连续

### Requirement: design.md §「数据证据」MUST 引用真机存量 binary 对比

本 round 的 `design.md` MUST 在「数据证据」附录章引用从默认真机 `8KE0219522008434` 拉取的至少 2 条存量 0-100 性能测试 binary 文件（含用户实跑的 10.2s / 7.2s 加速测试 + 至少 1 条 100-0 制动测试），并提供旧（一阶差分）vs 新（5 点 SG）算法的离线对比图作为本 round 的可论证 baseline。

取证脚本 MUST 一次性使用、不进入 codebase（保持产线代码不被取证逻辑污染）。

量化指标定义（汇总通过即视为有效，单条 outlier 必须有物理解释入 design.md 附录）：

- **峰值物理合理区间**：新算法峰值 |G| MUST 落在测试模板的物理合理区间内（0-100 加速测试 [0.3, 1.5] G；100-0 制动测试 [0.5, 1.5] G）
- **峰值邻居支撑**：汇总所有取证记录，新算法峰值前后 ±2 帧均值与峰值的相对偏差均值 < 30%（识别孤立单点 spike 主导峰值的情况；单条偏差 > 30% MUST 在 design.md 附录注明物理原因，例如 GPS 起步瞬间的卫星捕获跳变）
- **高频 RMSE 降幅**：汇总所有取证记录，新算法相对旧算法的高频 RMSE 降幅均值 ≥ 60%。"高频" 定义为信号减去 5 点滑动均值（200ms 窗口，对应 5Hz 物理带宽）后的残差

注：旧算法的"峰值"通常是噪声 spike 而非真实物理峰值，故 MUST NOT 设定 "新峰值 / 旧峰值 ≥ X%" 这种反向指标——这等于要求新算法保留噪声。

**样本量声明**：本 round 数据证据基于 3 条用户实跑记录（同一真机、同一车型、同一驾驶风格），样本量受限于"无法立刻凑齐多车型 / 多路况测试"的现实约束。本 round 接受"3 条样本汇总通过 + 单条 outlier 物理解释" 作为算法选择决策依据；扩展到 ≥ 5 条独立样本（混合 ≥ 2 测试模板 + ≥ 2 驾驶场景）属于 follow-up backlog `evaluate-sg-with-extended-samples`，不构成本 round 闭环阻塞。**MUST NOT** 在闭环宣称"算法在所有车型 / 路况上验证通过"——只能宣称"在 3 条 baseline 记录上验证通过"。

#### Scenario: design.md 含 10.2s 与 7.2s 记录的对比图

- **GIVEN** round 进入 apply 阶段前的 `design.md` 文件
- **WHEN** 检查「数据证据」章节
- **THEN** 章节包含 10.2s 与 7.2s 两条 0-100 加速记录 + 至少 1 条 100-0 制动记录的"旧算法 G 曲线 vs 新算法 G 曲线"对比图（PNG 文件位于 `evidence/` 目录）
- **AND** 章节列出每条记录的：peak old/new G 数字、新峰值是否落物理合理区间、新峰值邻居偏差、HF RMSE old/new/降幅
- **AND** 章节给出汇总判定：物理区间通过率 = 100%、平均邻居偏差 < 30%、平均 HF RMSE 降幅 ≥ 60%
- **AND** 单条 outlier（如某条邻居偏差 ≥ 30%）必须在附录给出物理解释
