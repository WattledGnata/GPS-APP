# fix-altitude-encoding-contract-alignment · code review

- **日期**：2026-04-24
- **评审对象**：
  - `19e5b75` R1/R3/协议文档
  - `428a113` R2 simulator
  - `29ab58e` P2 注释残留清理
- **覆盖攻击点**：A16b
- **评审方**：codex session
- **实施方**：session 1
- **轮次**：代码落地后最终核销 review
- **结论**：✅ 准予核销 A16b

## 0. 结论摘要

实现主体认可：parser 解码、test helper 编码、协议文档、simulator 编码、字节级测试都已经按 A16b 的 ino 对齐方案落地。`openspec validate fix-altitude-encoding-contract-alignment --strict` 通过。

第 1 轮提出的 1 条 P2 已由 `29ab58e` 闭合。旧 altitude 公式 grep 现在只剩 speed 侧命中，speed 不在 A16b scope 内，允许保留。本轮无新增 P0/P1/P2。

## 1. 🔴 P0 / P1

暂无。

## 2. 🟡 P2

### Finding 1 · Kotlin 注释仍保留 v1 altitude 公式

- **位置**：
  - `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt:139-141`
  - `core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt:766`
- **状态**：✅ 已由 `29ab58e` 修复。
- **问题**：
  - parser KDoc 仍写 `bit15=0: raw / 100.0 - 500.0`、`bit15=1: ((raw & 0x7FFF) * 10) / 100.0 - 500.0`。
  - test helper KDoc 仍写 `alt + 500 = raw / 100.0 (or raw * 10 / 100.0 if overflow)`。
  - 这与当前实现和 A16b 契约不一致：bit15=0 应为 `(raw & 0x7FFF) / 10.0 - 500.0`；bit15=1 应为 `(raw & 0x7FFF).toDouble() - 500.0`，发送端 `alt >= 6053.5m` 时不乘 10。
- **影响**：
  - 行为代码本身正确，但契约说明仍是旧协议，会破坏“同一仓库内四方对齐”的可维护性。
  - 后续有人按 KDoc 改 test helper 或 parser 时，容易重新引入 A16b 已修掉的公式。
- **建议修复**：
  - 更新 parser KDoc 的 altitude encoding 段，使其与当前代码完全一致。
  - 更新 `createValidGpsData20` helper KDoc，写明 A16b/ino 编码：
    - `alt < 6053.5`: `raw = ((alt + 500.0) * 10).toInt() & 0x7FFF`
    - `alt >= 6053.5`: `raw = ((alt + 500.0).toInt() & 0x7FFF) | 0x8000`
  - 可保留 speed 的旧公式说明，因为 speed 没在 A16b scope 内变更。

## 3. 🟡 P3

暂无。

## 4. 已确认通过的部分

- `RaceChronoParser.kt` 实现已按 A16b 解码：
  - bit15=0: `/ 10.0 - 500.0`
  - bit15=1: `toDouble() - 500.0`
- `RaceChronoParserTest.kt` helper 实现已按 ino 编码，RP22/RP22b/RP22c/RP22d 覆盖低海拔、高海拔、截断区间。
- `docs/RaceChrono_BLE_Protocol.md` 正文与 Kotlin 示例代码块已更新，speed 示例未误改。
- `GpsDataGenerator.kt` 已改为 `alt < 6053.5` 阈值，bit15=1 分支不乘 10。
- `GpsDataGeneratorTest.kt` 已锁定 `100m -> 0x1770`、`10000m -> 0xA904`、`4000m -> 0x2FC8`。

## 5. 本轮执行的复核命令

```bash
openspec validate fix-altitude-encoding-contract-alignment --strict
rg -n "raw / 100\.0|raw \* 10 / 100\.0|\* 10\) / 100\.0|alt \+ 500 = raw / 100\.0" \
  core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt \
  core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt \
  docs/RaceChrono_BLE_Protocol.md \
  simulator/src/main/java \
  simulator/src/test/java
```

## 6. 给实施方的回复模板

A16b 三个 commit（`19e5b75` → `428a113` → `29ab58e`）最终核销通过 ✅。第 1 轮 P2 注释残留已闭合，旧 altitude 公式在 A16b scope 内零残留；仅 speed 侧旧公式命中，属于非本 change 范围。可将 A16b 从 🟢 pending_review 迁入 ✅ resolved，并继续推进 A26 / A41。
