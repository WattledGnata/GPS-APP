# fix-active-lap-distance-accumulator spec review

- **日期**：2026-04-25
- **评审对象**：`openspec/changes/fix-active-lap-distance-accumulator/specs/active-lap-distance-accumulator/spec.md` V1（6 Requirements × 16 Scenarios）
- **覆盖攻击点**：A22
- **评审方**：codex session
- **实施方**：claude session
- **轮次**：第 3 轮 spec review（proposal + design 已放行）
- **结论**：🔴 暂不放行进入 tasks
- **前置条件**：P2 全闭合后重提 spec mini review

## 0. TL;DR

spec 主体质量不错：5 类 engine 返回路径、producer/consumer 边界、UI 零残留、16ms smoke、A56 边界都已经入网。  

但 V1 还有两个可执行性问题：A56 关键词 grep 过宽，会被既有 `telemetry` / Room 代码误伤；`LapRecord` 路径仍写成 `core/domain` 或等价路径，当前真实文件在 `feature/test`，tasks 阶段容易跑错位置。

## 1. 🟡 P2 Findings

### Finding 1 — [P2] A56 持久化关键词 grep 会误伤既有 UI/Room 代码

- **位置**：`openspec/changes/fix-active-lap-distance-accumulator/specs/active-lap-distance-accumulator/spec.md:164-168`
- **问题**：Scenario 写“grep 改动文件中 `Room|@Entity|@Dao|telemetry|chunk\.write|persistence|database` 零命中”。但 A22 必改 `LapDebugExecutionScreen.kt`，该文件现有 UI 参数/函数已经含 `telemetry`；仓库既有 `AppModule.kt` 也含 `Room` / `database`。如果 tasks 按“改动文件全文”或相关目录全文 grep，会稳定假红。
- **证据**：当前仓库中 `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/LapDebugExecutionScreen.kt` 已有 `telemetry: LapDebugTelemetry` / `TelemetryCard(...)`；`feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt` 已有 Room database builder。
- **要求**：把该 Scenario 收窄为“新增行 / commit diff added lines 零命中”，例如只检查 `git diff --cached -U0` 或最终 commit diff 中以 `+` 开头的新增内容；并把 `telemetry` 关键词改成更贴近 A56 的持久化语义，如 `telemetryChunk|telemetry_payload|chunk\\.write|Room|@Entity|@Dao|persistence|database`。若仍保留裸 `telemetry`，必须明确白名单 UI 展示命名，不得扫描整文件。

### Finding 2 — [P2] LapRecord 路径未绑定当前真实位置

- **位置**：`openspec/changes/fix-active-lap-distance-accumulator/specs/active-lap-distance-accumulator/spec.md:158-162`
- **问题**：Scenario 写 `core/domain/src/main/java/.../LapRecord.kt 或等价路径`，但当前真实 `LapRecord.kt` 在 `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapRecord.kt`。A22 impact 也声明 `core/domain` 零改动；继续把 `core/domain` 写在 Scenario 里，会让 tasks 作者补 grep 时跑错模块或误以为存在 domain 级 LapRecord。
- **要求**：把 Scenario 路径直接改为当前真实路径：`feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapRecord.kt`。如需保留“等价路径”弹性，也应写成“以 `rg --files | rg 'LapRecord\\.kt$'` 发现的 feature/test laptiming 文件为准”，但本 change 最好直接钉死真实路径。

## 2. 🟢 已充分认可

- R3 对路径 (a)-(f) 的 Scenario 覆盖完整，尤其是 no target gate / sector rejected / sector accepted 三条能防止 `activeLapWithDistance` 漏带。
- R2 把 `session.samples.lastOrNull()` 与 `previousSample` 参数的语义来源区分写入 spec，承接 design 决策。
- R4 UI consumer-only 的源码零残留门槛方向正确，且保留了 `samples.lastOrNull()?.timestampMillis` 的 O(1) elapsed 例外。
- R5 `<16ms` smoke 与 proposal/backlog 口径一致。

## 3. 给实施方的回复模板

spec V1 暂不放行进入 tasks。请先修 2 点：

1. A56 持久化关键词 grep 必须改成“新增 diff 行”级别，不能扫改动文件全文；裸 `telemetry` 会误伤现有 UI telemetry 命名，需要收窄或白名单。
2. `LapRecord` Scenario 路径改为当前真实文件 `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapRecord.kt`，不要继续写 `core/domain`。

修完后重提 spec mini review；无需 patches 清单。

## 4. Round 2 mini review

### 4.1 Finding closure

- Finding 1 closed：A56 持久化关键词门槛已改为只扫 `git diff <baseline>..HEAD` 的新增行，并排除 `+++` 文件头；关键词也从宽泛 `Room/database/telemetry/persistence` 收窄为 `@Entity` / `@Dao` / `@Database` / `RoomDatabase` / `chunkWrite` / `persistDistance` / `@Insert` / `@Query` 等新增持久化结构信号。
- Finding 2 closed：`LapRecord` Scenario 已改为当前真实路径 `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/LapRecord.kt`，并限定在该 data class 字段列表内 grep `distanceMeters|distanceMetersSinceStart`。
- `openspec validate fix-active-lap-distance-accumulator --strict` 继续通过。

### 4.2 Verdict

spec review 通过。可以进入 tasks。
