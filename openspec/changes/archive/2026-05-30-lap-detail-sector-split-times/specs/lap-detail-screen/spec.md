# lap-detail-screen 规格增量

## ADDED Requirements

### Requirement: 单圈详情屏 SECTORS 卡显示本圈各 sector 耗时

`LapDetailScreen` 的 SECTORS 卡在 `SectorBar` 之外 SHALL 显示本圈各 sector 的耗时（split），耗时由 `getLapTelemetry` 返回的 `sectorBoundaries` 派生。

实现 MUST 满足：

1. **派生**：`computeSectorSplits(sectorBoundaries, lapEndWallClock)` 把 `sectorBoundaries`（首元素 == lapStartWallClock）末尾接 `lapEndWallClock` 后取相邻差，得 `sectorBoundaries.size` 个 split（最后一段到 lapEnd）。
2. **不变式**：各 split 之和 MUST == `lapEndWallClock - sectorBoundaries.first()`（不丢段、不重叠）。
3. **显示**：`sectorBoundaries.size >= 2`（有 ≥1 个 sector 门）时 SHALL 逐段显示 `Sector N` → 耗时（时间字符串，Score 字体，单行 + Ellipsis，MUST NOT DSEG7/Mechanical）。
4. **退化**：`sectorBoundaries.size < 2`（无 sector 门）时 MUST NOT 渲染伪单段 sector 行，改显示「无 sector 分段」提示。
5. **不改契约**：MUST NOT 改 `getLapTelemetry` / `LapTelemetry` / `SectorBar` 组件 API。

#### Scenario: 2 个 sector 门派生 3 段耗时
- **GIVEN** 某圈 `sectorBoundaries == [1000, 2500, 3800]`、`lapEndWallClock == 5000`
- **WHEN** 调 `computeSectorSplits([1000,2500,3800], 5000)`
- **THEN** 返回 `[1500, 1300, 1200]`（3 段）
- **AND** SECTORS 卡显示 Sector 1/2/3 三行耗时

#### Scenario: 反例——无 sector 门退化不显示伪单段
- **GIVEN** 某圈 `sectorBoundaries == [1000]`（无 sector 过线）、`lapEndWallClock == 5000`
- **WHEN** 渲染 SECTORS 卡
- **THEN** `computeSectorSplits` 得 `[4000]`（size 1）
- **AND** MUST NOT 渲染「Sector 1」行（size < 2）
- **AND** 显示「无 sector 分段」提示

#### Scenario: 不变式——各段之和等于圈时
- **GIVEN** 任意 `sectorBoundaries`（首 == lapStart）+ `lapEndWallClock`
- **WHEN** 调 `computeSectorSplits`
- **THEN** `splits.sum() == lapEndWallClock - sectorBoundaries.first()`
- **AND** 若实现漏接 lapEnd 或多减一段，该断言 fail
