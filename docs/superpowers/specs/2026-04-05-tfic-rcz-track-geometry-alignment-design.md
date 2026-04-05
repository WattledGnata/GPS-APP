# TFIC RCZ 赛道几何对齐设计

## 背景

当前 TFIC 圈速链路已经确认：

1. `GateCrossingDetector` 的无限延长线误判已经修正为有限线段相交；
2. 华为真机最新日志中，大量 `accepted=false, reason=NoIntersection` 已证明当前问题不再是 detector 过松，而是 gate 几何本体位置错误；
3. 通过对 `~/Downloads/track_tfic_lpcc_1.rcz` 与 `~/Downloads/session_20260314_170249_天府赛道_lap7.rcz` 的纯数据比对，已经确认：
   - 两个 RCZ 中的 trap 定义一致；
   - lap7 GPS 数据可以与 RCZ trap 定义正确对齐；
   - 当前接收端使用的 TFIC `preset` / runtime gate 几何与 RCZ 真值不一致。

已确认的几何偏差量级为：

- `start-finish` 中心偏移约 `26.9m`
- `s1` 中心偏移约 `155.7m`
- `s2` 中心偏移约 `826.0m`

因此，本轮工作的根因已经明确：**TFIC 赛道 gate 几何来源错误，必须对齐到 RCZ trap 真值。**

## 目标

仅针对 `preset-tfic-lpcc` 这一条赛道，完成以下两件事：

1. 将 `PresetTracks.kt` 中 TFIC 的 `start-finish / s1 / s2` 几何改为与 RCZ trap 真值一致；
2. 修正 `ReplayAlignedTrackCatalog.kt` 的运行时 gate 组装逻辑，避免再把错误 fallback 几何混入 runtime `Track`。

## 非目标

本轮明确不做：

- 不抽象成通用 RCZ 导入框架；
- 不为其他赛道补齐 trap-to-gate 转换能力；
- 不处理 GPS 漂移防抖、多次穿线抑制、方向容忍区间等鲁棒性优化；
- 不调整 `GateCrossingDetector` 当前有限线段相交逻辑；
- 不处理服务端赛道下发、用户自定义赛道、轨迹自动生成赛道等更大范围能力。

## 设计决策

### 决策 1：TFIC 赛道真值统一以 RCZ trap 为准

本轮将 `track_tfic_lpcc_1.rcz` 中的 trap 视为 TFIC 当前唯一真值来源。

用于还原 gate 的字段为：

- `centerLatitude`
- `centerLongitude`
- `bearing`
- `width`

换算规则采用已经在仓库现有测试中验证过的 RCZ 规则：

- 坐标整数值 `/ 6000000.0` 转十进制度；
- `bearing / 1000.0` 转角度；
- `width / 1000.0` 转米。

### 决策 2：gate 线段按“行进方向法线 + trap 宽度”还原

RCZ trap 只提供中心点、行进方向和宽度，不直接提供线段端点。

因此本轮按以下方式还原 gate：

1. trap `bearing` 表示车辆允许通过该 gate 的行进方向；
2. gate 线段方向取该行进方向的法线；
3. 以 trap 中心为中点，沿法线方向各延展 `width / 2`，得到 gate 两端点；
4. 仅为当前 TFIC 赛道生成：
   - `start-finish`
   - `s1`
   - `s2`

这套还原方式已经与 lap7 RCZ GPS 片段完成纯数据对照，能正确解释：

- `起点` 在高速尾速区域附近；
- `s1` 与 `s2` 均可从 lap7 样本中找到合理穿越位置。

### 决策 3：`PresetTracks.kt` 直接落地 RCZ 真值端点

为了保持本轮最小改动，不引入通用导入框架。

因此 `PresetTracks.kt` 中 `preset-tfic-lpcc` 的 `startFinishGate` 和 `sectorGates` 将直接改成本次 RCZ trap 推导出的正确端点与 `passDirection`。

本轮不把“trap -> TimingGate 通用转换器”作为正式设计目标，只修正当前 TFIC 真值，避免扩大范围。

### 决策 4：runtime gate 不再混入错误 fallback 几何

`ReplayAlignedTrackCatalog.kt` 当前问题之一，是 runtime `Track` 会混入 fallback gate 几何，导致：

- `referencePath` 来自 replay / VBO；
- 但 `startFinishGate` 等 gate 又可能来自错误的 preset fallback；
- 最终形成“路径是 generated，但 gate 不是同一真值来源”的混合态。

本轮需要修正该逻辑，使 TFIC runtime `Track` 的 gate 来源统一为修正后的 TFIC 真值，不再继续把旧错误 fallback 几何注入运行时对象。

运行时预期为：

- `Track.source` 保持当前运行时语义；
- `referencePath` 仍可沿用当前 replay / VBO 对齐能力；
- `startFinishGate / s1 / s2` 统一采用修正后的 TFIC 真值；
- 运行时日志中的 `lapDebugTrackSummary` 应直接反映 RCZ 对齐后的 gate 几何。

## 数据设计

### TFIC gate 真值

本轮要落地的 TFIC gate 真值如下。

#### `start-finish`

- 中心点：`30.496179, 104.43317766666667`
- bearing：`183.0°`
- width：`50m`
- 线段端点：
  - `A = (30.496167246506413, 104.43343794245452)`
  - `B = (30.49619075349359, 104.43291739087881)`

#### `s1`

- 中心点：`30.489821166666665, 104.43255433333333`
- bearing：`84.0°`
- width：`50m`
- 线段端点：
  - `A = (30.49004451419976, 104.43252709154902)`
  - `B = (30.48959781913357, 104.43258157511764)`

#### `s2`

- 中心点：`30.495761833333333, 104.43722266666667`
- bearing：`359.0°`
- width：`50m`
- 线段端点：
  - `A = (30.4957579139104, 104.4369620745035)`
  - `B = (30.495765752756267, 104.43748325882984)`

### `passDirection` 语义

`TimingGate.passDirection` 仍表示允许通过方向。

本轮要求其与对应 trap 的 `bearing` 语义一致，即：

- 方向向量指向车辆允许前进方向；
- 其数值必须与 gate 线段法线方向保持一致；
- 不允许再出现“线段来自一套定义、方向来自另一套定义”的错配。

## 代码变更边界

### `PresetTracks.kt`

只修改 `preset-tfic-lpcc` 相关定义：

- `startFinishGate`
- `sectorGates` 中的 `s1`
- `sectorGates` 中的 `s2`
- 必要时同步修正对应 `passDirection`

不改其他赛道，不做无关整理。

### `ReplayAlignedTrackCatalog.kt`

只修改 TFIC runtime gate 的装配逻辑：

- 避免旧 fallback gate 几何污染 runtime `Track`
- 确保 `getTrack("preset-tfic-lpcc")` 与 `getAllTracks()` 返回的 TFIC gate 几何统一为修正后的真值

不改 replay path 主流程，不顺手重构整个 catalog 体系。

## 错误处理与边界

### 明确边界

本轮允许的行为：

- 只修 TFIC 当前这条赛道；
- 只修 `start-finish / s1 / s2` 三个 gate；
- 只修几何真值来源与 runtime 组装一致性。

### 本轮不允许的扩展

- 不为了“更优雅”而抽象出新的通用 RCZ 模型层；
- 不为了“更鲁棒”顺手修改穿线判定规则；
- 不因为发现旧 preset 录入风格问题而大面积重写整个预置赛道文件；
- 不处理 `p房` trap，因为它不属于当前圈速主链路 gate。

## 测试策略

本轮测试只覆盖根因修正所需的最小集合。

### 1. `PresetTracks.kt` 几何真值校验

新增或更新测试，验证 TFIC 的：

- `start-finish`
- `s1`
- `s2`

线段端点与 `passDirection` 已对齐到 RCZ trap 真值。

### 2. `ReplayAlignedTrackCatalogTest.kt`

验证：

- `getTrack("preset-tfic-lpcc")` 返回的 gate 几何与修正后的 TFIC 真值一致；
- `getAllTracks()` 暴露出来的 TFIC 赛道也使用相同几何；
- runtime `Track` 不再混入旧 fallback gate 端点。

### 3. `TestSessionViewModelTrackLapTest.kt`

保留现有基于 runtime track 的 crossing 回归验证，重点确认：

- 选择 `preset-tfic-lpcc` 后 runtime `Track` 仍可建立 session；
- 基于当前 runtime gate 的 crossing 测试仍能推动开圈 / 闭圈主链路；
- `currentLapTrackDebugSummary()` 输出的 gate 几何已切换到 RCZ 真值。

## 真机验收标准

在华为设备上，本轮通过的标准是：

1. `lapDebugTrackSummary` 中的 `startFinish / s1 / s2` 已显示为 RCZ 对齐后的新几何；
2. 用户预期起点附近重新出现有效穿线；
3. 不再出现旧无限延长线模型导致的远处误触发；
4. `NoIntersection` 若仍出现，必须发生在真正未接近 gate 的位置，而不再是由于 gate 真值错误造成的系统性误判。

## 成功标准

本轮完成后，应满足：

1. `preset-tfic-lpcc` 的 gate 真值与 RCZ trap 定义一致；
2. runtime `Track` 不再混入错误 fallback gate 几何；
3. 单元测试能够稳定证明接收端几何已改为 RCZ 真值；
4. 真机日志能够证明 runtime gate 坐标已切换正确；
5. 下一步问题若仍存在，将属于更高层的鲁棒性或状态机问题，而不再是赛道几何真值错误。
