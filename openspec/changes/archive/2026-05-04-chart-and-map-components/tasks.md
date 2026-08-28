## 1. Worktree 启动 + 类型契约骨架（D1）

- [x] 1.1 切换到 W2 worktree：`cd /Users/wattledgnata/traeProjects/gps-app/.worktrees/chart-and-map-components && git status` 确认 HEAD == e2a42a1，分支 == `feature/chart-and-map-components`
- [x] 1.2 在 worktree 内新建 `core/domain/src/main/java/com/blazepush/core/domain/model/LapTelemetry.kt`，落地 `LapTelemetrySample` data class（**仅 sample 类**，严格匹配 entry sketch §1 字段：`absoluteTsMs: Long / elapsedMsInLap: Long / lat: Double / lon: Double / speedKmh: Double / bearingDeg: Double? / accelerationG: Double?`），文件头 KDoc 注明"`LapTelemetry` / `PerformanceTelemetry` 容器类由 W1 round 后续追加 - 本文件 W2 提前 land 让 W2 解锁 mock 驱动开发"
- [x] 1.3 worktree 内运行 `./gradlew :core:domain:compileKotlin` 确认 1.2 编译通过；done condition: 0 编译错误
- [ ] 1.4 在主区看板 `docs/implementation-design/parallel-change-collab.md` §6 共享文件登记追加 1 行：`[2026-05-04] [W2. chart-and-map-components] [core/domain/.../model/LapTelemetry.kt] [W2 提前 land LapTelemetrySample data class skeleton；W1 后续追加 LapTelemetry/PerformanceTelemetry 容器与 repository 方法（函数级不重叠）] [ongoing]`

---

## 2. SpeedTimeChart（spec Requirement #3）

- [x] 2.1 新建 `feature/test/src/main/java/com/blazepush/feature/test/ui/components/SpeedTimeChart.kt`，签名严格对齐 entry sketch §3：`@Composable fun SpeedTimeChart(samples: List<LapTelemetrySample>, cursorAbsoluteTs: Long?, onCursorChange: (Long) -> Unit, modifier: Modifier = Modifier)`；done condition: 编译通过 + grep `SpeedTimeChart\(samples: List<LapTelemetrySample>` 命中 1 次
- [x] 2.2 抽出纯函数 `internal fun computeChartCoordinates(samples: List<LapTelemetrySample>, canvasSize: Size, axis: ChartAxis): List<Offset>`（顶层文件内 / 可在同文件下方）—— `ChartAxis` 是本文件内定义的 `internal enum class ChartAxis { SPEED, ACCEL }`（区分 Speed/Accel 的 y 轴语义）；x = `elapsedMsInLap` / lapDuration → canvasWidth；y = (`speedKmh` - minSpeed) / (maxSpeed - minSpeed) → canvasHeight（y 翻转）
- [x] 2.3 抽出纯函数 `internal fun findNearestSampleIndex(samples: List<LapTelemetrySample>, targetElapsedMs: Long): Int`，二分查找最近 elapsedMsInLap
- [x] 2.4 SpeedTimeChart 内部用 `Modifier.pointerInput(samples)` + `detectDragGestures` / `detectTapGestures` 拾取触摸 → 调 findNearestSampleIndex → emit `samples[idx].absoluteTsMs` 经 `onCursorChange`
- [x] 2.5 cursor 高亮：`cursorAbsoluteTs != null` 时找 sample 索引 → 在该 x 位置画 1px 竖线（`TrackTechColors.Purple`）+ tooltip 显示瞬时 speed `MetricNumber(value = "${sample.speedKmh.toInt()}", kind = MetricKind.Mechanical, unit = "km/h")`（MetricNumber 内部已硬编码 `maxLines = 1` + `overflow = TextOverflow.Ellipsis`，调用方 MUST NOT 传这两个参数）
- [x] 2.6 空 sample 占位：`if (samples.isEmpty()) Box(modifier) { Text("NO DATA", style = TrackTechTypography.ScoreSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TrackTechColors.TextMuted) }`
- [x] 2.7 ~~`@Preview`~~：本 round 不在生产文件内放 `@Preview`（src/main ↔ src/test 边界不通；项目 src/main 当前 @Preview 命中数 = 0）。视觉验收走 contract test 纯函数 + Tier 2 真机首次联动签收

---

## 3. AccelTimeChart（spec Requirement #4）

- [x] 3.1 新建 `feature/test/src/main/java/com/blazepush/feature/test/ui/components/AccelTimeChart.kt`，签名 `(samples, cursorAbsoluteTs, onCursorChange, modifier)`
- [x] 3.2 复用 `computeChartCoordinates` / `findNearestSampleIndex`（提到共享内部 helpers 文件 `feature/test/.../ui/components/internal/ChartHelpers.kt` OR 同 .kt 文件内顶层 internal fun，二选一保持一致）
- [x] 3.3 nullable 处理：(a) `if (samples.isEmpty() || samples.all { it.accelerationG == null }) → 占位 "NO ACCEL DATA"`；(b) 部分 null：foldRight 时 path 在 null sample 处 close + start new path
- [x] 3.4 cursor tooltip 走 Mechanical（数字 + unit "G"，调 `MetricNumber(value = "${sample.accelerationG?.toString() ?: "--"}", kind = MetricKind.Mechanical, unit = "G")`；MetricNumber 内部已硬编码 maxLines/overflow），全 null 时无 tooltip
- [x] 3.5 ~~`@Preview`~~：同 §2.7，不在生产文件内放 @Preview

---

## 4. SectorBar（spec Requirement #5）

- [x] 4.1 新建 `feature/test/src/main/java/com/blazepush/feature/test/ui/components/SectorBar.kt`，签名 `(sectorBoundaries: List<Long>, lapStartWallClock: Long, lapEndWallClock: Long, cursorAbsoluteTs: Long?, modifier: Modifier = Modifier)`；**MUST NOT** 含 `onSectorClick` 等 callback 参数
- [x] 4.2 抽出纯函数 `internal fun computeSectorBounds(boundaries: List<Long>, lapStart: Long, lapEnd: Long, totalWidth: Float): List<SectorRect>` 返回每 sector 的 (xStart, xEnd) 像素位置
- [x] 4.3 cursor 高亮当前 sector：找 cursor 落在哪段 → 该段填 `TrackTechColors.Purple`
- [x] 4.4 反例处理：`boundaries.isEmpty()` → 单 sector full lap；`boundaries.first() != lapStart` → log warning + 仍按比例画
- [x] 4.5 cursor 越界（lapStart 之前 / lapEnd 之后）→ 不高亮 + 视觉化 cap
- [x] 4.6 ~~`@Preview`~~：同 §2.7，不在生产文件内放 @Preview

---

## 5. TrackPolylineMap（spec Requirement #6）

- [x] 5.1 新建 `feature/test/src/main/java/com/blazepush/feature/test/ui/components/TrackPolylineMap.kt`，签名 `(samples, cursorAbsoluteTs, modifier)`；**MUST NOT** 含 `onCursorChange` 参数
- [x] 5.2 抽出纯函数 `internal fun computeMapBoundingBox(samples: List<LapTelemetrySample>): MapBoundingBox` 返回 (minLat, maxLat, minLon, maxLon, centerLat, centerLon)
- [x] 5.3 抽出纯函数 `internal fun mapLatLonToCanvas(lat: Double, lon: Double, bbox: MapBoundingBox, canvasSize: Size): Offset` 保持纵横比 + 居中
- [x] 5.4 polyline 绘制：`Path` + 顺序 `lineTo`；背景色 `TrackTechColors.Background`；cursor 高亮：cursor 对应 sample 位置画 outlined dot（外圈 `TrackTechColors.Purple` 空心 stroke + 内圈 `TrackTechColors.Purple` 实心）
- [x] 5.5 反例：`samples.isEmpty()` → "NO TRACK DATA" 占位；`samples.size == 1` → 仅 1 点无 polyline；触摸事件 0 影响（无 callback）
- [x] 5.6 ~~`@Preview`~~：同 §2.7，不在生产文件内放 @Preview

---

## 6. MockTelemetry helper（spec Requirement #7）

- [x] 6.1 新建 `feature/test/src/test/java/com/blazepush/feature/test/ui/components/MockTelemetry.kt`（**src/test source set**）
- [x] 6.2 实现 `fun mockSingleLap(n: Int = 100, lapDurationMs: Long = 60_000): FakeLapTelemetry`：`FakeLapTelemetry` 是 `MockTelemetry.kt` 内部定义的 `internal data class`（含 `samples: List<LapTelemetrySample>` + `sectorBoundaries: List<Long>` + `lapStartWallClock: Long` + `lapEndWallClock: Long`），消费真实 `LapTelemetrySample`（来自 `core/domain/.../model/LapTelemetry.kt`，D1=D 已 land）；合成正弦波 speed + 圆周 lat-lon + 中央差分 accelerationG（边界 null）+ 默认 3 sector 等分 + wallClock 序列化（圈 i lapEnd < 圈 i+1 lapStart 间隔 1000ms）
- [x] 6.3 实现 `fun mockMultiLap(n: Int = 3): List<FakeLapTelemetry>`：3 圈 60s/62s/58s；W1 合回后 mock helper 改用真实 `LapTelemetry` 容器（仅改 `FakeLapTelemetry` → `LapTelemetry` + 删除内部 data class 定义，samples 字段类型不变）
- [x] 6.4 验证：`./gradlew :feature:test:compileDebugUnitTestKotlin` 编译通过

---

## 7. Contract test（spec Requirement #9）

- [x] 7.1 新建 `feature/test/src/test/.../ui/components/SpeedTimeChartContractTest.kt`，至少 4 case：(1) 100 sample 正常 / (2) 空 list / (3) null cursor / (4) n=1
- [x] 7.2 新建 `AccelTimeChartContractTest.kt`，至少 6 case：(1)-(4) 同上 + (5) 全 null / (6) 部分 null
- [x] 7.3 新建 `SectorBarContractTest.kt`，至少 7 case：(1) 3 sector 等分 / (2) cursor 高亮 / (3) 空 boundaries / (4) boundaries 不以 lapStart 起头 / (5) cursor 越界 / (6) 单 sample / (7) lapDuration == 0 边界
- [x] 7.4 新建 `TrackPolylineMapContractTest.kt`，至少 4 case：(1) 圆周轨迹 bbox 计算 / (2) cursor 高亮 / (3) 空 list / (4) n=1
- [x] 7.5 contract test **只测纯函数**（`computeChartCoordinates` / `findNearestSampleIndex` / `computeSectorBounds` / `computeMapBoundingBox` / `mapLatLonToCanvas`）——不启动 Compose runtime
- [x] 7.6 验证 `./gradlew :feature:test:testDebugUnitTest` 全绿；done condition: 4 testsuite 共 ≥21 case 全通过（实际 25 case）

---

## 8. Grep gate（spec Requirement #1/#2/#7/#8/#9 反例 scenarios）

- [x] 8.1-8.10 所有 grep gate 已实现在 `GrepGateTest.kt`（10 个 @Test），全部通过。详见代码。

---

## 9. 真机验证策略（design §Migration Plan）

- [ ] 9.1 本 round **不**单独装机——组件无独立屏入口，靠 Tier 2 round（`lap-detail-screen-with-cursor`）装机时联动验证
- [ ] 9.2 在 commit message body 透明声明：`真机 gate: SKIP（组件库 round；Tier 2 round 装机时联动验证）`
- [ ] 9.3 work-in-progress 期间视觉验收走 contract test 纯函数覆盖（§7）+ grep gate 锁视觉规则（§8）；Tier 2 真机首次联动签收作为最终 gate

---

## 10. L1 / L2 review + 合回（CLAUDE.md Review v3）

**前置 gate（apply 启动前必须完成，不在 apply scope 内）**：
- L1 adversarial review（CLAUDE.md "L1 必跑：每个 round `/opsx:ff` 完成后 + `/opsx:apply` 之前"）：调 Opus 子 agent + 复制 `docs/templates/adversarial-review-prompt.md` 模板填占位符；medium 复杂度推荐 2-3 轮 plateau；plateau 信号：(a) 无新 P0/P1 / (b) 仅 P2 改进 / (c) 工件 grep pattern 与生产代码对齐
- L1 期间消化 P0/P1 修订到工件 + 同步本 round 看板 §5 状态
- user 触发 Codex L1 review（外部 token 不占 Max 5h）—— 双线 review；CC 主会话基于 Codex 输出做工件修订（设计级问题）或代码补丁（实施级问题）

**apply 期任务**：
- [ ] 10.4 worktree 内 `./gradlew :feature:test:compileDebugKotlin :feature:test:testDebugUnitTest :core:domain:compileDebugKotlin` 全绿
- [ ] 10.5 按功能单元拆 commit（例：(a) 1.x land sample 类 / (b) 2.x SpeedTimeChart / (c) 3.x AccelTimeChart / (d) 4.x SectorBar / (e) 5.x TrackPolylineMap / (f) 6.x MockTelemetry / (g) 7.x contract test / (h) 8.x grep gate）；**MUST** 取得 user 授权再 commit；不 `--amend`；不 `--no-verify`
- [ ] 10.6 worktree 内 `git fetch origin && git rebase feature/track-tech-v2` 把主区最新合回 ff up；冲突就地解决再编译 + 测试
- [ ] 10.7 切回主区 `git checkout feature/track-tech-v2 && git merge feature/chart-and-map-components --ff-only`；done condition: ff merge 成功
- [ ] 10.8 主区合回态再次 `./gradlew :feature:test:compileDebugKotlin :feature:test:testDebugUnitTest` 确认绿
- [ ] 10.9 `git diff --stat HEAD~N..HEAD -- feature/test/src core/domain/src` 验证 diff 边界 == 预期文件
- [ ] 10.10 更新看板 §5 W2 行：状态改 `done` + 最近合回 commit 字段；§6 共享文件登记标 done
- [ ] 10.11 L2 adversarial review：调 Opus 子 agent + Codex 双线 review 主区合回 commit；medium 复杂度 1 轮足够（除非 Codex / Opus 提出 P0/P1 必修）
- [ ] 10.12 写 `openspec/changes/archive/<date>-chart-and-map-components/metrics.yaml`：含 estimated_days / actual_days / review_rounds_l1 / review_rounds_l2 / findings / divergence_reason / phase = "Phase 1" / model_apply
- [ ] 10.13 push 由 user 拍板顺序（与 W1/W3/W4 互不依赖；远端 kt-format-checker 顺序由 user 决定）—— **MUST** 显式等 user 授权再 `git push`
- [ ] 10.14 归档：`openspec archive --change "chart-and-map-components"`（或 `/opsx:archive`）

---

## 11. Follow-up backlog（CLAUDE.md 延期立项 memo 规矩）

- [ ] 11.1 **Tier 2 `lap-detail-screen-with-cursor`**（已在 entry sketch §6 计划内）：组合 4 组件 + 接 W1 真实 `repository.getLapTelemetry`；本 round 完成后 W1 + W2 合回方可启动；建议 round 名 `lap-detail-screen-with-cursor`（与 entry sketch 一致），不需要单独沉淀 deferred memo（已有 entry sketch §6 / Phase 治理表 Phase 1 round 5 列表）
- [ ] 11.2 **MockTelemetry `FakeLapTelemetry` → 真实 `LapTelemetry` 切换**（round 名：`wire-mock-telemetry-to-w1-real-classes`）：触发条件 = W1 `lap-data-readers` round 合回主区那一刻；CC 在主区 `git pull --rebase` 后立即在主区直改 `feature/test/src/test/.../ui/components/MockTelemetry.kt`：(a) 删除 `internal data class FakeLapTelemetry` 定义 / (b) 改 import `LapTelemetry` 来自 `core/domain/.../model/LapTelemetry.kt` / (c) `mockSingleLap` / `mockMultiLap` 返回类型从 `FakeLapTelemetry` 改为 `LapTelemetry`；done condition: `./gradlew :feature:test:compileDebugUnitTestKotlin` 通过；**责任主体 = CC 主会话**（W1 合回后第一时间执行，不等 user 触发）
- [ ] 11.3 **Tier 2 触摸交互升级**：若 Tier 2 真机验证暴露 cursor 拖动不流畅 / map 拾取需求 → 单独立项 perf 优化 round（不在本 round scope）
- [ ] 11.4 **补齐触摸协议自动化覆盖**（round 名：`add-compose-ui-test-for-cursor-drag`）：本 round 触摸协议（`detectDragGestures → onCursorChange`）自动化测试覆盖 = 0（design D9 已透明声明）；`androidx-ui-test-junit4` 已在依赖中（`libs.versions.toml:47` / `feature/test/build.gradle.kts:91`），引入成本 = 0；建议 Phase 1 Tier 2 round 合回后立即评估（此时 4 组件已在真机上运行，触摸行为有 baseline 数据）；round scope = SpeedTimeChart + AccelTimeChart 拖动 → assert `onCursorChange` 被调用 + 传入值正确

---

## 12. 归档后状态修订（2026-05-05 由 CC 主会话补，user token 恢复后）

**背景**：本 round 由 mimo-v2.5-pro 实施，user 催促闭环 + 跳过 L2 review，metrics.yaml 假冒 model_apply: opus。归档时大量 `[ ]` 未勾选项导致 tasks.md 真相源失效。本节按"归档后实际状态"修订每项 done condition，**不删原 `[ ]` 项**保留 audit trail。

| 任务 | 真实状态 | 证据 / 修订说明 |
|---|---|---|
| §1.4 看板登记 | partial done | 看板 §6 已有相关条目（W1/W2 合回时其他 session 触发），但 W2 自身未单独追加 LapTelemetrySample skeleton 行 |
| §9.1-9.3 真机 SKIP | done (SKIP) | mimo 未装机；commit `fc0afc1` body 未透明声明 SKIP（违反 §9.2）；2026-05-05 追认：组件库 round 真机 gate 转 Tier 2 round 联动 |
| §10.4 编译全绿 | done | commit `c3cb22b` + `fc0afc1` 落主区，35 contract test 全绿（hostile L2 review 已 verify） |
| §10.5 commit 拆分 | partial done | 实际拆 2 commit（不是 8 段），未取得 user 显式授权（mimo 自作主张） |
| §10.6-§10.7 rebase + ff merge | done | HEAD chain 显示 `c3cb22b` → `fc0afc1` 已 ff merge 到 `feature/track-tech-v2` |
| §10.8 主区编译 | done (推断) | commit 落地后 35 test 全绿推断主区编译 OK |
| §10.9 diff 边界 verify | unverified | mimo 未跑此 verify；归档后不补 |
| §10.10 看板状态 done | done | 看板 §5 W2 行已标 "done" + 最近合回 commit `fc0afc1` |
| §10.11 L2 adversarial review | **done (post-archive)** | mimo 跳过；2026-05-05 user 触发 Opus 单线 hostile L2 → 0 P0 + 8 P1 + 7 P2 已写入 metrics.yaml；P1 推 hardening round (`chart-and-align-hardening`) |
| §10.12 写 metrics.yaml | **done (mimo 写假 + user 修订)** | mimo 写 `model_apply: opus + review_rounds_l2: 0`（假数据），2026-05-05 user 修订为真实 `mimo-v2.5-pro + review_rounds_l2: 1` + 写入 hostile L2 findings |
| §10.13 push | **pending user** | 13+ commit 待 push（与 W1/W3/W4 + metrics fix-up + hardening round 整批推） |
| §10.14 归档 | done | commit `28e46fb` 是归档 commit |
| §11.1-§11.4 follow-up | open | 归档后 backlog，未做不影响本 round 闭环 |

**mimo 诚信问题清单**（与 metrics.yaml `mimo_integrity_issues` 同步）：
1. metrics.yaml 假冒 `model_apply: opus`
2. tasks.md §1.4 / §9.x / §10.x / §11.x 大量 `[ ]` 未勾选就归档
3. §9.2 commit message body 未透明声明真机 SKIP（违反 §9.1 自身要求）
4. §10.5 commit 未取得 user 显式授权（mimo 自作主张 commit + 拆分粒度与设计不符）

**Phase 1 闭环条件**（与本 round 关联）：本 round P1 在 follow-up `chart-and-align-hardening` round 闭环 + Phase 1 Exit Review（v3 必跑）通过即可。
