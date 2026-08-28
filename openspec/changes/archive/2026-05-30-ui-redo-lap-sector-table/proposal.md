## Why

`lap-session-theoretical-best` round 的独立**大面板**（顶部列出 5 个 sector 逐行 + 总时间 + gain）真机路测被用户否：「数字对，但习惯上一般不这么给这么大的 UI 范围」。赛道分析工具（RaceChrono / AiM）的常规做法是把**圈列表本身做成表**——每圈一行、各 sector 一列、每列最快段高亮，外加一行 theoretical best；信息密度高、不占额外大面板。用户 2026-05-30 拍板走此方案。

## What Changes

- **删**：`TheoreticalBest`/`SectorBest`/`computeTheoreticalBest`/`TheoreticalBestPanel`（旧面板）+ `LapSessionTheoreticalBestTest`。
- **加** `computeLapSectorTable(crossings): LapSectorTable?`：每圈各 sector 耗时（splits 对齐 sectorCount 缺段补 null）+ `bestSplitPerSector`/`bestLapPerSector` + `theoreticalTotalMs`。同源 SF 配对 + sector 窗口，rejected/null 排除，仅完整圈参与每 sector min；零 binary 读、零 repository 改、零 #16。
- **加** `LapSectorTableBlock` 表：表头 `LAP|TIME|S1..SN` + `OPT` 理论行（各 sector 最快段 Green + 总时间 + gain 小字）+ 各 valid/best 圈行（sector split 命中最快段高亮 Green、缺段 `—`、BEST 圈 Purple）；窄屏**共享横向滚动 + 固定列宽**；圈行 clickable 导航 + FileLogger 埋点保留；INVALID/INCOMPLETE 圈仍在表下方列出；无 sector 门 fallback 原 `LapRecordRow` 列表。
- `formatSectorSplit(ms)`：紧凑 `23.456`（>60s 退化 `m:ss.mmm`），Score 字体。
- 测试 `LapSectorTableTest`（6 case）。

## Impact

- 受影响：`feature/test/.../ui/tracktech/LapSessionDetailScreen.kt`；测试 swap（删 1 加 1）。
- **supersede** `lap-session-theoretical-best`（archive/2026-05-30，commit 43ac626+84c3c09）的面板 UI——该 round 的数据洞察（理论最佳圈）保留，仅呈现形态从大面板改为表。
