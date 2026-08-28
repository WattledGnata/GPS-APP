# lap-session-detail-screen 规格增量

## ADDED Requirements

### Requirement: 圈列表屏顶部显示理论最佳圈

`LapSessionDetailScreen` 在有 sector 门且有完整圈时 SHALL 于顶部显示理论最佳圈（各 sector 取所有完整圈最快段拼接）+ 每 sector 最快圈号。

实现 MUST 满足：

1. **数据源**：从屏已加载的 crossings 纯函数派生，MUST NOT 新增 binary 读 / repository 方法 / 改 `getLapTelemetry` 契约。
2. **配对同源**：每圈 sector 派生用 accepted StartFinish wallClock 配对窗口 `[lapStart, lapEnd)` + 窗口内 accepted Sector wallClock，与 `deriveDetailMetrics` / `getLapTelemetry` 同源（lapNumber=idx+1）。
3. **拼接**：每 sector 取所有"完整圈"（split 数 == 最大段数）中最快段，sum = 理论最佳圈总时间；记录每 sector 最快圈号。
4. **退化为 null**：`< 2` 个 accepted StartFinish、或每圈 sector 段数 `< 2`（无 sector 门）、或无完整圈 → 返回 null，面板 MUST NOT 显示。
5. **rejected/null-wallClock 排除**：rejected 或 wallClock 为 null 的 crossing MUST NOT 参与。
6. **视觉**：时间字符串走 Score 字体（MUST NOT DSEG7/Mechanical）；面板内每 Text 单行 + Ellipsis。

#### Scenario: 两圈各取最快 sector 拼接
- **GIVEN** crossings：Lap1 splits `[500,1300,1200]`、Lap2 splits `[400,1500,1100]`（SF@1000/4000/7000 + 各圈 2 sector 门）
- **WHEN** 调 `computeTheoreticalBest(crossings)`
- **THEN** `totalMs == 2800`（400@L2 + 1300@L1 + 1100@L2）
- **AND** `sectors[0].lapNumber == 2`、`sectors[1].lapNumber == 1`、`sectors[2].lapNumber == 2`

#### Scenario: 反例——rejected sector 不参与（不改变段数基准）
- **GIVEN** 同上但 Lap2 多一个 `accepted=false` 的 Sector@4100
- **WHEN** 调 `computeTheoreticalBest`
- **THEN** `totalMs == 2800`（rejected 4100 被排除，L2 仍 3 段完整圈）
- **AND** 若实现误纳入 rejected，L2 变 4 段 → 非完整圈被排除 → 理论最佳退化成 L1=3000 → 断言 fail

#### Scenario: 反例——无 sector 门返回 null（面板不显示）
- **GIVEN** 只有 StartFinish crossing（无 Sector），每圈 1 段
- **WHEN** 调 `computeTheoreticalBest`
- **THEN** 返回 `null`
- **AND** `TheoreticalBestPanel` MUST NOT 渲染
