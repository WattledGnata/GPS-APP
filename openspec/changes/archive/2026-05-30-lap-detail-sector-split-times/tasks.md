# Tasks

## 1. computeSectorSplits 纯函数
- [x] 1.1 `LapDetailScreen.kt` 加 `internal fun computeSectorSplits(sectorBoundaries, lapEndWallClock): List<Long>`（bounds = sectorBoundaries + lapEnd；zipWithNext 相邻差；空 boundaries → 空）

## 2. SECTORS 卡渲染各 sector 耗时
- [x] 2.1 SECTORS `ChartCard` 在 `SectorBar` 下方：`splits.size >= 2` 时 `forEachIndexed` 渲染 `OverviewRow("Sector ${i+1}", formatLapDetailTime(split))`
- [x] 2.2 退化：`splits.size < 2` 显示 muted「无 sector 分段」（单行 Ellipsis）
- [x] 2.3 时间字符串走 Score 字体（formatLapDetailTime，非 DSEG7）

## 3. 单测
- [x] 3.1 `LapDetailSectorSplitsTest`：2 门→3 段 / 1 门→2 段 / 无门→全圈 / 空 / 和==圈时不变式（5 case 全绿）

## 4. 编译 + 真机
- [x] 4.1 `:feature:test:testDebugUnitTest` 绿 + `:app:assembleDebug` 编译
- [x] 4.2 真机 vivo V2405A 验证（用户路测签收 OK）

## 10. follow-up backlog
- [ ] 10.1 `lap-session-theoretical-best`（**下个 round**，medium）：`LapSessionDetailScreen` 圈列表屏顶部显示**理论最佳圈** = session 内各 sector 取所有圈中最快段拼接，并标每 sector 最快是哪圈。跨圈 session 级聚合（需遍历 session 所有圈的 sector splits）。用户 2026-05-30 拍板放圈列表屏顶部。**前置数据依赖**：session 各圈须有 sector 门派生（若赛道无 sector 门则理论最佳退化 == 最快圈，需 design 处理）。
- [ ] 10.2 sector 专用耗时格式 `ss.mmm`（若路测嫌 `0:23.456` 前导 `0:` 冗余）——非阻塞，触发条件=用户反馈
