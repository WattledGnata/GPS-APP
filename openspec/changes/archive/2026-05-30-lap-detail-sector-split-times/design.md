## Context

M2 详情屏 SECTORS 卡只有装饰性 `SectorBar`。用户路测要各 sector 耗时。`getLapTelemetry` 已返回多段 `sectorBoundaries`（future-sector-derivation round），数据现成。road-test-first 模式。

## Decisions

### Decision 1：sector 耗时显示形态 = SectorBar 下方的耗时行（OverviewRow 列表）

- **选**：保留 `SectorBar`（游标定位有用），其下方用 `OverviewRow`（`Sector N` → 耗时）逐行列各 sector 耗时。
- **Alt A（拒绝）**：在 `SectorBar` 各段内部叠加耗时数字 → 需改 W2 公共组件 `SectorBar` API + Canvas 内文字布局复杂 + 窄段文字溢出，超出本 round scope。
- **Alt B（拒绝）**：删 `SectorBar` 只留耗时列表 → 丢失游标位置可视化（拖游标看当前在哪段），降级体验。
- **rationale**：行列表零改 W2 组件、任意 sector 数都不溢出（垂直堆叠 + 单行 Ellipsis）、与 LapOverviewSection 的 `OverviewRow` 视觉一致；时间字符串走 Score 字体（V2 约束 MUST NOT DSEG7）。

### Decision 2：无 sector 门退化 = 显示「无 sector 分段」提示，不显示伪单段

- `sectorBoundaries=[lapStart]`（无 sector 过线）→ `computeSectorSplits` 得 1 个 split = 全圈耗时。若直接渲染会显示「Sector 1 = 全圈时间」，与 Overview 的 Lap time 重复且误导（看似有 1 个 sector）。
- **选**：`splits.size >= 2` 才渲染 sector 行；否则显示 muted「无 sector 分段」。
- **rationale**：诚实——无 sector 门的赛道就是没分段可看，不伪造「1 个 sector」。

## Risks

- **sector 数因赛道而异**：行列表垂直堆叠 + 单行 Ellipsis 天然适配任意数量，无溢出风险。
- **耗时格式**：复用 `formatLapDetailTime`（m:ss.mmm），sector 短段显示如 `0:23.456`，前导 `0:` 略冗但与圈时格式一致；mitigation：先用统一格式，若路测嫌冗后续可加 sector 专用 `ss.mmm` 格式（不阻塞本 round）。
