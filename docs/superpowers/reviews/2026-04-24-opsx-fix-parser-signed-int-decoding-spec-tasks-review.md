# fix-parser-signed-int-decoding spec/tasks review

- **日期**：2026-04-24
- **评审方**：codex
- **评审对象**：
  - `openspec/changes/fix-parser-signed-int-decoding/proposal.md`
  - `openspec/changes/fix-parser-signed-int-decoding/specs/parser-signed-int-decoding/spec.md`
  - `openspec/changes/fix-parser-signed-int-decoding/tasks.md`
- **结论**：🔴 暂不放行，需修 1 个 P1 + 2 个 P2 后重提 mini review

## 0. TL;DR

lat/lon signed int32 的 scope 切分方向正确：本 change 只修 RP16/RP19，RP22 altitude
另起 `fix-altitude-encoding-contract-alignment`，这个决策我认可。

阻塞点在执行闭环：

1. tasks §3.6/3.7 会把 A16 整体迁到 🟢，但 A16 原条目仍包含 RP22 altitude，不能让未解决风险离开 pending。
2. `@Ignore` grep 会被文件头 `// @IgnoreFormatCheck` 污染，实施后会误判失败。
3. 下游消费者 grep 会扫进 build 产物且 pattern 太宽，当前环境已实测大量误命中，不能作为机器门槛。

`openspec validate fix-parser-signed-int-decoding --strict` 已复核通过。

## 1. Findings

### P1-1 · A16 backlog 迁档会把未解决的 RP22 altitude 风险一起迁走

- **位置**：`openspec/changes/fix-parser-signed-int-decoding/tasks.md:103-117`
- **问题**：
  - A16 原 backlog 条目明确包含 RP16 / RP19 / RP22 三条 `@Ignore`，核销条件也写了
    "lat/lon signed int32、altitude overflow 编码"、"RP16 / RP19 / RP22 三条
    `@Ignore` 去掉"、"海拔 > 3276.7m"。
  - 本 change scope 正确收敛到 RP16/RP19，但 tasks §3.6 要把 A16 条目从 🔴 整体
    搬到 🟢 `pending_review`，§3.7 还要求附录 A16 状态列改为 🟢。
  - 即使行内备注 "altitude 移交独立 change"，看板主状态仍会显示 A16 已进入核销队列，
    这违反核销闭环原则：未完成的 RP22/altitude 风险不能离开 pending，除非先拆出新的
    pending 攻击点并显式承接。
- **建议修订**：
  - 不要把原 A16 整体迁 🟢。
  - 二选一：
    1. 将 A16 拆分为 `A16a lat/lon signed int32` 与 `A16b altitude contract alignment`；
       本 change 完成后只迁 A16a，A16b 留 🔴 并绑定
       `fix-altitude-encoding-contract-alignment`。
    2. 保留 A16 在 🔴，在条目内追加 "lat/lon 子项已由 commit <hash> 完成，RP22
       altitude 仍 pending" 的子状态；待 altitude change 完成后再迁整条 A16。

### P2-1 · `@Ignore` 数量门槛会被 `@IgnoreFormatCheck` 误污染

- **位置**：
  - `openspec/changes/fix-parser-signed-int-decoding/tasks.md:74-78`
  - `openspec/changes/fix-parser-signed-int-decoding/specs/parser-signed-int-decoding/spec.md:170-175`
- **问题**：
  - 当前 `RaceChronoParserTest.kt` 文件头已有 `// @IgnoreFormatCheck`。
  - 现状实测 `grep -c "@Ignore" ...` 输出 `4`，其中 1 个不是 JUnit `@Ignore`。
  - 实施方删除 RP16/RP19 两个 JUnit `@Ignore` 后，裸 grep 仍会输出 `2`（文件头
    `@IgnoreFormatCheck` + RP22），不会等于文档要求的 `1`，机器门槛必失败。
- **建议修订**：
  - 把命令改成只统计注解行，例如：
    ```bash
    grep -c "^[[:space:]]*@Ignore\\b" core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt
    ```
  - spec 的 Scenario 也同步改成统计 JUnit `@Ignore` 注解，而不是任意 `@Ignore` 字符串。

### P2-2 · 下游消费者 grep 会扫进 build 产物且 pattern 过宽，当前实测非零命中

- **位置**：`openspec/changes/fix-parser-signed-int-decoding/tasks.md:14-19`
- **问题**：
  - tasks §1.1 要求：
    ```bash
    grep -Rn "latitude\s*<\s*0\|longitude\s*<\s*0\|latitude.*negative\|lat.*-1" core/ feature/ app/ 2>/dev/null
    ```
    并预期零命中。
  - 当前环境实测该命令会扫进 `core/**/build`、`feature/**/build`、`app/**/build`，
    输出大量 generated XML / binary matches。
  - 即使排除 build，`lat.*-1` 仍误命中 `latitude + 1e-12` 这类测试代码，因为
    `1e-12` 内含 `-1`。这不是 "负 lat/lon = 异常" 假设。
- **建议修订**：
  - 用 `rg` 并排除 build 产物，只扫源码：
    ```bash
    rg -n "latitude\\s*<\\s*0|longitude\\s*<\\s*0|latitude\\s*[!=]=\\s*-|longitude\\s*[!=]=\\s*-" \
      core/*/src feature/*/src app/src
    ```
  - 或把 §1.1 降级为人工审计项：允许命中测试 fixture，但必须确认没有生产代码把负经纬度当异常过滤。

## 2. P3 / 文案修订建议

- `proposal.md:241` 行为表 After 写成 "两轴都正 ✓"，应为 "两轴都正确为负值 ✓"。
- `spec.md:43` 纬度 Requirement 的 ino 编码示例写成 `(int32_t)(alt * 10000000.0)`，
  应为 latitude/lat，不要把 altitude 词带进 lat/lon spec。
- `proposal.md:250` 风险表说下游 grep 见 tasks §2.3，实际在 tasks §1.1。
- `tasks.md:88-89` 当前 `$(npm config get prefix)/bin/openspec` 在本机可用，但建议按项目
  最新约定写成 `openspec validate fix-parser-signed-int-decoding --strict`，避免继续传播
  环境路径绑定。

## 3. 复核记录

- `openspec validate fix-parser-signed-int-decoding --strict`：PASS
- `which openspec`：`/Users/wattledgnata/.local/bin/openspec`
- `npm config get prefix`：`/Users/wattledgnata/.local/opt/node-v22.22.1-darwin-arm64`
- `grep -c "@Ignore" core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt`：当前输出 `4`
- `grep -c "^[[:space:]]*@Ignore\\b" core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt`：当前输出 `3`
- tasks §1.1 原 grep：当前实测大量误命中 build 产物；排除 build 后仍误命中
  `GateCrossingDetectorTest.kt` 的 `1e-12` fixture

## 4. 核销建议

暂不进入 `/opsx:apply`。请先修：

1. A16 backlog 迁档策略：不能把未完成的 RP22 altitude 从 pending 移走。
2. `@Ignore` grep 统计注解行。
3. 下游负经纬度 grep 排除 build 并收窄 pattern。

修订项共 3 条，未达到 5 条，不单独产出 `review-vN-patches.md`。

---

## 5. mini review：第二轮修订复核

- **日期**：2026-04-24
- **评审对象**：第二轮修订后的 `proposal.md` / `spec.md` / `tasks.md`
- **结论**：🟡 还差 1 个 P2 文案闭环；主方案已认可，但暂不放行 `/opsx:apply`

### 5.1 上轮 finding 关闭情况

| Finding | 复核结论 |
|---|---|
| P1-1 A16 半数修复不能整体迁 pending_review | 主体已修。tasks §3.6-3.9 改为 A16 → A16a/A16b 拆条；A16a 随本 change 迁 🟢，A16b 留 🔴 并绑定 altitude 独立 change |
| P2-1 `@Ignore` grep 被 `@IgnoreFormatCheck` 污染 | 已修。tasks §2.5 与 spec Scenario 改为 `grep -c "^[[:space:]]*@Ignore\\b"`，只统计 JUnit 注解行 |
| P2-2 下游消费者 grep 误命中 | 已修。tasks §1.1 改为 `rg` 扫 `core/*/src feature/*/src app/src`，并收窄到显式负值比较 |

### 5.2 新发现

#### P2-3 · commit 策略仍写 "A16 迁 🟢"，与拆条策略冲突

- **位置**：`openspec/changes/fix-parser-signed-int-decoding/tasks.md:225`
- **问题**：
  - tasks §3.6-3.9 已正确要求拆成 A16a/A16b，并只迁 A16a。
  - 但 §4 commit 策略仍写："backlog 迁档（本地文档，不进 git）：A16 迁 🟢，附 commit 1 hash"。
  - 这会在最后执行 commit/backlog 阶段重新引入上轮 P1 的歧义：实施方可能按 §4 把整条 A16 迁出 pending。
- **建议修订**：
  - 改为："backlog 迁档（本地文档，不进 git）：按 §3.6 拆分 A16；A16a 迁 🟢 并附 commit 1 hash；A16b 保持 🔴，绑定 `fix-altitude-encoding-contract-alignment`。"

### 5.3 复核记录

- `openspec validate fix-parser-signed-int-decoding --strict`：PASS
- `grep -c "^[[:space:]]*@Ignore\\b" core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt`：当前输出 `3`（实施前预期值；删除 RP16/RP19 后应为 `1`）
- `rg -n "latitude\\s*<\\s*0|longitude\\s*<\\s*0|latitude\\s*[!=]=\\s*-|longitude\\s*[!=]=\\s*-" core/*/src feature/*/src app/src`：当前无输出，exit 1，符合零命中预期

### 5.4 核销建议

修 `tasks.md:225` 一行后可直接进入 `/opsx:apply`；无需再产出 patches 清单。

---

## 6. mini review：tasks.md:225 收尾复核

- **日期**：2026-04-24
- **评审对象**：`tasks.md:225-227`
- **结论**：✅ 通过，可进入 `/opsx:apply`

### 6.1 上轮 finding 关闭情况

| Finding | 复核结论 |
|---|---|
| P2-3 commit 策略仍写 "A16 迁 🟢" | 已修。commit 策略现明确按 §3.6 拆分 A16；只将 A16a 迁 🟢 并附 commit hash；A16b 保持 🔴，绑定 `fix-altitude-encoding-contract-alignment` |

### 6.2 复核记录

- `openspec validate fix-parser-signed-int-decoding --strict`：PASS
- `tasks.md:225-227`：已明确 "A16a 迁 🟢 / A16b 保持 🔴"
- `rg -n "A16 迁|A16 整条|只.*A16a|A16b 保持" tasks.md`：残留的 "A16 整条" 均位于拆条原则 / 删除原条目 / grep 自检语境中，无迁档冲突

### 6.3 放行结论

`fix-parser-signed-int-decoding` 三工件当前可放行实施。实施阶段按 tasks 执行：

1. parser 两处 lat/lon unsigned mask 删除。
2. RP16/RP19 解封 + 2 条边界测试。
3. backlog 拆 A16a/A16b；只迁 A16a，A16b 留 🔴。
4. 1 个代码 commit。

---

## 7. final code review：commit `f097478` 核销

- **日期**：2026-04-24
- **评审对象**：commit `f097478`（`fix(bluetooth): 战役 D 尾巴 A16a lat/lon signed int32 解码修复`）
- **结论**：✅ 通过，A16a 可从 🟢 `pending_review` 迁入 ✅ `resolved`

### 7.1 复核结论

| 核销条件 | 复核结论 |
|---|---|
| 删除 parser line 178 / 185 unsigned mask | 已完成。`currentLatitude = latInt / 10_000_000.0`，`currentLongitude = lonInt / 10_000_000.0` |
| RP16 / RP19 去 `@Ignore` | 已完成。`RP16_parseLatitude_negative` / `RP19_parseLongitude_negative` 已恢复活跃测试 |
| 新增 lat/lon 边界测试 | 已完成。新增双负坐标 + 极地 / 反子午线极端负值两条测试 |
| RP22 altitude 留给 A16b | 已保持。JUnit `@Ignore` 仅剩 RP22，A16b 仍为 🔴 pending |

### 7.2 验证记录

- `openspec validate fix-parser-signed-int-decoding --strict`：PASS
- `grep -c "^[[:space:]]*@Ignore\\b" core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt`：`1`
- `./gradlew :core:bluetooth:testDebugUnitTest --tests "*RaceChronoParserTest*"`：BUILD SUCCESSFUL

### 7.3 backlog 同步

- A16a 已迁入第五节 ✅ resolved，绑定 commit `f097478`
- A16b 保持第一节 🔴 pending，绑定独立 change `fix-altitude-encoding-contract-alignment`
