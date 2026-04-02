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

### 下一步
1. 保留当前 parser 锚点修复，并继续以真实资产为准推进。
2. 单独处理 `s2`：确认是上游 VBO split 定义错误、赛道版本不一致，还是需要用 replay 轨迹重新生成临时 gate。
3. 在未确认 `s2` 资产前，不继续改 `LapTimingEngine` 主逻辑，避免把资产问题误判为状态机问题。
4. 如需先打通链路，可评估临时降级为“起点 + s1”验证，待 `s2` 资产确认后再恢复完整两段门。
