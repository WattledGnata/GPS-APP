## Why

现场 1.0.7 已恢复异常退出遗留的圈速 Session 与五段可播放录像，但最快圈恰好跨越自动三圈轮换边界，当前统一时间轴把该边界判为“圈头或圈尾缺少录像”并禁止导出。需要让既有历史数据在不重录、不改库的前提下，重新识别自动轮换章节并导出带数据图层的最快圈。

## What Changes

- 将同一 Session 内、按序相邻且满足自动轮换特征的视频段建模为可拼接 chapter，而不是一律按真实停录缺口处理。
- 对历史记录运行时重建连续导出时间轴；无需迁移或重新绑定原视频。
- 自动轮换边界允许跨段导出并重排输出 PTS；真实手动停录、异常缺段仍保持明确警告与保守限制。
- 保持现有共享 overlay 绘制与多段 MediaCodec/MediaMuxer 管线，确保导出成片烧录圈速、速度、G 值和赛道图层。
- 增加现场形态回归测试：最快圈位于三圈轮换后的首圈、多段文件均存在、段间记录时间存在短缺口。

## Capabilities

### New Capabilities

<!-- 无新增独立 capability。 -->

### Modified Capabilities

- `video-segment-model`: 自动轮换生成的相邻视频段 SHALL 能作为同一连续 chapter 序列参与历史圈时间轴重建，并与真实手动/异常缺口区分。
- `video-export`: 历史圈跨自动轮换段时 SHALL 允许拼接导出带数据图层，不因可压缩的自动轮换边界被误判为不可导出。

## Impact

- `feature/test/src/main/java/com/blazepush/feature/test/export/`: 多段时间轴计划、加载与导出 gate。
- `feature/test/src/test/java/com/blazepush/feature/test/export/`: 自动轮换边界与历史最快圈回归测试。
- `core/domain/src/main/java/com/blazepush/core/domain/model/`: 仅在确有必要时补充纯内存连续性语义；本次优先不做 Room schema 迁移。
- 协议兼容性：不修改 RaceChrono BLE 协议、不修改 Livetiming API、不涉及发射端 simulator。
