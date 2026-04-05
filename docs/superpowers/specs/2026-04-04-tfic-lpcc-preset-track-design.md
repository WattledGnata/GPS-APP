# TFIC LPCC Preset Track Design

## Goal

在当前 LapDebug / 测试页面中新增一个可选固定赛道 `preset-tfic-lpcc`，替代仅依赖 `preset-demo-circuit` 的现状。该赛道用于让现有判圈链路直接基于天府赛道（TFIC LPCC）的起点与分段门进行运行时判定，而不是继续用 demo 圈对模拟器发送的 GPS 数据做比对。

## Confirmed Decisions

- 保留现有 `preset-demo-circuit`，新增 `preset-tfic-lpcc`。
- 只取 `track_tfic_lpcc_1.rcz` 中的 `起点`、`s1`、`s2` 三个 trap。
- 忽略 `p房` trap，不参与当前 LapTiming 主链路。
- 赛道门线数据来自 `.rcz`。
- `referencePath` 来自 `tianfu_track_replay_5hz.json` 中的 replay samples。
- 结果先硬编码到当前测试页面使用的 preset 赛道链路中，不做通用导入器，不做运行时动态赛道生成。

## Why This Approach

这是当前最小、最稳、最有验证价值的方案。

相比直接重构 `TrackCatalog` 或把 replay / RCZ 动态导入链路接入运行时，这个方案的优势是：

1. 保持当前 LapDebug 主流程不变，降低引入额外问题的风险。
2. 能在同一页面中保留 demo 圈与 TFIC 圈并行对照，便于快速定位判圈问题究竟来自赛道定义还是判圈引擎。
3. 把当前工作聚焦在“判圈基准正确化”，而不是提前做通用基础设施。
4. 为后续真正接入 RCZ/replay 动态赛道提供一个可靠的固定基线。

## Runtime Shape

运行时仍然保持现有结构：

- `TrackCatalog` 提供赛道列表
- LapDebug 配置页展示可选赛道
- 用户选择某条赛道后进入执行页
- `TestSessionViewModel` 持续把 GPS 数据桥接给 `LapTimingEngine`
- `LapTimingEngine` 依据当前选中的 `Track` 做 crossing / lap 判定

本次变化只扩大 `PresetTrackCatalog` 的内容：

- 现有：`preset-demo-circuit`
- 新增：`preset-tfic-lpcc`

## Data Sources

### 1. RCZ track package

文件：`/Users/wattledgnata/Downloads/track_tfic_lpcc_1.rcz`

已确认该文件是 zip 包，内部当前只有 `trackId.json`，包含：

- track 名称：`TFIC LPCC`
- 描述：`天府赛道，成都领克分会`
- traps：`起点`、`s2`、`s1`、`p房`

当前设计只使用：

- `起点` → `startFinishGate`
- `s1`、`s2` → `sectorGates`

`p房` 明确保留在源文件语义里，但当前不进入 `Track`。

### 2. Replay samples

文件：`simulator/src/main/assets/replay/tianfu_track_replay_5hz.json`

该文件不是赛道定义，而是一段回放轨迹样本，包含：

- `sessionTitle`
- `samples: List<ReplaySample>`

本次只把它用作 `referencePath` 的点集来源，让 TFIC preset track 在地图/占位视图与调试链路中拥有一条与真实回放一致的参考轨迹。

## Track Mapping Rules

### Track identity

新增固定赛道：

- `id = "preset-tfic-lpcc"`
- `name = "TFIC LPCC"`
- `layoutName` 可选填入便于区分的描述，如 `"RaceChrono RCZ"` 或保持为空；本次不要求额外扩展。

### Gate mapping

RCZ 中的 trap 先转换为当前 `TimingGate` 模型。

映射规则：

- `起点` → `TimingGateType.StartFinish`
- `s1`、`s2` → `TimingGateType.Sector`
- `sequenceIndex` 固定为：
  - 起点：`0`
  - s1：`1`
  - s2：`2`

判圈顺序明确为：

1. 起点
2. s1
3. s2
4. 再次起点完成一圈

### Gate geometry

`.rcz` 中 trap 提供的是中心点、bearing、width，而当前 `Track` 模型需要的是一条门线（`GeoLine`）和通过方向（`GeoVector`）。

本次设计允许使用“基于 center + bearing + width 的固定几何换算”得到门线与方向向量，然后把结果固化为 preset 数据。换句话说，最终落到代码中的可以是已经算好的 `GeoLine` / `GeoVector` 常量，而不是运行时再解析 `.rcz`。

这是一个一次性数据固化动作，不是构建通用导入器。

### Reference path

`referencePath.points` 来自 `tianfu_track_replay_5hz.json` 的 `samples`：

- 每个 `ReplaySample(latitude, longitude)` 映射为一个 `GeoPoint`
- 作为 `TrackPath.points`

不要求在本次实现中对轨迹做压缩、抽稀或纠偏；保持与当前 replay 样本一致即可，除非测试证明样本量对 UI 或单测造成明显负担。

## Implementation Shape

### Production code

保持现有运行时链路不变，只做以下收口：

1. 在 `PresetTracks.kt` 中新增 `preset-tfic-lpcc`
2. 将该赛道加入 `presetTracks`
3. 继续由 `PresetTrackCatalog` 提供给当前 LapDebug 配置页

### Non-goals

本次明确不做：

- 不做通用 `.rcz` 文件解析器接入生产代码
- 不做 replay JSON → Track 的运行时动态构建
- 不扩展 `Track` 模型去承载 pit lane / p房 等额外 trap
- 不改 `LapTimingEngine` 的基本判圈机制
- 不替换当前测试页整体交互

## Testing Strategy

按照 TDD 执行，至少覆盖以下行为：

1. `PresetTrackCatalog` 返回的赛道列表中同时包含：
   - `preset-demo-circuit`
   - `preset-tfic-lpcc`
2. `preset-tfic-lpcc` 的 gate 映射符合预期：
   - 起点是 `StartFinish`
   - `s1`、`s2` 是 `Sector`
   - 顺序是 `起点 -> s1 -> s2`
3. `TestSessionViewModel` 可以选择 `preset-tfic-lpcc` 进入 LapDebug 模式
4. 如果现有判圈测试依赖唯一 demo track，需要调整为对 TFIC 赛道显式断言，而不是依赖唯一候选项

## Success Criteria

完成后，用户在当前测试页面应当可以：

1. 进入 LapDebug 配置页
2. 同时看到 `Demo Circuit` 与 `TFIC LPCC`
3. 选择 `TFIC LPCC` 开始圈速调试
4. 让现有 GPS / 模拟器数据按 TFIC 的起点、s1、s2 进行 crossing 与 lap 判定

本次成功的标准不是“动态导入赛道能力完成”，而是“当前测试页已经能基于 TFIC 固定赛道做真实链路验证”。

## Risks

### Gate geometry mismatch

`.rcz` 提供的是 trap 中心、朝向和宽度，当前 `TimingGate` 使用的是门线。一次性换算如果理解有偏差，可能导致 crossing 判定方向或位置不准确。

应对策略：

- 先把结果固化为固定数据，避免在运行时引入更多变量
- 保留 demo 赛道做对照
- 后续如果 crossing 不稳定，再单独收敛几何换算问题

### Replay path and gate source mismatch

如果 replay 样本与 RCZ trap 不是完全同一套会话来源，可能出现 referencePath 看起来接近，但 crossing 命中不理想的情况。

应对策略：

- 当前把 replay path 视为“参考轨迹”，主目标先是让 gate 基准替换正确
- 若后续发现 path 与 gate 偏差明显，再进一步收紧数据来源一致性

## Out of Scope Follow-ups

若本次固定 TFIC preset 跑通，后续可独立推进：

1. 把 `.rcz` → `Track` 的转换收敛为可复用工具
2. 把 replay / RCZ 动态导入接到运行时 `TrackCatalog`
3. 重新关闭并恢复 `ReplayLapTimingIntegrationTest` 的 completed lap blocker
4. 根据业务需要决定是否纳入 `p房` 等非圈计时 trap
