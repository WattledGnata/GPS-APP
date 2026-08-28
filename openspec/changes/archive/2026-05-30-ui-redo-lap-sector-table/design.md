## Context

理论最佳圈大面板被否，改 RaceChrono/AiM 风格圈列表表。窄屏（vivo V2405A）5+ 列是关键约束。road-test-first。

## Decisions

### Decision 1：呈现 = 圈列表做成表（lap 行 × sector 列），非独立面板 / 非紧凑指标
- 用户在 紧凑指标 / 表 / 压缩面板 三选中选**表**（信息密度最高、最符合赛道分析习惯：一眼看出每段强弱 + 哪圈最快）。

### Decision 2：窄屏 = 横向滚动 + 各行共享 scrollState + 固定列宽
- **选**：单一 `rememberScrollState()`，表头 / OPT 行 / 各圈行的 `Row` 都挂 `Modifier.horizontalScroll(hScroll)` → 横滚严格同步；固定列宽（LAP 48 / TIME 76 / Sector 60 dp）。
- **Alt A（拒绝）**：自适应压缩列宽（weight）→ 5+ 列在窄屏挤成换行/截断，违反 V2 单行约束。
- **Alt B（拒绝）**：第三方 AutoSizeText 自适应字号 → V2 MUST NOT（foundation 不支持 + Ellipsis 已是 fallback）。
- **rationale**：固定列宽 + 横滚保证任意 sector 数都不挤压不换行；cell 内 Text 被 bounded width 测量后 maxLines=1+Ellipsis 生效。

### Decision 3：数据 = 复用屏已加载 crossings 纯函数（同 lap-session-theoretical-best Decision 1）
- 零 binary 读、零 repository 改、零 #16。

## Risks

- **横滚可发现性**：窄屏无滚动条提示，用户可能不知能右滑 → follow-up 可加 edge fade/渐变遮罩（本 round 未加，避免 scope 膨胀；真机若反馈再补）。
- **极端列宽截断**：sector 跨分钟 `1:05.234`（8 字符）在 60dp 可能贴边 → 真机调列宽（64-66dp）。mitigation：先固定值，真机迭代。
- **INVALID 圈**：不进表（无干净窗口）→ 仍以原 LapRecordRow 在表下方列出，不丢。
