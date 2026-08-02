## Context

现有 `VideoTimelinePlan` 已把多个 `VideoSegment` 切成按 wall-clock 排列的 slices，`MultiSegmentVideoExportPipeline` 也已能重新编码、拼接并烧录 overlay。阻塞发生在导出前：任何超过 500ms 的圈内 gap 都把 coverage 降为 `PARTIAL`，UI 与 Service 只接受 `FULL`。三圈自动轮换使用单个 CameraX `Recorder` 执行 stop→start，最快圈若是轮换后的首圈，就可能在圈头出现短暂真实 gap 或 Start 回调锚点误差。

历史 `video_segments` 没有持久化 rotation reason，不能可靠区分自动轮换与一次很短的手动停录。现场数据的文件和绑定仍存在，修复必须能够在运行时作用于旧 Session，且不要求数据库迁移。

## Goals / Non-Goals

**Goals:**

- 历史圈跨相邻短缺口视频段时仍可导出一条带 overlay 的成片。
- coverage 继续诚实反映画面并非完整，不把缺帧伪装成 `FULL`。
- 导出时间轴压缩可桥接 gap，并按每个 slice 的原 wall-clock 计算 overlay。
- 单段完整录像、长时间真实停录和无录像行为不回归。

**Non-Goals:**

- 不凭空恢复 CameraX stop→start 期间未采集的真实视频帧。
- 不在本变更重写 CameraX 录制底层或实现连续编码器切换 MediaMuxer。
- 不修改 Room schema、BLE 协议、Livetiming API 或发射端。
- 不解决 Session/录像启动竞态及 GPS 硬件静默问题；二者由独立任务处理。

## Decisions

### 1. coverage 与 exportability 分离

`Coverage.FULL/PARTIAL/NONE` 继续描述实际视频覆盖；新增由 `VideoTimelinePlan` 计算的 `isExportable`/桥接统计描述能否产出有意义的成片。含可桥接 gap 的圈仍为 `PARTIAL`，但 UI 和 Service 可以导出并显示“分段衔接”。

替代方案是直接把短缺口改判 `FULL`；这会掩盖真实缺帧，故不采用。

### 2. 历史相邻段采用有界桥接启发式

保留现有 500ms `SHORT_GAP_TOLERANCE_MS` 作为无感技术 gap；新增 5,000ms `EXPORT_BRIDGE_GAP_TOLERANCE_MS`。仅当缺口与圈本体相交、两侧存在按 `segmentIndex` 相邻的有效 slices、且缺口不超过 5 秒时，视为可桥接 chapter gap。窗口前导/收尾缺画面不影响圈本体判断；圈本体只有单侧画面、长缺口或完全无画面仍不可导。

历史没有 reason，5 秒是兼顾 CameraX stop→start 延迟与防止吞掉明显手动停录的保守上限。后续录制重构应持久化边界原因，逐步替代启发式。

### 3. 复用既有多段导出管线

不引入 FFmpeg 或新 codec 依赖。`VideoTimelinePlan.slices` 已为每段计算连续 `outputStartMs/outputEndMs`；`MultiSegmentVideoExportPipeline` 继续逐 slice 重新编码并压缩 gap，overlay 通过 `wallClockForOutputPosition` 回到各段原始时钟域。

### 4. UI 与 Service 使用同一导出判定

回放页按钮、Toast 状态和 `VideoExportService` 必须共同消费 `timelinePlan.isExportable`，避免 UI 放行但后台再次按 `coverage != FULL` 拦截。可桥接时显示累计/最大 gap 的明确提示；长 gap 仍显示缺失秒数。

## Risks / Trade-offs

- [历史启发式可能把 5 秒内手动停录当 chapter] → 保持 `PARTIAL` 和可见提示，导出只压缩、不修改原数据；用户仍可回看原段。
- [真实 gap 导出会发生画面跳变] → 不伪造帧；成片在边界直接跳转，overlay 按两侧各自 wall-clock 对齐。
- [某机型自动轮换超过 5 秒仍无法抢救] → FileLogger 输出每个 gap；现场包验证后仅基于证据调整阈值，不无限放宽。
- [音视频跨段格式不一致] → 沿用现有多段管线的格式检查与失败清理，失败不留 MediaStore 半成品。

## Migration Plan

1. 覆盖安装 1.0.8，不卸载、不清数据。
2. 打开历史第二节 Session 的最快圈；loader 运行时重新生成 timeline，无数据库迁移。
3. 若显示“分段衔接，可导出”，导出到 `Movies/BlazePush` 并核对 overlay、圈头跨段跳变和音频。
4. 回滚到 1.0.7 不改写原视频或 Session，只会恢复旧的严格禁用行为。

## Open Questions

- 车友真实自动轮换 gap 数值尚未取得；生产诊断包只允许受控服务账号读取，当前本机无 SSH 权限。1.0.8 现场验证需回收 timeline gap 日志以确认 5 秒上限。
