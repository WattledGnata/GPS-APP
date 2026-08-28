# lap-session-detail-screen 规格增量

## ADDED Requirements

### Requirement: 有 sector 门时圈列表渲染为带 sector 列的表

`LapSessionDetailScreen` 在 `computeLapSectorTable(crossings) != null`（有 sector 门 + 有完整圈）时 SHALL 把 valid/best 圈列表渲染为表（每圈一行、各 sector 一列），并显示一行 theoretical best；否则 fallback 原简单圈列表。

实现 MUST 满足：

1. **数据**：`computeLapSectorTable` 从屏已加载 crossings 纯函数派生，MUST NOT 新增 binary 读 / repository 方法 / 改公共契约。同源 SF 配对（lapNumber=idx+1）+ sector 窗口 `[lapStart,lapEnd)`，rejected/null 排除，仅完整圈（splits.size==sectorCount）参与每 sector 最快。
2. **表结构**：表头 `LAP|TIME|S1..SN` + `OPT` 行（`bestSplitPerSector` 各高亮 + `theoreticalTotalMs`）+ 各 valid/best 圈行（每 sector split 命中 `bestSplitPerSector[i]` 高亮 Green、缺段 `—`）。
3. **窄屏**：固定列宽 + 各行共享 `horizontalScroll` 同步；每 cell Text 单行 + Ellipsis；时间字符串走 Score 字体（MUST NOT DSEG7）。
4. **交互**：圈行 clickable 导航 `lap_detail/{sessionId}/{lapNumber-1}`（仅 valid/best）+ FileLogger 埋点；INVALID/INCOMPLETE 圈 MUST 仍在表下方以原行显示（不丢）。
5. **退化**：`< 2` SF / sectorCount `< 2` / 无完整圈 → `computeLapSectorTable` 返回 null → fallback 原 `LapRecordRow` 列表（不回归）。

#### Scenario: 两圈各取最快 sector 拼接（表数据）
- **GIVEN** crossings：Lap1 splits `[500,1300,1200]`、Lap2 `[400,1500,1100]`
- **WHEN** 调 `computeLapSectorTable(crossings)`
- **THEN** `theoreticalTotalMs == 2800`、`bestLapPerSector == [2,1,2]`、`bestSplitPerSector == [400,1300,1100]`
- **AND** 每圈行 splits 对齐 sectorCount

#### Scenario: 反例——rejected sector 排除
- **GIVEN** 同上 + Lap2 多一个 accepted=false 的 Sector
- **THEN** `theoreticalTotalMs == 2800`（rejected 不改变段数/最快）

#### Scenario: 反例——无 sector 门 fallback 原列表
- **GIVEN** 只有 StartFinish（无 Sector）
- **THEN** `computeLapSectorTable` 返回 null
- **AND** 圈列表 MUST 渲染原 `LapRecordRow` 列表（非表）
