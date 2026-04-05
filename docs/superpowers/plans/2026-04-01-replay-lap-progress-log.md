# Replay Lap Progress Log

## 2026-04-01 当前停靠点

### 已完成
- 确认 `SpeedController` 的既有测试失败不属于本轮 replay 接线回归，不阻塞圈速回放链路判断。
- 为 `feature:test` 增加了对 `simulator` replay 解析代码的测试依赖：`feature/test/build.gradle.kts`。
- 将 `GateCrossingDetector` 从“仅按纬度穿线判定”改为“基于线段两侧符号变化判定”，基础测试 `GateCrossingDetectorTest` 通过。
- 新增 replay 圈速集成测试：
  - `feature/test/src/test/java/com/blazepush/feature/test/usecase/ReplayLapTimingIntegrationTest.kt`
  - 该测试当前仍失败，但已稳定复现问题。
- 从原始 RaceChrono CSV 裁出 2~4 圈、5Hz 的最小可用 replay 小样本：
  - `simulator/src/main/assets/replay/tianfu_track_replay_laps_2_4_5hz.json`
  - `sampleCount = 1894`
  - 时间范围：`1773478356533` ~ `1773478735140`

### 当前阻塞点
- `ReplayLapTimingIntegrationTest` 仍未跑出完成圈。
- `parseVboGates()` 第一版用 VBO 第一条 `[data]` 记录做归一化锚点，这对“只保留第 2~4 圈”的 replay 小样本会造成整体漂移；该问题已定位并修正为按 `referenceSample.timestampMillis` 就近匹配 VBO data 行。
- 修正锚点后，`起点` 与 `s1` 已能贴近 replay 轨迹，但 `s2` 仍明显落在轨迹包围盒之外：
  - `起点` 最近距离约 `1.49e-06 deg`
  - `s1` 最近距离约 `3.15e-05 deg`
  - `s2` 最近距离约 `0.00264 deg`
- 同样结论对完整回放 `tianfu_track_replay_5hz.json` 也成立，因此当前更像是 `tianfu_track.vbo` 中 `s2` 定义与 replay 资产本身不一致，而不是小样本裁剪导致。
- 进一步核对 `track_tfic_lpcc_1.rcz` 后，已确认：
  - `.rcz` 不是加密文件，而是 ZIP 容器，内部为明文 `trackId.json`；
  - `RCZ.track.traps[*].centerLatitude/centerLongitude` 与 VBO `[laptiming]` 中每条 gate 的起点原始数值一一对应；
  - `width=50000` 与 VBO gate 线段长度约 50m 一致，可视为 trap 宽度的毫米表示；
  - `bearing` 与 VBO 线段轴向满足稳定关系：可近似理解为“从 VBO end 指向 VBO start 的方向”；
  - 因此 RCZ 与 VBO 属于同一套 trap 几何源，不支持“地图协议/坐标系切换导致微末偏差”的假设；当前 `s2` 问题更像是源 trap 定义与 replay 轨迹不一致，或赛道 trap 版本与该 replay 不一致。

### 对 fitted S2 方案的影响
- 当前证据已经足够支持：优先排查 `s2` 资产定义/版本问题，而不是继续怀疑 parser 归一化或地图坐标协议。
- 现有 `ReplayGateFitter` 的“仅平移、保持原线段朝向与长度”假设，仍只在 synthetic test 上成立；对真实 replay，下一步应以“重建临时 gate”为候选方案，而不应继续围绕纯平移微调。
- 在 `s2` 真值未厘清前，`LapTimingEngine` 主状态机不应背锅，也不应引入全局容差放宽。

### 下一步
1. 保留当前 parser 锚点修复，并继续以真实资产为准推进。
2. 单独处理 `s2`：确认是上游 VBO split 定义错误、赛道版本不一致，还是需要用 replay 轨迹重新生成临时 gate。
3. 在未确认 `s2` 资产前，不继续改 `LapTimingEngine` 主逻辑，避免把资产问题误判为状态机问题。
4. 如需先打通链路，可评估临时降级为“起点 + s1”验证，待 `s2` 资产确认后再恢复完整两段门。

## 2026-04-03 阶段结论

- 当前可置信的 gate 为 `起点` 与 `s1`：两者与 replay 轨迹贴合度明显高于 `s2`，可作为当前主链路验证依据。
- `s2` 当前可降级处理：它更像是资产定义/版本不一致的局部问题，不再作为是否认可整体方案的前置阻塞。
- 由 `起点 + s1` 可得出的阶段判断是：replay 读取、gate 解析、crossing 判定、`LapTimingEngine` 状态推进这条主代码链路已基本打通。
- `ReplayTemporaryGateBuilderTest` 与 `ReplayTemporaryGateGeometryTest` 已对齐，builder/geometry synthetic tests 不再是 replay 主线阻塞点。
- integration 剩余阻塞已收敛为单一问题：`ReplayLapTimingIntegrationTest` 期望出现 `completedLaps=1`，但真实 replay 接线下仍无法产出 completed lap；该测试现已明确标注为已知 blocker，而非新的未知回归。
- 在 `s2` 真值厘清前，`lap-debug-mode` 不继续按“完整两段 split 已成立”的前提扩写；下一阶段可先基于 `起点 + s1` 收口页面语义与验证路径。
