## MODIFIED Requirements

### Requirement: 按圈裁剪导出

系统 SHALL 按圈导出（一圈一文件），导出窗口由 `VideoTelemetrySync.lapPlayheadRange` 产生，并与按 wall-clock 排列的全部视频 slices 求交。圈本体被视频完整覆盖时 SHALL 直接导出；圈本体跨越不超过 5 秒、且两侧均有相邻视频段画面的 chapter gap 时 SHALL 允许压缩该 gap 后跨段导出，coverage 仍 MUST 保持 `PARTIAL` 并向用户提示“分段衔接”。无画面、单侧缺失超过上限或圈内存在更长 gap 时 MUST 禁止导出。

导出 SHALL 复用多段 MediaCodec/MediaMuxer 管线重排连续输出 PTS，并 MUST 按每个 slice 的原 wall-clock 计算烧录 overlay；不得修改或覆盖原始视频文件。

#### Scenario: 圈完整落在单个视频覆盖段内

- **GIVEN** 圈 [lapStart, lapEnd] 完整落在视频段 [videoStart, videoEnd] 内
- **WHEN** 导出该圈
- **THEN** 系统 SHALL 按既有单段裁剪路径产出带 overlay 的单圈成片

#### Scenario: 最快圈跨自动轮换短 gap

- **GIVEN** 第三圈完成后 CameraX 自动轮换，最快圈第四圈的圈头跨相邻 segment 2 与 segment 3，圈内 gap 为 1.2 秒且两侧文件可用
- **WHEN** 用户打开历史 Session 并导出最快圈
- **THEN** coverage SHALL 为 `PARTIAL`，导出入口 SHALL 显示“分段衔接”并可点击
- **AND** 导出 SHALL 压缩 1.2 秒 gap、拼接两段并烧录各自 wall-clock 对齐的 overlay

#### Scenario: 圈头前导超视频覆盖不阻止完整圈导出

- **GIVEN** `lapStart - leadIn < videoStart`，但圈本体 [lapStart, lapEnd] 完整有画面
- **WHEN** 导出该圈
- **THEN** 导出起点 SHALL 钳到视频实际起点，前导超覆盖段 SHALL 不产生黑帧

#### Scenario: 长缺口继续禁止导出

- **GIVEN** 圈内相邻视频段之间缺少 8 秒画面
- **WHEN** 用户尝试导出
- **THEN** 系统 MUST 禁止导出并提示圈内缺少录像秒数，不产生输出文件

#### Scenario: 反例 — 仅圈后半段有录像

- **GIVEN** 录像在圈起点 12 秒后才开始，圈头不存在前一相邻段可桥接
- **WHEN** 用户打开该圈
- **THEN** 系统 MUST 保持 `PARTIAL` 且不可导出，不得把单侧缺失误判为 chapter gap
