## Context

W2 `chart-and-map-components` 用 test-only 占位容器 `FakeLapTelemetry`（`MockTelemetry.kt:14`，4 字段）组装 chart 组件契约测试，等 W1 合回后切到正式 `LapTelemetry`（9 字段）。W1 已 land，本 round 执行切换。

### 三类容器对照（grep 核实）

| 容器 | 定义位置 | 字段 |
|---|---|---|
| 占位 `FakeLapTelemetry`（待删） | `feature/test/src/test/.../ui/components/MockTelemetry.kt:14` | `samples` / `sectorBoundaries` / `lapStartWallClock` / `lapEndWallClock`（4） |
| 正式 `LapTelemetry`（切到它） | `core/domain/.../model/LapTelemetry.kt:32` | `sessionId` / `lapIndex` / `lapStartWallClock` / `lapEndWallClock` / `lapDurationMs` / `samples` / `sectorBoundaries` / `trackId` / `trackNameSnapshot`（9） |
| `getLapTelemetry` 实际填充 | `core/data/.../repository/TelemetryRepository.kt:298-308` | 上述 9 字段全填；`sectorBoundaries = listOf(lapStartWallClock)`（单元素）；样本 `accelerationG = null`；`flags = sample.flags`（透传 binary） |

### 字段差（W2 L2 review P1-1 已 flag）

real `LapTelemetry` 比 `FakeLapTelemetry` 多 5 字段：`sessionId:String` / `lapIndex:Int` / `lapDurationMs:Long`（3 个 non-null 必填，编译期硬约束）+ `trackId:String?` / `trackNameSnapshot:String?`（2 个 nullable，可填 null）。简单 import 切换会让 `mockSingleLap`/`mockMultiLap` 的构造调用编译失败（缺 3 个 non-null 必填参数）。

### 消费形态（grep 4 个 contract test 核实）

| 测试文件 | 消费的字段 / helper |
|---|---|
| `SpeedTimeChartContractTest.kt` | `lap.samples` → `computeChartCoordinates` / `findNearestSampleIndex` |
| `AccelTimeChartContractTest.kt` | `lap.samples` → `computeChartCoordinates` / `computeChartBounds` / `computeAccelSegments` |
| `SectorBarContractTest.kt` | `lap.sectorBoundaries` / `lap.lapStartWallClock` / `lap.lapEndWallClock` → `computeSectorBounds`；其中 `mockSingleLap sectors - 3 equal width` 断言 mock 产出 **3 sector** |
| `TrackPolylineMapContractTest.kt` | `lap.samples` → `computeMapBoundingBox` / `mapLatLonToCanvas` |
| `GrepGateTest.kt` | grep 字面量（不解构容器）：§8.6 检查 `import.*mockSingleLap\|import.*mockMultiLap` ≥4 + prod 0 引用；§8.7 锁 `LapTelemetry.kt` 8 字段含 `val flags: Int` |

**关键**：4 个组件的 pure helper 全部只吃 `List<LapTelemetrySample>` / `List<Long>` / `Long`，**从不接受容器类型本身**。容器从 `FakeLapTelemetry` 换成 `LapTelemetry` 后，4 个被读取字段（`.samples` / `.sectorBoundaries` / `.lapStartWallClock` / `.lapEndWallClock`）在 real `LapTelemetry` 上全部同名存在 → 消费侧表达式不变即编译通过。

## Goals / Non-Goals

**Goals:**

- 把 `MockTelemetry.kt` 的 `mockSingleLap`/`mockMultiLap` 返回类型从占位 `FakeLapTelemetry` 切到正式 `LapTelemetry`，补全 5 个 W1 字段。
- 在测试层锁死「4 组件 pure helper 可直接消费正式 `LapTelemetry` 的输出形态字段」契约，让 `lap-detail-screen-with-cursor` 组屏建立在已验证契约上。
- 删除 `FakeLapTelemetry` 占位类，消除「测试断言一个生产不存在类型」的债务。

**Non-Goals:**

- 不改 4 个 chart/map 生产组件签名（容器替换对它们透明）。
- 不改 `core/domain` 的 `LapTelemetry`/`LapTelemetrySample` 字段（消费方对齐，非契约扩张）。
- 不改 `getLapTelemetry` reader 的填充语义（`sectorBoundaries` 单元素 / `accelerationG=null` 留给 `future-sector-derivation-round` / detail 屏 R1）。
- 不组屏 / 不接线 / 不导航（detail 屏 scope）。
- 不引入 `gridIndex` 跨圈映射组件 API 改造（comparison 屏 scope）。

## Decisions

### Decision 1: 只换容器类型，不改 4 个 chart 组件签名

**选择**：本 round 只动 `MockTelemetry.kt`（test-only），4 个生产 chart/map 组件 0 diff。

**理由**：grep 核实 4 个组件的 pure helper 签名只吃 `List<LapTelemetrySample>`（如 `computeChartCoordinates(samples: List<LapTelemetrySample>, ...)`）+ 原始类型，从不接受 `FakeLapTelemetry` / `LapTelemetry` 容器本身。测试是 `mockSingleLap().samples` 这样先解构再传入。容器类型替换对组件透明。

**Alternatives:**

- **Alt A（采用）只换 MockTelemetry.kt 容器类型**。优点：改动面最小（单文件 test-only），生产 0 diff，rebase 冲突面积最小。缺点：无（消费侧字段同名，编译透明）。
- **Alt B 同时给 4 个组件加一个 `fun XChart(lap: LapTelemetry, ...)` overload 直接吃容器**。拒绝理由：组件当前 API 故意 hoist 出 `samples`/`cursorAbsoluteTs`（W2 design Decision，cursor state 外置），让组件无状态可复用于单圈+多圈；加吃容器的 overload 会把容器耦合进组件，且本 round scope 是「验证消费对齐」不是「改组件 API」，越界改公共组件签名命中加速通道强制升级 medium 例外，与 small 复杂度矛盾。
- **Alt C 不删 `FakeLapTelemetry`，只加一个 `mockSingleLapReal(): LapTelemetry` 新 helper 并行存在**。拒绝理由：留两套 mock helper 会让「测试断言生产不存在类型」的债务永久化（违背 W2 §11.2 的切换承诺），且 GrepGate §8.6 的 `mockSingleLap`/`mockMultiLap` import 计数会与新名分叉，治理债扩大。本 round 目标就是消除占位类，不是叠加。

### Decision 2: 5 个新字段的填充值（编译期必填 + 语义自洽）

**选择**：`mockSingleLap` / `mockMultiLap` 补全字段如下表：

| 字段 | mockSingleLap 填充 | mockMultiLap 填充（圈 i，i 从 0） | rationale |
|---|---|---|---|
| `sessionId` | `"mock-session"`（常量） | `"mock-session"`（同一 session 多圈共享） | 测试不消费此字段，给可读常量；多圈语义上属同一 session（与 `getLapTelemetry(sessionId, lapIndex)` 的「一个 session 内多圈」语义一致） |
| `lapIndex` | `0` | `i`（0,1,2,...） | 测试不消费；多圈赋递增 index 保持「同 session 内第 i 圈」语义，未来若 detail/comparison 屏测试需要按 index 区分圈不踩坑 |
| `lapDurationMs` | `lapDurationMs`（入参，已有局部变量 = `lapEnd - lapStart`） | 该圈 `duration`（已有局部变量） | 与 `lapEndWallClock - lapStartWallClock` 严格一致（real reader L303 也是这么派生），保持 invariant `lapDurationMs == lapEndWallClock - lapStartWallClock` |
| `trackId` | `null` | `null` | nullable；mock 无真实赛道；real reader 填 `entity.trackId`（也可能 null），mock 用 null 与「无赛道关联」语义一致 |
| `trackNameSnapshot` | `null` | `null` | 同上 |

**理由**：3 个 non-null 字段（`sessionId`/`lapIndex`/`lapDurationMs`）是编译期硬约束，必须填；选可读常量 + 已有局部变量，零额外计算。`lapDurationMs` 复用已存在的 `lapDurationMs` 入参 / `duration` 局部，保证与 `lapEndWallClock - lapStartWallClock` 一致（不引入第二真相源）。2 个 nullable 填 null，与 mock「无真实赛道」语义自洽，且不让任何下游测试误以为有赛道数据。

**Alternatives:**

- **Alt A（采用）non-null 填可读常量/已有局部，nullable 填 null**。优点：编译通过 + 语义自洽 + 零额外计算 + 不引入第二真相源。
- **Alt B `lapDurationMs` 用 `samples.last().elapsedMsInLap` 反算**。拒绝理由：mock 的 `samples.last().elapsedMsInLap` 经 L1 修订已锁严格 == `lapDurationMs`（`MockTelemetry.kt:32-34` 注释），但用反算会引入「durationMs 真相源散在 samples 末项」的隐患——若未来改 sample 生成公式，duration 会静默漂移。直接用入参更稳。
- **Alt C nullable 字段也填假赛道 `trackId="mock-track"`**。拒绝理由：测试不消费这两字段，填假赛道反而可能让未来「按 trackId 过滤/对比」的测试误命中假数据，制造假绿。null 更诚实。

### Decision 3: 5 个 contract test 默认 verify-only（预期零 diff），仅实测 fail 时微调

**选择**：4 个 ContractTest + GrepGateTest 默认不改；apply 期跑全套，**仅当某断言因 real 容器形态变化实测 fail 时**才对该断言做最小微调，并在 tasks 勾选时记录原因。

**理由**：消费侧只读 `.samples`/`.sectorBoundaries`/`.lapStartWallClock`/`.lapEndWallClock`，real `LapTelemetry` 同名提供这 4 字段，断言语义不变 → 预期零 diff。把「可能微调」限定在「实测 fail 才动」，避免预防性改测试制造无意义 diff，也防止「为了过编译顺手放松断言」削弱契约（v3 盲点 #2 假绿）。

**Alternatives:**

- **Alt A（采用）默认 verify-only，实测 fail 才微调**。优点：最小 diff + 契约强度不被预防性放松。
- **Alt B 预防性给所有 contract test 加 real 类型断言**。拒绝理由：scope 蔓延，且预防性改断言可能引入与现实 reader 不符的过度约束。
- **Alt C 新增一个专门断言「mockSingleLap() is LapTelemetry」的契约 case**。**采纳为 spec Requirement 的一个 scenario**（见 spec.md 「mock helper 返回正式 LapTelemetry 类型」normative），但不强行塞进 4 个既有 ContractTest——契约由 spec normative + 切换后编译通过 + GrepGate §8.6 import 计数共同保证。

### Decision 4: mock 保留比 reader 更丰富的测试数据（3-sector + 派生 accelerationG）

**选择**：切换后 `mockSingleLap` 仍产出 3 个等分 sector（`MockTelemetry.kt:64-65`）+ 中央差分 `accelerationG`（边界 null），**不**对齐到 `getLapTelemetry` 当前的「`sectorBoundaries` 单元素 + `accelerationG=null`」。

**理由**：mock 的职责是给组件契约提供**有信号的测试数据**（3 sector 锁 `SectorBar` 多段渲染、非 null accelerationG 锁 `AccelTimeChart` 曲线绘制、`computeAccelSegments` 分段逻辑）。`getLapTelemetry` 当前填单元素 sector / null accelerationG 是 reader 侧的**已知 gap**（路线图 §1.2，留给 `future-sector-derivation-round` + detail 屏 R1 接 `AccelerationSmoother` 反算）。mock 比 reader 当前输出更丰富是**合法且必要**的——它锁的是「组件在 sector 派生完成 / accelerationG 灌回后能正确渲染」的目标契约。本 round 验证的是「real `LapTelemetry` **容器形态**可被消费」（字段存在性 + 类型），不是「mock 与 reader 字面值逐位相等」。

**Alternatives:**

- **Alt A（采用）mock 保留 3-sector + 派生 accelerationG**。优点：契约测试保留对组件多段/曲线渲染能力的覆盖；与 reader 的 gap 由各自 follow-up round 收敛。
- **Alt B mock 对齐 reader 当前输出（单元素 sector + null accelerationG）**。拒绝理由：会让 `SectorBarContractTest` 的「3 equal width」case 失去测试目标（只剩 1 段），`AccelTimeChart` 的曲线/分段测试全部退化为「NO ACCEL DATA」空态，**抹掉组件多段渲染契约的覆盖**。reader 侧 gap 是 reader 的问题，不该让组件契约测试陪跑降级。
- **Alt C 本 round 顺手把 reader 改成多 sector + accelerationG 灌回**。拒绝理由：改 `getLapTelemetry` 填充语义 = 改 `LapTelemetry` 公共数据契约填充，命中 v3 盲点 #16（跨 round 共享字段语义扩展 + W2/W3 已合回消费契约）+ F1 #17，强制升级 medium，与本 round small/纯测试侧 scope 严重不匹配。明确划给 `future-sector-derivation-round` + detail 屏 R1。

## Risks / Trade-offs

- **[real `LapTelemetry` 9 字段构造遗漏 non-null 必填 → 编译失败]** → **Mitigation**：design Decision 2 字段填充表已枚举全 9 字段（4 旧 + 3 non-null 新 + 2 nullable 新）；tasks 2.x 逐字段勾选；done condition = `:feature:test:compileDebugUnitTestKotlin` 通过（编译器强制全 non-null 字段必填，遗漏即 fail，不可能假绿）。
- **[GrepGate §8.6 import 计数因删 `FakeLapTelemetry` 而漂移]** → §8.6 检查的是 `import.*MockTelemetry|import.*mockSingleLap|import.*mockMultiLap` ≥4 与 prod 0 引用，`FakeLapTelemetry` 本身不在该 grep pattern 内（它是 `MockTelemetry.kt` 内部 `internal data class`，从不被 import，contract test 用的是 `mockSingleLap`/`mockMultiLap` 函数）。删 `FakeLapTelemetry` 不影响这两个 helper 函数名 → §8.6 仍绿。**Mitigation**：apply 期 #3 自查实测 `:feature:test:testDebugUnitTest --tests "*GrepGateTest*"` 全绿。
- **[GrepGate §8.7 锁 `LapTelemetry.kt` 8 字段含 flags，本 round 不碰该文件却依赖它]** → §8.7 grep 的是 `core/domain/.../LapTelemetry.kt` 的字段字面量，本 round **不改** `core/domain`，§8.7 状态不变。**Mitigation**：proposal「不受影响」段已声明 core/domain 0 diff；apply 期实测 §8.7 仍绿。
- **[mockMultiLap 多圈 `lapIndex` 赋值错位 → 未来 detail/comparison 屏测试按 index 取错圈]** → **Mitigation**：design Decision 2 明确多圈 `lapIndex = i`（0,1,2 递增），与 `getLapTelemetry(sessionId, lapIndex)` 的「同 session 第 i 圈」语义一致；spec 加 scenario 锁 `mockMultiLap()[i].lapIndex == i`。
- **[本 round 与 detail 屏 round 在 `feature/test` 目录的 rebase 冲突]** → 本 round 独占 `ui/components/`（contract test 侧），detail 屏在 `ui/tracktech/` 组屏，文件零交叉。**Mitigation**：看板 §5 登记独占路径；本 round 先于 detail 屏闭环合回（路线图 §4 第一批线 B → 第二批 detail 屏）。

## Migration Plan

无 schema / 协议 migration（纯 test source set 类型切换，0 生产改动）。

部署步骤：

1. 看板 §5 登记本 round，独占路径 `feature/test/src/test/.../ui/components/`（H round 独占 `ui/tracktech/` 零交叉）。
2. 实施 tasks.md（删 `FakeLapTelemetry` + 改 2 helper 返回类型 + 补 5 字段 + verify 5 个 contract test）。
3. `:feature:test:compileDebugUnitTestKotlin` 通过 + `:feature:test:testDebugUnitTest --tests "*ui.components*"` 全绿。
4. commit + ff-only 合回主区（真机 SKIP，纯测试代码）。
5. 加速通道 L2（road-test-first 模式下去 Codex；本 round 无运行时不需 FileLogger 埋点，纯测试代码）+ metrics.yaml + 归档；push 等 user 拍板。

## Open Questions

无。切换路径 + 字段填充 + 消费形态全部 grep 核实（W2 L2 review P1-1 已预判字段差并给出 scope 修订建议）；本 round 是该建议的机械落地。
