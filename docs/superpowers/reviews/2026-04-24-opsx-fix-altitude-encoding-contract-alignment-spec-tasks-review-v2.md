# fix-altitude-encoding-contract-alignment · 第 2 轮 mini review

- **日期**：2026-04-24
- **评审对象**：
  - `openspec/changes/fix-altitude-encoding-contract-alignment/proposal.md` v2
  - `openspec/changes/fix-altitude-encoding-contract-alignment/specs/race-chrono-parser/spec.md` v2
  - `openspec/changes/fix-altitude-encoding-contract-alignment/tasks.md` v2
- **覆盖攻击点**：A16b
- **评审方**：codex session
- **实施方**：session 1
- **轮次**：第 2 轮 mini review（复核第 1 轮 4 条 blocker）
- **结论**：🟡 暂不进入 `/opsx:apply`，修 1 个 P2 后可放行
- **前置条件**：P2-1 修订并重新 `openspec validate fix-altitude-encoding-contract-alignment --strict`

## 0. 结论摘要

上一轮 4 条 blocker 大体闭合：

- `6053m` 边界已改成 `6054m / 0x999A`，数值链正确。
- 协议文档示例代码块已纳入 tasks。
- simulator 字节级单测已从可选改必做。
- `4000m / 0x2FC8 / 723.2m` 截断区间测试已从可选改必做。

剩余问题是一个文字契约冲突：R2 仍笼统写 “simulator 编码被 parser 解码后还原原 alt”，但 `4000m` Non-goal 区间明确不能还原原 alt。需要把 R2 的精确往返收窄到“截断区间外”。

## 1. 🔴 P0 / P1

暂无。

## 2. 🟡 P2

### P2-1 · R2 精确往返 MUST 未排除截断区间

- **位置**：`spec.md:84-86`，`tasks.md:158-169`，`proposal.md:221-222`
- **问题**：spec R2 写“simulator 对 altitude 的编码 MUST 与 parser 解码形成精确往返（parser 解码 simulator 编码的字节还原原 alt）”。但 v2 tasks 已把 `alt=4000m` 列为 simulator 字节级必测，并明确它应编码为 ino 截断字节 `0x2F 0xC8`；parser 解码该字节得到 `723.2m`，不能还原 `4000m`。这与 R5 Non-goal 是同一个事实，R2 的 MUST 需要同步收窄。
- **后果**：实施方按 R2 原文理解会尝试让 simulator/parser 对 `4000m` 精确往返，和 Non-goal 测试 `assertNotEquals(4000.0, result.altitude)` 正面冲突。
- **修订建议**：
  - `spec.md:84-86` 改为：`simulator 对 altitude 的编码 MUST 与 parser 解码在截断区间外形成精确往返；对 [2776.7m, 6053.5m] 区间，simulator MUST 与 ino 一致产生截断字节，parser 解码为截断后的值，不承诺还原原 alt。`
  - `tasks.md:158-161` 的 “E2E 往返一致性验证” 改名为 “E2E 截断区间外往返一致 + 截断区间字节对齐验证”。
  - `proposal.md:221-222` 仍写 simulator 无 altitude 单测、靠 E2E 兜底；这已被 v2 tasks 推翻。请改成“新增 GpsDataGeneratorTest 字节级单测覆盖 100m / 10000m / 4000m”。

## 3. 🟡 P3

### P3-1 · “6053m 及以上”表述可更精确

- **位置**：`spec.md:29`，`proposal.md:151-172`，`tasks.md:39-40`，`tasks.md:101-103`
- **问题**：多处写 bit15=1 范围 “6053m 及以上 / 6053m ~ 33267m”。从 decoded value 看 `6053.5m` 会量化回 `6053m`，所以不是硬错；但从发送端判定看真正触发条件是 `alt >= 6053.5m`。
- **建议**：统一写成“发送端 alt >= 6053.5m 触发 bit15=1；解码值精度 1m，最小可回读整数为 6053m”。这样能减少下次又拿 `6053m` 当 bit15=1 输入值的风险。

## 4. proposal / 上游遗留

`proposal.md:287` 仍写 R4 simulator 字节测试“通过 GpsDataGeneratorTest 或 E2E 间接验证”，但 tasks 已改为 GpsDataGeneratorTest 必做。建议和 P2-1 一起改成必做，避免 proposal / tasks 分裂。

## 5. 🟢 已充分认可

- `6054m / 0x999A / v1 解 155.4m` 修正正确。
- `4000m / 0x2FC8 / 723.2m / assertNotEquals(4000.0)` 是很好的 Non-goal 机器锚点。
- 协议文档正文 + 示例代码块双改是必要且充分的。
- simulator 字节级测试升为必做，覆盖了 R2 真正的风险点。

## 6. 给实施方的回复模板

第 2 轮只剩 1 个 P2：R2 的“simulator → parser 精确往返”必须排除 `[2776.7m, 6053.5m]` 截断区间；4000m 应只承诺字节与 ino 对齐并解出截断值 723.2m，不承诺还原 4000m。顺手把 proposal 里“simulator 无 altitude 单测 / 靠 E2E 兜底”的旧句子改成 `GpsDataGeneratorTest` 必做。修完并 `openspec validate --strict` 通过后，我认为可以放行 `/opsx:apply`。
