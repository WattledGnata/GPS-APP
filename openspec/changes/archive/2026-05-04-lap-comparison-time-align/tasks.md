## 1. Pre-flight 与 worktree 设置

- [ ] 1.1 **在主区**（cwd `/Users/wattledgnata/traeProjects/gps-app`）跑 `git -C .worktrees/lap-comparison-time-align status` 确认 worktree 在 `e2a42a1` 干净基线
- [ ] 1.2 **在主区**（cwd `/Users/wattledgnata/traeProjects/gps-app`）通读 `docs/design/phase-1-entry-data-contracts.md` §1 + §4 + §6 + §7，将 design.md D6 inline 字段集合 + entry sketch §1 原文 + （若 W1 worktree 已 commit）`.worktrees/lap-data-readers/core/domain/src/main/java/com/blazepush/core/domain/model/LapTelemetry.kt` 三方对照——逐字段（sessionId / lapIndex / lapStartWallClock / lapEndWallClock / lapDurationMs / samples / sectorBoundaries / trackId / trackNameSnapshot；以及 LapTelemetrySample 的 absoluteTsMs / elapsedMsInLap / lat / lon / speedKmh / bearingDeg / accelerationG）名称、类型、nullable 标记 100% 一致；任意字段不一致 STOP 并与 user 同步。**STOP 后的 3 路决策树**：
    - (a) **W1 已删 accelerationG 字段**（认为 "W3 派生" 不需要预留）→ 同步删 design D6 inline 字段集合 / 占位 LapTelemetry.kt / spec D2 字段表中 accelerationG 行 / spec scenarios "nullable 字段..." 三条；同步 user 后再继续
    - (b) **W1 改其他字段**（命名 / 类型 / nullable 标记不同）→ user 决定改占位匹配 W1，or 改 design D6 字段集合匹配 W1，or 让 W1 回退到 entry sketch 原版
    - (c) **W1 仅命名差异（如 sessionId 改 lapId）**→ 占位**以 entry sketch 为准**；W1 合回时 git rm 占位（同 design D6 rebase 流程），W3 worktree 内代码 import W1 实际版本前先 grep verify 命名
- [ ] 1.3 **在主区** `git -C . log --oneline feature/lap-data-readers ^feature/track-tech-v2 2>/dev/null | head -5` 检查 W1 是否已合回主区（若已合回 → 跳过 §2 占位类型新建，直接 import W1 已落地版本）

## 2. core/domain 占位类型（W1 未合回时执行；若已合回跳过）

- [ ] 2.1 **在 worktree** `.worktrees/lap-comparison-time-align/` 内新建 `core/domain/src/main/java/com/blazepush/core/domain/model/LapTelemetry.kt`，按 design D6 inline 字段集合 100% 复刻 `data class LapTelemetry` + `data class LapTelemetrySample`，**字段顺序与命名 100% 对齐**
- [ ] 2.2 文件顶部加 KDoc 注明：`// W3 round 占位类型，W1 lap-data-readers 合回后由 worktree rebase 期 git rm 删除（详见本 round design D6 + tasks §7.3 rebase 流程）`
- [ ] 2.3 worktree 内跑 `./gradlew :core:domain:compileKotlin` 验证编译通过

## 3. core/domain LapAlignment usecase 实现

- [ ] 3.1 **在 worktree** 新建 `core/domain/src/main/java/com/blazepush/core/domain/usecase/LapAlignment.kt`，结构（按 design D7 return type 形态）：
  - 顶部常量：`private const val EARTH_RADIUS_M = 6378137.0`、`private const val DEG_TO_RAD = Math.PI / 180.0`
  - public `data class LapAlignmentResult(samplesPerLap, distanceStepMeters, refTotalDistMeters, gridSize, referenceLapIndex)` + `companion object { val EMPTY = ... }` + `fun gridIndexFor(distanceMeters: Double): Int` + `fun distanceAtGridIndex(gridIndex: Int): Double`
  - `object LapAlignment {}` 容器
  - public `fun alignByDistance(laps: List<LapTelemetry>, referenceLapIndex: Int, distanceStepMeters: Double = 5.0): LapAlignmentResult`
  - private `fun computeCumulativeDistances(samples: List<LapTelemetrySample>, cosLat0: Double): DoubleArray`
  - private `fun resampleByGrid(samples: List<LapTelemetrySample>, cumulative: DoubleArray, grid: DoubleArray, lapStartWallClock: Long, fallbackRefSamples: List<LapTelemetrySample>?): List<LapTelemetrySample>`
  - private `fun interpolate(s0: LapTelemetrySample, s1: LapTelemetrySample, alpha: Double, lapStartWallClock: Long): LapTelemetrySample`
- [ ] 3.2 边界路径完全按 design D5 + spec scenarios 锁死：
  - `laps.isEmpty()` → return `LapAlignmentResult.EMPTY`
  - `referenceLapIndex !in laps.indices` → return `LapAlignmentResult.EMPTY`
  - `distanceStepMeters <= 0.0` → return `LapAlignmentResult.EMPTY`
  - 参考圈 `samples.size < 2` → return `LapAlignmentResult.EMPTY`（**统一返回 EMPTY，不做单元素降级**——避免外层长度 != laps.size 风险；MUST NOT 用 `?: continue` 类语法陷阱）
  - 参考圈累计距离 `refTotalDist == 0.0`（所有 sample 同位置）→ return `LapAlignmentResult.EMPTY`
- [ ] 3.3 距离累计：用 `cosLat0 = cos(laps[refIdx].samples[0].lat * DEG_TO_RAD)` 提前算 1 次；`Δd = sqrt((Δlon * cosLat0 * DEG_TO_RAD * R)² + (Δlat * DEG_TO_RAD * R)²)` 累计到 `DoubleArray`
- [ ] 3.4 网格生成：`gridSize = floor(refTotalDist / step).toInt() + 1`；`grid = DoubleArray(gridSize) { it * step }`
- [ ] 3.5 重采样查表（按 spec "累计距离含重复值 SHALL 不返回 NaN" requirement）：
  - 用 `Arrays.binarySearch(cumulative, d*)` 找位置；处理负 insertion point：`-(insertionPoint + 1)`
  - 首尾 clamp：`d* < d_0 == 0` → 返回 `samples[0]`；`d* > d_{n-1}` → 返回 `samples[n-1]`
  - **重复值防护**：找到候选区间 `[d_k, d_{k+1}]` 后，若 `d_k == d_{k+1}`（区间长度 0）→ α 直接 0（取 `s_k`），**MUST NOT** 做除法 `(d* - d_k) / (d_{k+1} - d_k)` 触发 NaN
  - **稳定 index**：若二分查找命中重复值（`cumulative[k] == d*`），sweep 向左找到该重复区间最小 index `k_min`，返回 `samples[k_min]`（spec scenario "累计距离含重复值" 锁死）
- [ ] 3.6 字段插值（按 spec "字段插值" requirement 表格）：
  - 线性插值：`speedKmh / lat / lon`（α 浮点）
  - 线性插值 + round 转 Long：`elapsedMsInLap` 用 `kotlin.math.round(s_k.elapsedMsInLap * (1-α) + s_{k+1}.elapsedMsInLap * α).toLong()`
  - 最近邻：`bearingDeg`（`if (alpha < 0.5) s_k.bearingDeg else s_{k+1}.bearingDeg`）
  - nullable 三分支：`accelerationG`（两端非 null 走线性 / 一端 null 取另一端 / 都 null 返回 null）
  - 派生：`absoluteTsMs = lapStartWallClock + (插值后的 elapsedMsInLap)`
- [ ] 3.7 比较圈样本数过少降级（按 spec "比较圈样本数过少 SHALL 退化处理"）：
  - `samples.size == 1` → 该圈所有 grid 点输出 `samples[0]`（**直接复制**，包括 `absoluteTsMs` / `accelerationG` 都保留原值）
  - `samples.isEmpty()` → 取参考圈对应 grid 点的 `lat/lon/speedKmh/elapsedMsInLap`；**MUST 重新派生** `absoluteTsMs = laps[k].lapStartWallClock + (该 grid 点 elapsedMsInLap)`（**禁止** copy 参考圈 absoluteTsMs，会跨时钟域污染——core spec normative 锁死路径）；`accelerationG` 强制 null 标记 fallback；为此 `resampleByGrid` 接 `fallbackRefSamples: List<LapTelemetrySample>?` 参数（参考圈已重采样的 grid 序列）+ `targetLapStartWallClock: Long` 参数（用于 absoluteTsMs 重派生）
- [ ] 3.8 worktree 内跑 `./gradlew :core:domain:compileKotlin` 验证 LapAlignment.kt 编译通过 0 warning

## 4. 单元测试 6 cases（覆盖 spec scenarios 与边界）

- [ ] 4.1 **在 worktree** 新建 `core/domain/src/test/java/com/blazepush/core/domain/usecase/LapAlignmentTest.kt`，加 `package com.blazepush.core.domain.usecase` + `import org.junit.Test` + `import org.junit.Assert.*` + 私有 helper `fun mockLap(...)` 构造 `LapTelemetry` 与 `LapTelemetrySample`（参数化 sample 数 / pace / 起点 lat,lon / lapDurationMs / accelerationG nullable / lapStartWallClock: Long = 0L）
- [ ] 4.2 **Case A：三圈不同 pace 重采样**（覆盖 spec "三圈不同 pace 正常重采样" + "字段插值" + "局部平面投影" + "两圈相同轨迹" 场景的混合）
  - 构造 3 圈：相同 1km 直线轨迹（lat 0→0.009°，lon 不变），耗时 60s/65s/62s，sample 数 1500/1625/1550 @ 25Hz，speed 80/74/77 km/h；变量名定为 `val laps: List<LapTelemetry>` 含 3 元素
  - 调用 `val result = LapAlignment.alignByDistance(laps, 0, 5.0)`
  - 断言 1：`result.samplesPerLap.size == 3`
  - 断言 2：`result.gridSize == result.samplesPerLap[0].size && result.samplesPerLap[0].size == result.samplesPerLap[1].size && result.samplesPerLap[1].size == result.samplesPerLap[2].size`
  - 断言 3：`result.gridSize == floor(参考圈 totalDist / 5) + 1`（参考圈直线距离约 1000m → gridSize ≈ 201）
  - 断言 4：`result.samplesPerLap[0][100].speedKmh ≈ 80.0`（在 distance=500m 处）
  - 断言 5：`result.samplesPerLap[1][100].speedKmh ≈ 74.0`，`result.samplesPerLap[2][100].speedKmh ≈ 77.0`（同一空间位置三圈各自速度）
  - 断言 6：`result.samplesPerLap[0][0].lat == laps[0].samples[0].lat`（首网格点 == 首样本的 lat，首尾 clamp 验证；**MUST 用 `laps[0].samples[0].lat` 不是 `samples[0].lat`**——`samples` 不是 mock helper 暴露的变量名）
  - 断言 7：`result.gridIndexFor(500.0) == 100`（reverse-lookup helper 正确）
  - 断言 8：`result.distanceAtGridIndex(100) == 500.0`
  - 断言 9：`result.referenceLapIndex == 0`（验证用过的参考圈 index 字段）
- [ ] 4.3 **Case B：单圈输入**（覆盖 spec "单圈输入返回单元素 samplesPerLap"）
  - 构造 1 圈：800m 直线，sample 数 1000，speed 60 km/h；变量名 `val laps: List<LapTelemetry>` 含 1 元素
  - 调用 `val result = LapAlignment.alignByDistance(laps, 0, 5.0)`
  - 断言 1：`result.samplesPerLap.size == 1`
  - 断言 2：`result.gridSize == floor(800/5) + 1 == 161`
  - 断言 3：`result.samplesPerLap[0][50].lat in laps[0].samples.first().lat..laps[0].samples.last().lat`（grid 50 处 lat 落在 mock 数据 lat 区间内，验证投影正确性）
- [ ] 4.4 **Case C：距离过短 / 步长不合法**（覆盖 spec "距离步长非正"）
  - 子 case C1：构造 1 圈正常，调用 `alignByDistance(laps, 0, 0.0)` → `assertEquals(LapAlignmentResult.EMPTY, result)` + `assertTrue(result.samplesPerLap.isEmpty())`
  - 子 case C2：构造 1 圈正常，调用 `alignByDistance(laps, 0, -5.0)` → `assertEquals(LapAlignmentResult.EMPTY, result)`
  - 子 case C3：构造 1 圈累计距离 1500m，调用 `alignByDistance(laps, 0, 3000.0)` → `result.gridSize == 1`（floor(1500/3000)+1 = 1）+ `result.samplesPerLap[0].size == 1`
- [ ] 4.5 **Case D：参考圈越界 + laps 空 + EMPTY helper 行为**（覆盖 spec "参考圈索引越界" + "输入 laps 为空" + "gridIndexFor 在 EMPTY 上返回 -1"）
  - 子 case D1：构造 2 圈，调用 `alignByDistance(laps, -1, 5.0)` → `assertEquals(LapAlignmentResult.EMPTY, result)` + `assertEquals(-1, result.gridIndexFor(100.0))`
  - 子 case D2：构造 2 圈，调用 `alignByDistance(laps, 5, 5.0)` → `assertEquals(LapAlignmentResult.EMPTY, result)` + `assertEquals(-1, result.gridIndexFor(0.0))`
  - 子 case D3：调用 `alignByDistance(emptyList(), 0, 5.0)` → `assertEquals(LapAlignmentResult.EMPTY, result)` + `assertEquals(-1, result.referenceLapIndex)` + `assertEquals(-1, result.gridIndexFor(50.0))` + `assertEquals(0.0, result.distanceAtGridIndex(0), 1e-9)`（laps 为空时 step==0 → distance == 0）
  - 子 case D4：构造 1 圈但参考圈仅 1 个样本，调用 `alignByDistance(laps, 0, 5.0)` → `assertEquals(LapAlignmentResult.EMPTY, result)`（spec "参考圈样本不足"）
  - 子 case D5：构造 1 圈但参考圈所有 sample 同位置（lat/lon 严格相同），调用 `alignByDistance(laps, 0, 5.0)` → `assertEquals(LapAlignmentResult.EMPTY, result)`（spec "参考圈累计距离 == 0" 反例）
- [ ] 4.6 **Case E：累计距离含重复值（车静止反例）**（覆盖 spec "累计距离含重复值 SHALL 不返回 NaN"）
  - 构造 1 圈：300 帧总数，前 100 帧直线移动（lat 0→0.0009°）累计 100m（索引 [0, 99]），中间 100 帧静止（lat/lon 严格相同，索引 [100, 199]，elapsedMsInLap 单调递增 [4000, 4040, 4080, ..., 7960]，对应累计距离 `d_100 == d_101 == ... == d_199 = 100m`），后 100 帧继续直线（lat 0.0009°→0.0018°）累计另 100m（索引 [200, 299]）；全圈 elapsedMsInLap 单调 [0, 40, 80, ..., 11960] @ 25Hz
  - 调用 `val result = LapAlignment.alignByDistance(laps, 0, 5.0)`
  - 断言 1：`result.gridSize` 合理（约 floor(200/5)+1 = 41）
  - 断言 2：grid 点落入静止区间时（如 d* = 100m，`gridIdx = result.gridIndexFor(100.0) == 20`），`samplesPerLap[0][20].elapsedMsInLap == 4000L`（取重复区间最小 index 的 sample 的 elapsedMsInLap），**禁止**返回 NaN 或其它非 4000L 值（如 4040L、5520L、7960L 中任一）
  - 断言 3：`!samplesPerLap[0][20].lat.isNaN() && !samplesPerLap[0][20].lon.isNaN() && !samplesPerLap[0][20].speedKmh.isNaN()`（α = 0/0 防护生效）
  - 断言 4：`result.samplesPerLap[0].all { !it.lat.isNaN() && !it.lon.isNaN() && !it.speedKmh.isNaN() }`（全 sample 无 NaN）

- [ ] 4.7 **Case F：比较圈样本退化（fallback 路径）**（覆盖 spec "比较圈样本数过少 SHALL 退化处理" 两条 normative）
  - 子 case F1（比较圈仅 1 个样本）：
    - 构造 2 圈：lap0 正常 1000m 直线 + 1500 sample；lap1 仅 1 个样本（lat/lon = lap0.samples[500].lat/lon，speedKmh = 99.9，accelerationG = 0.7）；`laps = listOf(lap0, lap1)`
    - 调用 `val result = LapAlignment.alignByDistance(laps, 0, 5.0)`
    - 断言 1：`result.samplesPerLap.size == 2 && result.samplesPerLap[1].size == result.gridSize`（外层与 grid 对齐）
    - 断言 2：`result.samplesPerLap[1].all { it == lap1.samples[0] }`（每个元素 == lap1.samples[0]，包括 absoluteTsMs / accelerationG 都保留原值）
  - 子 case F2（比较圈样本为空 → 参考圈 fallback + absoluteTsMs 重新派生）：
    - 构造 2 圈：lap0 正常 1000m 直线 + 任意合理 sample 数（如 1500 sample @ 任意 lapDurationMs）+ `lapStartWallClock = 2000000L`；lap1 `samples = emptyList()` + `lapStartWallClock = 1000000L`；`laps = listOf(lap0, lap1)`（**MUST 用不同的 lapStartWallClock 让 fallback 路径可被验证**）
    - 调用 `val result = LapAlignment.alignByDistance(laps, 0, 5.0)`
    - **派生表达式断言**（MUST NOT hard-code 数值，让 mock 数据自由）：
      - 取 `val refSample10 = result.samplesPerLap[0][10]`（参考圈 grid k=10 处重采样 sample）
      - 断言 1：`result.samplesPerLap[1].size == result.gridSize`（保证非空 list）
      - 断言 2：`result.samplesPerLap[1][10].lat == refSample10.lat && result.samplesPerLap[1][10].lon == refSample10.lon && result.samplesPerLap[1][10].speedKmh == refSample10.speedKmh && result.samplesPerLap[1][10].elapsedMsInLap == refSample10.elapsedMsInLap`（4 个字段保留参考圈 grid 点值）
      - 断言 3：`result.samplesPerLap[1][10].absoluteTsMs == laps[1].lapStartWallClock + refSample10.elapsedMsInLap`（**MUST** 用派生表达式，等于 `1000000L + refSample10.elapsedMsInLap`，跟随 mock helper 任意 elapsedMsInLap 自洽）
      - 断言 4：`result.samplesPerLap[1][10].absoluteTsMs != refSample10.absoluteTsMs`（**反例锁死**：跨时钟域不污染——参考圈基于 2000000L，比较圈基于 1000000L，差 1000000L 永不相等）
      - 断言 5：`result.samplesPerLap[1].all { it.accelerationG == null }`（fallback 圈强制 null）
      - 断言 6：`result.samplesPerLap[1].all { it.absoluteTsMs == laps[1].lapStartWallClock + it.elapsedMsInLap }`（全 fallback sample 都满足重派生不变量）

- [ ] 4.8 worktree 内跑 `./gradlew :core:domain:test --tests "*LapAlignmentTest*"` 6 cases 全绿（A/B/C/D/E/F）

## 5. Grep gates 防回退

- [ ] 5.1 在 worktree 内 `grep -nE 'import android\.|import androidx\.' core/domain/src/main/java/com/blazepush/core/domain/usecase/LapAlignment.kt` 命中数 == 0（spec "无 Android 依赖 grep gate" 要求）
- [ ] 5.2 在 worktree 内 `grep -nE 'Context|Repository|Dao\b|SharedPreferences|DataStore' core/domain/src/main/java/com/blazepush/core/domain/usecase/LapAlignment.kt` 命中数 == 0（pure function 边界）
- [ ] 5.3 在 worktree 内 `grep -nE '@RunWith\(AndroidJUnit4|robolectric' core/domain/src/test/java/com/blazepush/core/domain/usecase/LapAlignmentTest.kt` 命中数 == 0（spec "单测可在 JVM unit test 跑通"）
- [ ] 5.4 **签名形态锁定 grep**：`grep -nE 'fun alignByDistance\(laps: List<LapTelemetry>, referenceLapIndex: Int, distanceStepMeters: Double = 5\.0\)' core/domain/src/main/java/com/blazepush/core/domain/usecase/LapAlignment.kt` **恰好 1 命中** + **断言行号范围**：grep 结果第一列 line 数 ∈ [10, 80]（首屏可见 + design D7 起始位置约束）。**保护意图**：实施期或后续 round 改动签名（如调整参数顺序、改默认值、改返回类型）时 grep 失败即 review fail。**注意**：本 gate 在 baseline 阶段（文件不存在）期 grep 报 "No such file"——这是预期，仅在 apply 完成后跑。
- [ ] 5.5 **return type 形态锁定 grep**：`grep -c 'data class LapAlignmentResult' core/domain/src/main/java/com/blazepush/core/domain/usecase/LapAlignment.kt` 恰好 1 命中（防止合并占位时 EMPTY 实例化方式被改）+ `grep -c '\.EMPTY' core/domain/src/test/java/com/blazepush/core/domain/usecase/LapAlignmentTest.kt` **恰好 7 命中**（覆盖 D1/D2/D3/D4/D5 + C1/C2 共 7 条 EMPTY 边界断言子 case，与 §4.4-§4.5 sub-case 数对齐；上界保护防止漏写）
- [ ] 5.6 **NaN 防护 grep**：`grep -nE 'isNaN' core/domain/src/test/java/com/blazepush/core/domain/usecase/LapAlignmentTest.kt` ≥ 1 命中（case E 必须断言 NaN 不出现）

## 6. 编译 + 测试全绿验证

- [ ] 6.1 worktree 内 `./gradlew :core:domain:compileKotlin :core:domain:test` 全绿
- [ ] 6.2 ~~worktree 内 `./gradlew :core:domain:lintDebug` 不引入新 Kotlin warning~~（**修订 2026-05-05**：kotlin.jvm 模块无 lintDebug 任务；mimo 实施时未真跑命令验证；kt-format-checker 在 push 期会拦）

## 7. Commit + rebase + 合回主区（user 授权后执行）

- [ ] 7.1 worktree 内 `git status` 确认改动只涉及 `core/domain/src/main/java/.../usecase/LapAlignment.kt` + `core/domain/src/test/java/.../usecase/LapAlignmentTest.kt` + `core/domain/src/main/java/.../model/LapTelemetry.kt`（占位）三文件
- [ ] 7.2 user 授权后 worktree 内 `git add` 三文件 + `git commit -m "feat(lap-comparison): 新增 LapAlignment.alignByDistance pure function + LapAlignmentResult + 6 case 单测"`（Conventional Commits）
- [ ] 7.3 worktree 内 `git fetch origin && git rebase feature/track-tech-v2`：
  - **若 W1 已合回主区**：rebase 必然在 `core/domain/src/main/java/com/blazepush/core/domain/model/LapTelemetry.kt` 上 conflict（同 package 同名 data class 二次声明，Kotlin 编译器不允许）。处理：`git rm core/domain/src/main/java/com/blazepush/core/domain/model/LapTelemetry.kt` → `git rebase --continue` → `./gradlew :core:domain:test` 全绿
  - **若 W1 未合回**：rebase 干净通过（占位 LapTelemetry.kt 与主区无冲突）
  - **MUST NOT** 用 `git rebase --skip` 或 `--abort` 草率应对 conflict——这是预期信号，不是错误
- [ ] 7.4 rebase 后再次跑 `./gradlew :core:domain:compileKotlin :core:domain:test` 全绿
- [ ] 7.5 切回主区 `git checkout feature/track-tech-v2 && git merge feature/lap-comparison-time-align --ff-only`（**worktree → 主区方向**，此步 ff-only 必然成立——worktree 已 rebase 至主区 head）
- [ ] 7.6 主区跑 `./gradlew :core:domain:compileKotlin :core:domain:test` 验证合回态全绿
- [ ] 7.7 主区 `git diff --stat HEAD~1..HEAD -- core/domain/` 验证 diff 边界 == 3 文件（W1 未合回时）or 2 文件（W1 已合回时，无占位 LapTelemetry.kt diff）
- [ ] 7.8 同步本地 md 一致性（不进 git，但 plateau 必要条件——下游 Tier 2 多圈比较屏 round 立项时读 entry sketch 会按旧契约走偏）：
  - (a) 更新 `docs/implementation-design/parallel-change-collab.md` §5 W3 行：状态 → `待 push`，最近合回 commit 字段填实际 hash；scope 改为"单测 6 cases (A/B/C/D/E/F)"
  - (b) 更新 `docs/design/phase-1-entry-data-contracts.md` §4（line 162-166）：旧裸 `List<List<LapTelemetrySample>>` 签名改为 `LapAlignmentResult` 形态 + `gridIndexFor` / `distanceAtGridIndex` helper 描述

## 8. Codex L2 review + push（user 拍板顺序）

- [ ] 8.1 提醒 user 触发 Codex L2 adversarial review；review 通过后 user 拍板 push 顺序
- [ ] 8.2 Codex review P0/P1 修订（若有）→ 走 §6 / §7 流程消化
- [ ] 8.3 user 拍板 push 后执行 `git push origin feature/track-tech-v2`（**MUST 等 user 显式授权**，远端 kt-format-checker 顺序敏感）

## 9. 归档（review 通过 + push 完成后）

- [ ] 9.1 跑 `/opsx:archive lap-comparison-time-align` 归档变更目录到 `openspec/changes/archive/<date>-lap-comparison-time-align/`
- [ ] 9.2 主 spec 同步到 `openspec/specs/lap-comparison-alignment/spec.md`（capability 落地）
- [ ] 9.3 worktree 清理：主区 `git worktree remove .worktrees/lap-comparison-time-align && git branch -d feature/lap-comparison-time-align`
- [ ] 9.4 metrics.yaml 写入：`estimated_days / actual_days / review_rounds_l1 / review_rounds_l2 / divergence_reason / phase: "Phase 1" / model_apply: "sonnet" or "opus"`

## 10. Follow-up backlog

- [ ] 10.1 **`lap-alignment-vincenty-fallback`**：本 round R2 的 follow-up——跨城市赛道 > 5km 局部平面投影累积误差超 5m 步长时，升级到 Vincenty 公式或 PROJ4 投影。当前 Phase 1 内不做（5km 内精度 < 0.5m 满足），Phase 1 收尾或 Phase 2 决定是否启动。memo 路径：`docs/design/lap-alignment-vincenty-fallback-deferred.md`（本 round 实施期建 placeholder）
- [ ] 10.2 **`lap-alignment-trajectory-divergence-warning`**：本 round R4 的 follow-up——多圈轨迹偏离时（turn 切线不同），同一 distance bucket 不在同一物理位置；Tier 2 多圈比较屏 round 引入"轨迹偏差报警"（cursor 处比较圈与参考圈实际位置 > 阈值时染色提示）。当前 Phase 1 多圈比较屏 round 期间评估，可能合并到该 round scope；如不合并则单独立项

- [x] 10.3 **v3 高频盲点 #16 实战首例已修复 by `phase1-hardening-w2-w3-w4-mimo-debt` round**（2026-05-05）：本 round mimo 实施期未消费 W1 追加的 `LapTelemetrySample.flags: Int = 0` 字段（W1 commit `3c2f2d9` 后追加），导致 `LapAlignment.interpolate / resampleByGridFallback` 重采样后 flags 默认 0 哨兵 → UI 层"flags != 0 表示标记"判断会全部错认。phase1-hardening-w2-w3-w4-mimo-debt round B2/B3/B4 修复路径：(a) interpolate 加最近邻 `flags = if (alpha < 0.5) s0.flags else s1.flags`；(b) resampleByGridFallback empty 圈强制 `flags = 0`；(c) clamp + 精确命中两路径直接返回原 sample 含 flags（天然保留）；(d) LapAlignmentTest case G 5 sub-scenario 锁；(e) W3 spec.md + design.md 字段插值表加 flags 行 + s0/s1 全局 caveat 同步。**详见**：`openspec/changes/archive/<date>-phase1-hardening-w2-w3-w4-mimo-debt/{design.md Decision 6, specs/lap-comparison-alignment/spec.md, review-l2-opus.md}`。**残留**：W4 binary writer 仍永久默认 0 (deferred to Phase 2)，详见本 round design Decision 6 producer/consumer 表。
