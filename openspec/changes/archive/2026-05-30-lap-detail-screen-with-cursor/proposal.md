# Change: lap-detail-screen-with-cursor

## Why

### 问题溯源

圈速数据分析（功能二）的底座已全部就绪但**零生产消费**：

- reader：`TelemetryRepository.getLapTelemetry(sessionId, lapIndex): LapTelemetry?`（`core/data/.../repository/TelemetryRepository.kt:291`）已返回完整单圈切片（samples + 多段 sectorBoundaries + trackId/trackNameSnapshot）。
- 4 个图表/地图组件（`SpeedTimeChart` / `AccelTimeChart` / `SectorBar` / `TrackPolylineMap`，`feature/test/.../ui/components/`）有完整 contract test（W2 round 归档 archive/2026-05-04-chart-and-map-components），但 grep 证实它们只互相引用 + 被单测引用，**无任何生产 screen import**。
- `LapAlignment` / `AccelerationSmoother` 等算法（`core/domain/.../usecase/`）已 land。

**当前 baseline**：`LapSessionDetailScreen.kt`（`feature/test/.../ui/tracktech/LapSessionDetailScreen.kt`）只渲染 Overview + 文字圈列表（`LapRecordRow`，L338）。圈行**不可点**，没有任何图表/地图/游标。`TrackTechAppShell.kt`（L167-187）只有 `lap_session_detail/{sessionId}` 路由，没有单圈详情路由。

从用户视角，「点开一圈 → 看速度/加速度/轨迹曲线 + 拖游标联动」**0% 可用**——缺的是组屏 + 接线 + 导航，不是底层算法。这正是路线图 §0.3 诊断的「根因 1：Tier2 UI 屏纯串行排在队列尾段，3.5 周一个 UI 屏都没启动」。

### 用户场景

用户在 Laps tab 点开一个圈速 session（进入 `LapSessionDetailScreen`），看到圈列表后想分析「第 3 圈」的细节：点击 Lap 3 行 → 进入单圈详情屏 → 看到这一圈的速度曲线、加速度曲线、分段条、轨迹地图，拖动任一图表上的游标，4 个组件同步高亮同一时间点的数据（同一圈内 4 组件共享同一 `LapTelemetry.samples`，`absoluteTsMs` 精确相等匹配可命中）。

### 这是 M2 里程碑核心交付物

本 round 是路线图 M2「单圈详情屏能回放」的核心交付物 1：sector 多段派生（M2 前置 `future-sector-derivation` 已归档 archive/2026-05-29）已就绪，本 round 完成组屏 + 接线 + 导航后，**功能一 + 功能二首次端到端可用**。

## What Changes

1. **新建单圈详情屏** `LapDetailScreen`（`feature/test/.../ui/tracktech/LapDetailScreen.kt`，新建）：
   - DetailHeader（back + "LAP DETAIL" 标题）
   - Lap Overview（圈号 / 圈时 / track name / top speed in lap）——**圈时是时间字符串，用 Score 字体（非 DSEG7）**
   - 4 个组件：`SpeedTimeChart` + `AccelTimeChart` + `SectorBar` + `TrackPolylineMap`
   - 共享游标 `cursorAbsoluteTs` state hoisting：任一 chart 拖动 → 4 组件同步高亮
2. **新路由** `lap_detail/{sessionId}/{lapIndex}` 注册到 `TrackTechAppShell.kt`（两个 navArgument：sessionId=StringType + lapIndex=IntType）。
3. **`LapSessionDetailScreen` 圈行 onClick**：VALID/BEST 圈行（`LapRecordRow`）加 onClick → 导航 `lap_detail/$sessionId/${lapNumber-1}`（lapIndex = lapNumber-1，与 `deriveDetailMetrics` 圈编号同源，已由 `unify-lap-count-pairing-semantics` 收敛站点 B/C 排序键统一为 wallClock）。
4. **LaunchedEffect 加载**：`LapDetailScreen` 用 `LaunchedEffect(sessionId, lapIndex)` 调 `getLapTelemetry(sessionId, lapIndex)` 拿 `LapTelemetry`。
5. **R1 accelerationG = UI 层派生**：`getLapTelemetry` 返回的 sample.accelerationG 恒 null（reader 硬编码 L313）。在 UI 数据准备层用 `core/domain` 的 `AccelerationSmoother` 从 samples 的 speedKmh + absoluteTsMs 反算 accelerationG（`/ GRAVITY_MS2` 转 G），构造带 accelerationG 的 sample 列表喂 `AccelTimeChart`。**不改 reader / 不改 LapTelemetry 公共契约**（M2 纯组屏，不触发 v3 #16）。
6. **R2 sectorBoundaries = 消费多段**：`future-sector-derivation` 已合回，`getLapTelemetry` 现返回多段 sectorBoundaries，`SectorBar` 直接消费多段，无需再改 reader。
7. **V2 视觉约束**：DSEG7 仅用于仪表瞬时数字（游标读数 SPEED/G 在组件内部已用 Mechanical）；屏内所有 metric/label/标题类 Text MUST maxLines=1 + Ellipsis；圈时字符串用 Score。
8. **FileLogger 埋点**（road-test-first 强制）：detail 屏 LaunchedEffect 数据加载（成功/失败/越界 null）/ 游标关键状态转移 / accelerationG 派生 / 错误降级路径。

### 不在本 round 范围（显式 out-of-scope）

- 多圈比较（跨圈 cursor gridIndex 映射）→ M3 `lap-comparison-screen-with-cursor`。
- 25Hz 全量渲染降采样/虚拟化 → 本 round design §risks 评估后 defer，立 future round `chart-downsample-virtualization`（§10 backlog）。
- 改 4 组件的公共 API（cursor 入参签名）→ 本 round 复用现有 `absoluteTsMs` 精确相等签名，单圈内可命中，无需改签名。
- 改 reader / LapTelemetry 数据契约填充语义 → R1/R2 都在 UI 层消费，不触发。

## Impact

- Affected specs: 新增 capability `lap-detail-screen`（屏级 capability，mirror `performance-result-screen-v2`）。
- Affected code:
  - 新建 `feature/test/.../ui/tracktech/LapDetailScreen.kt`
  - 修改 `feature/test/.../ui/tracktech/TrackTechAppShell.kt`（加 1 个 route）
  - 修改 `feature/test/.../ui/tracktech/LapSessionDetailScreen.kt`（`LapRecordRow` 加 onClick + navigate）
  - 新增测试 `feature/test/src/test/.../ui/tracktech/LapDetailScreenContractTest.kt`
  - 新增/复用 UI 层 accelerationG 派生纯函数（可放 `LapDetailScreen.kt` internal 或独立 helper，便于单测）+ 其单测
- 公共协议 / Room schema / reader 契约：**0 改动**（road-test-first 不升级 medium 的 5 个例外场景均未命中）。
- 看板：本 round 独占 `LapDetailScreen.kt`（新建）；`TrackTechAppShell.kt` 与 `LapSessionDetailScreen.kt` 当前无并行 round 占用（看板 §5 round 5 lap-comparison 未启动），合回时 §6 登记。
