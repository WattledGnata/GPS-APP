# fix-gps-stats-and-lazy-catalog-hot-start proposal review

## 0. TL;DR

不建议直接进入 design/specs。proposal 方向总体正确，但 A28 的 reset 分层、A28 测试场景、A37 dispatcher 契约还有 3 处需要先收紧，否则后续 specs/tasks 会把错误契约固化。

## 1. Findings

### Finding 1 — [P1] resetStats 触发层写到 TestSessionViewModel 会漏覆盖

- **位置**：`openspec/changes/fix-gps-stats-and-lazy-catalog-hot-start/proposal.md:48`
- **问题**：Impact 把 `订阅 ConnectionState 触发 resetStats` 写在 `TestSessionViewModel.kt` 上，但 `resetStats()` 和 `_dataQuality` 状态都属于 `GpsDataViewModel`。当前 `GpsDataViewModel` 已经有自己的 `connectionState` StateFlow；如果把 reset 放进 `TestSessionViewModel`，只有创建了测试会话 ViewModel 的路径才会清 stats，设备连接页等直接消费 `GpsDataViewModel` 的路径仍可能保留旧 stats。
- **要求**：proposal 改为 `GpsDataViewModel` 在自身 init 内订阅自己的 `connectionState`，在迁入 `DISCONNECTED` 时调用 `resetStats()`；`TestSessionViewModel` 只承担 A37 track loading，不承担 A28 stats reset。

### Finding 2 — [P1] “后 10 秒掉 0 帧”测试场景无法驱动 ViewModel 更新

- **位置**：`openspec/changes/fix-gps-stats-and-lazy-catalog-hot-start/proposal.md:25`
- **问题**：测试写“前 30 秒连续 25Hz，后 10 秒掉 0 帧”，再断言 `stats.frequency` 在掉帧窗口内下降。但如果后 10 秒完全没有 `GpsData` emission，`GpsDataViewModel.updateDataStats` 不会运行，`data.frequency` 也不会被透传到 stats；除非另加 ticker，这个测试无法机器核销。若实际想测试“近期频率口径”，应喂入后续低频/稀疏帧并让 `GpsData.frequency` 显式为低值，或把“无帧超时衰减”设计成新的 ticker 契约。
- **要求**：proposal 明确 A28 只验证“收到新帧时 stats.frequency 等于该帧 `data.frequency`”，例如 25Hz 历史后喂一帧 `frequency = 1.0` 并断言 stats 立即为 1.0；不要写无 emission 却期待 ViewModel 自行下降的场景，除非本 change 明确引入定时衰减机制。

### Finding 3 — [P2] ReplayAlignedTrackCatalog 的 IO 边界不能写成可由调用方承担

- **位置**：`openspec/changes/fix-gps-stats-and-lazy-catalog-hot-start/proposal.md:19`
- **问题**：proposal 写 `ReplayAlignedTrackCatalog.getAllTracks` 用 `withContext(Dispatchers.IO)` 包裹 “或调用方自带 IO context”。后半句会削弱 A37 契约：任何直接从 Main 调用 suspend `getAllTracks()` 的路径仍可能同步 asset 读。既然 A37 要修的是 catalog 自身首次访问热路径，IO 边界应在 `ReplayAlignedTrackCatalog` 内部兜住。
- **要求**：删除“或调用方自带 IO context”的可选口径，改为 `ReplayAlignedTrackCatalog.getAllTracks` MUST 在内部 `withContext(Dispatchers.IO)` 包裹 asset 读 + Gson parse；调用方可以在 IO 启动协程，但不能作为唯一防线。

## 2. Open Questions

- `packetLossRate` 新公式目前只写“基于 data.frequency 与 dataAge 的相对关系”，design/specs 需要给出可测公式或明确只做保守展示值，否则 tasks 阶段容易不可核销。

## 3. Verdict

暂不放行进入 design/specs。修完以上 3 条后可重提 proposal mini review；无需 patches 清单。

## 4. Round 2 mini review

### 4.1 Finding closure

- Finding 1 closed：`resetStats` 触发层已改为 `GpsDataViewModel.init` 自订阅自身 `connectionState`；`TestSessionViewModel` 只承担 A37 track loading。
- Finding 2 closed：A28 测试已从“无 emission 后期待 stats 自降”改为“喂入低频帧 `data.frequency = 1.0` 后立即透传”，可以硬区分累计平均。
- Finding 3 closed：`ReplayAlignedTrackCatalog.getAllTracks` 已明确 MUST 在实现内部 `withContext(Dispatchers.IO)`，调用方 IO 仅是 defense in depth。
- Open question closed：`packetLossRate` 已给出 `expectedSampleInterval = 1000.0 / data.frequency` 的可测公式。

### 4.2 Non-blocking nits

- `proposal.md:66` 仍写 `GpsDataViewModelTest.kt` 新增 2 条 scenario，但测试契约现在列了 3 条 A28 scenario；design/tasks 阶段同步一下数量。
- `proposal.md:42` 提到 `DataStats.EMPTY`，当前代码里 `DataStats` 没有 companion `EMPTY`；后续 specs/tasks 建议写“等价初始态 / DataQuality.Empty”或明确新增常量，避免照抄成编译错误。

### 4.3 Verdict

Round 2 通过。可以进入 design/specs。上述 nits 不阻塞，但应在下一工件中收敛。
