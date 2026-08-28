## MODIFIED Requirements

### Requirement: mock 数据 helper 返回正式 LapTelemetry 容器类型

`MockTelemetry.kt`（`feature/test/src/test/.../ui/components/`）的 mock 数据 helper SHALL 返回 `core/domain` 的正式 `LapTelemetry` 类型，而非 test-only 占位容器 `FakeLapTelemetry`。该占位容器 SHALL 被删除。

实现 MUST 满足：

- `mockSingleLap(n: Int, lapDurationMs: Long)` 的返回类型 MUST 是 `com.blazepush.core.domain.model.LapTelemetry`（不是 `FakeLapTelemetry`）。
- `mockMultiLap(n: Int)` 的返回类型 MUST 是 `List<LapTelemetry>`。
- `internal data class FakeLapTelemetry` MUST NOT 在切换后存在于 `MockTelemetry.kt`（生产 APK 不含此占位类型，切换后测试也不应再引用占位类型）。
- 构造 `LapTelemetry` 时 MUST 填全 9 个字段：4 个既有（`samples` / `sectorBoundaries` / `lapStartWallClock` / `lapEndWallClock`）+ 5 个 W1 字段（`sessionId` / `lapIndex` / `lapDurationMs` non-null + `trackId` / `trackNameSnapshot` nullable）。
- `lapDurationMs` MUST 与 `lapEndWallClock - lapStartWallClock` 严格相等（不引入第二真相源）。
- `mockMultiLap` 的第 i 圈（i 从 0）MUST 赋 `lapIndex == i`，且各圈共享同一 `sessionId`（同 session 内多圈语义，对齐 `getLapTelemetry(sessionId, lapIndex)`）。

#### Scenario: mockSingleLap 返回正式 LapTelemetry 且字段自洽
- **WHEN** 调用 `mockSingleLap(n = 100, lapDurationMs = 60_000)`
- **THEN** 返回值类型是 `com.blazepush.core.domain.model.LapTelemetry`
- **AND** `samples.size == 100`；`lapDurationMs == 60_000`；`lapDurationMs == lapEndWallClock - lapStartWallClock`；`lapIndex == 0`；`sessionId` 非空字符串

#### Scenario: mockMultiLap 多圈赋递增 lapIndex 且共享 sessionId
- **WHEN** 调用 `mockMultiLap(n = 3)`
- **THEN** 返回 `List<LapTelemetry>` 且 size == 3
- **AND** `result[0].lapIndex == 0`；`result[1].lapIndex == 1`；`result[2].lapIndex == 2`
- **AND** `result[0].sessionId == result[1].sessionId && result[1].sessionId == result[2].sessionId`（同 session）
- **AND** 每圈 `lapDurationMs == lapEndWallClock - lapStartWallClock`

#### Scenario: 反例——切换后仍存在 FakeLapTelemetry 占位类型则视为未完成切换
- **WHEN** 对 `MockTelemetry.kt` grep `internal data class FakeLapTelemetry`
- **THEN** 命中数 MUST == 0（占位类型已删除）
- **AND** 若命中数 > 0，则本 round 切换 MUST 判为未完成（半闭环），不得宣称契约对齐——因为「测试仍断言一个生产 APK 不存在的占位类型」正是本 round 要消除的债务

#### Scenario: 反例——LapTelemetry 构造遗漏 non-null 必填字段则编译失败
- **WHEN** `mockSingleLap` / `mockMultiLap` 的 `LapTelemetry(...)` 构造调用遗漏任一 non-null 必填字段（`sessionId` / `lapIndex` / `lapDurationMs` / `lapStartWallClock` / `lapEndWallClock` / `samples` / `sectorBoundaries`）
- **THEN** `:feature:test:compileDebugUnitTestKotlin` MUST 编译失败（Kotlin 编译器强制 non-null primary constructor 参数必填）
- **AND** 该编译失败是切换正确性的硬门槛——只有全 non-null 字段填全才能通过，不存在「假绿」（done condition 绑定编译通过）

### Requirement: 4 chart/map 组件 pure helper 直接消费正式 LapTelemetry 输出形态

切换后，`SpeedTimeChart` / `AccelTimeChart` / `SectorBar` / `TrackPolylineMap` 4 个组件的 pure helper（`computeChartCoordinates` / `findNearestSampleIndex` / `computeChartBounds` / `computeAccelSegments` / `computeSectorBounds` / `computeMapBoundingBox` / `mapLatLonToCanvas`）SHALL 能直接消费由 `mockSingleLap`/`mockMultiLap`（现返回正式 `LapTelemetry`）解构出的字段，无需任何组件签名改动。

实现 MUST 满足：

- 4 个生产组件文件（`SpeedTimeChart.kt` / `AccelTimeChart.kt` / `SectorBar.kt` / `TrackPolylineMap.kt`）MUST NOT 因本 round 产生任何 diff（容器类型替换对消费原始类型的组件透明）。
- contract test 通过 `lap.samples` / `lap.sectorBoundaries` / `lap.lapStartWallClock` / `lap.lapEndWallClock` 解构后传入 pure helper 的表达式 MUST 在 real `LapTelemetry` 上同名编译通过。
- 切换后 `:feature:test:testDebugUnitTest --tests "*ui.components*"`（4 个 ContractTest + GrepGateTest）MUST 零回归。
- mock 数据 MUST 保留比当前 `getLapTelemetry` 输出更丰富的测试信号：`mockSingleLap` 产出 3 个 sector boundary（锁 `SectorBar` 多段渲染）+ 非 null 中央差分 `accelerationG`（锁 `AccelTimeChart` 曲线/分段渲染）。本 round MUST NOT 把 mock 降级到 reader 当前的「单元素 sectorBoundaries + null accelerationG」。

#### Scenario: SectorBar 消费 mock 的 3-sector 仍正确分段
- **WHEN** `mockSingleLap(n = 100, lapDurationMs = 60_000)` 返回正式 `LapTelemetry`，调用 `computeSectorBounds(lap.sectorBoundaries, lap.lapStartWallClock, lap.lapEndWallClock, 900f)`
- **THEN** 返回 3 个 sector（`SectorBarContractTest` 的「3 equal width」case 仍绿）
- **AND** 每段宽 ≈ 300px（900/3）

#### Scenario: SpeedTimeChart / TrackPolylineMap 消费 mock samples 仍跨满画布
- **WHEN** `mockSingleLap(n = 100)` 返回正式 `LapTelemetry`，调用 `computeChartCoordinates(lap.samples, Size(1000f, 500f), ChartAxis.SPEED)` 与 `computeMapBoundingBox(lap.samples)`
- **THEN** `coords.size == 100` 且 `coords[0].x ≈ 0f`、`coords.last().x ≈ 1000f`
- **AND** bbox 中心 ≈ (31.0, 121.0)，纬经度跨度 > 0.001（圆周轨迹）

#### Scenario: 反例——本 round 改动了 4 个 chart 组件生产文件则越界
- **WHEN** 对 `SpeedTimeChart.kt` / `AccelTimeChart.kt` / `SectorBar.kt` / `TrackPolylineMap.kt` 4 个生产文件做本 round diff 审查
- **THEN** 这 4 个文件 MUST 0 行 diff
- **AND** 若任一文件被改（如给组件加吃 `LapTelemetry` 容器的 overload），则越出本 round scope（本 round 是消费方对齐验证，不是改组件 API）——改组件公共签名命中加速通道强制升级 medium 例外，与 small 复杂度矛盾，MUST 拆出独立 round

#### Scenario: 反例——mock 被降级到 reader 当前单 sector 则抹掉组件多段契约覆盖
- **WHEN** 切换时把 `mockSingleLap` 的 `sectorBoundaries` 从 3 元素改为 reader 当前的单元素 `listOf(lapStartWallClock)`
- **THEN** `SectorBarContractTest` 的「3 equal width」case MUST fail（只剩 1 段）
- **AND** 该 fail 锁死「本 round MUST NOT 把 mock 降级对齐 reader 当前 gap」——reader 侧单 sector / null accelerationG 是 reader 的已知 gap（留给 `future-sector-derivation-round` + detail 屏 R1），不得让组件契约测试陪跑降级
