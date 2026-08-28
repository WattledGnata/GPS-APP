## Context

理论最佳圈 = 跨圈 session 级聚合。`LapSessionDetailScreen` 已加载全部 crossings。road-test-first 模式。

## Decisions

### Decision 1：数据源 = 复用屏已加载的 crossings（纯函数），不新增 repository 方法 / 不重读 binary

- **选**：`computeTheoreticalBest(crossings)` 从 `LapSessionDetailScreen` 已 `getCrossings(sessionId)` 的 crossings 直接算（SF 配对出每圈窗口 → 窗口内 accepted Sector wallClock → 每段耗时）。
- **Alt A（拒绝）**：每圈调 `getLapTelemetry(sessionId, lapIndex)` 取 sectorBoundaries → 每圈读 binary（真机实测单圈 `samples=3437`），N 圈 = N×几千 sample 的 IO，只为拿 sector 时间戳，浪费。
- **Alt B（拒绝）**：新增 repository 方法 `getSessionSectorSplits` → 多一层 core/data 改动 + 测试边界，本 round 数据已在屏内现成，无必要。
- **rationale**：crossings 已在内存，sector 派生是纯函数；零 IO、零 repository/契约改、零 #16。与 `deriveDetailMetrics`（同样吃这份 crossings）一致。

### Decision 2：仅"完整圈"（split 数 == 最大 sector 段数）参与每 sector 最快；sectorCount<2 或无完整圈 → null

- 拼接最优圈要求各圈 sector 数一致才能"best s1 + best s2 + ..."。debug 宽容闭合圈可能段数不同。
- **选**：`sectorCount = max(各圈段数)`；仅 `splits.size == sectorCount` 的圈参与每 sector min；`sectorCount < 2`（无 sector 门）或无完整圈 → 返回 null（面板不显示）。
- **rationale**：避免混合不同段数的圈产生错误拼接；无 sector 门的赛道理论最佳退化成最快圈（无意义）故不显示，诚实。

### Decision 3：位置 = LapSessionDetailScreen 顶部（LazyColumn 首 item，OverviewSection 之前）

- 用户拍板放圈列表屏顶部当头条。`theoreticalBest != null` 才占位。

## Risks

- **仅 1 个完整圈**：理论最佳 == 该圈（无提升），「比最快圈快」行不显示（gain<=0）。可接受——诚实反映无跨圈提升空间。
- **sector 段数不一致**：非完整圈被排除，不参与拼接；mitigation：取 max 段数为基准 + 完整圈过滤，spec 反例 scenario（rejected sector 致段数变化）锁死。
