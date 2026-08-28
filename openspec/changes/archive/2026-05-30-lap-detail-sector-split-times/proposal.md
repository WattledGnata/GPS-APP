## Why

M2 `lap-detail-screen-with-cursor` 真机路测（vivo V2405A，2026-05-30）用户反馈：看圈速真正关注的是**各 sector 的耗时**（split），而当前 M2 详情屏 SECTORS 卡只有一个 `SectorBar`——按比例上色 + 游标高亮的**装饰条**，不显示任何时间信息（grep 坐实 `SectorBar.kt` 只 drawRect + 游标 fraction，无 Text/时间）。用户场景：跑完一圈点进详情屏，想知道这圈各段分别用了多久，定位哪个 sector 慢。

数据已现成：`getLapTelemetry` 返回的 `sectorBoundaries`（future-sector-derivation round 已派生为 `[lapStartWallClock, sector1WallClock, ...]` 多段）就是各 sector 过线时间戳，相邻差即各段耗时，无需新数据源/新查询。

## What Changes

- 新增 `computeSectorSplits(sectorBoundaries, lapEndWallClock): List<Long>` 纯函数（LapDetailScreen.kt）：`sectorBoundaries` 末尾接 `lapEndWallClock`，相邻差得各 sector 耗时。
- `LapDetailScreen` SECTORS 卡：保留 `SectorBar`（仍配游标看位置），其下方列各 sector 耗时（`OverviewRow`，时间字符串走 Score 字体，单行 Ellipsis）；无 sector 门（单 boundary）显示「无 sector 分段」。
- 单测 `LapDetailSectorSplitsTest`（5 case）。

**不改**：reader / `getLapTelemetry` / `LapTelemetry` 公共契约 / `SectorBar` 组件 / 公共协议 / Room schema。

## Impact

- 受影响：`feature/test/.../ui/tracktech/LapDetailScreen.kt`（+ 纯函数 + SECTORS 卡渲染）；新增测试 1 文件。
- 跨圈「理论最佳圈」（各 sector 最快段拼接）= 独立 follow-up round，放 `LapSessionDetailScreen` 圈列表屏顶部（session 级跨圈指标，用户已拍板，见 tasks §10）。
