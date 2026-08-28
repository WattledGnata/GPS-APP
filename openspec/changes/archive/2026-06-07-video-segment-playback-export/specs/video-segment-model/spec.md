# video-segment-model Delta Specification

> 修改 capability(change `video-segment-playback-export` ②c):消费侧契约——按窗口选段、多段回放映射、跨段导出降级、playable 首播回写。

## ADDED Requirements

### Requirement: 按圈窗口选段契约

系统 SHALL 提供纯函数 `VideoSegmentSelector.selectForWindow(segments, windowStartMs, windowEndMs)`:返回与窗口 `[windowStartMs, windowEndMs]` 有 wallClock 重叠的段,按 `segmentIndex` 升序;段的有效区间为 `[startWallClock, endWallClock ?: +∞]`——`endWallClock == null`(ERROR 救援段时长未知)MUST 保守入选(漏选=救援段画面再次不可见,即 ②a 修的事故复发)。回放/导出 SHALL 经此函数选段,选段为空时行为与现状一致(该圈无录像)。

#### Scenario: 窗口在单段内(正例主路径)

- **WHEN** 段 A=[1000,5000]、B=[8000,12000],窗口=[2000,4000]
- **THEN** 返回 [A]

#### Scenario: 窗口跨两段(正例)

- **WHEN** 同上段集,窗口=[4000,9000]
- **THEN** 返回 [A, B](升序)

#### Scenario: 救援段 null endWallClock 保守入选(反例锁)

- **WHEN** 段 R=[3000, endWallClock=null](救援段),窗口=[100000,200000](远在 start 之后)
- **THEN** R MUST 入选——若实现把 null 当零长(`endWallClock ?: startWallClock`),本 scenario 断言失败,救援段永不可见

#### Scenario: 无覆盖返回空(正例)

- **WHEN** 段 A=[1000,5000],窗口=[6000,7000]
- **THEN** 返回空列表(调用方走"该圈无录像"现状路径)

### Requirement: 多段回放 wallClock 按段映射

回放屏 SHALL `setMediaItems(选中段升序列表)`;playhead wallClock SHALL = `selected[player.currentMediaItemIndex].startWallClock + player.currentPosition`(每段独立基准);段间 gap 由 playlist item 切换自然跳过(剪辑语义,MUST NOT 为 gap 插黑场假播)。

#### Scenario: 第二段播放中的映射(正例)

- **WHEN** 选中段 [A(start=1000), B(start=8000)],ExoPlayer currentMediaItemIndex=1、currentPosition=500
- **THEN** playheadWallClock = 8500(B 基准,非 A 基准 1500)

#### Scenario: 单段行为不回归(正例)

- **WHEN** 选中段仅 [A(start=1000)],currentPosition=2000
- **THEN** playheadWallClock = 3000,与 ②a 前单文件行为一致

### Requirement: 跨段导出 v1 明确拒绝

导出管线选段后:单段覆盖 SHALL 直接以该段为输入(`sourcePath = seg.filePath`,Clip 窗口计算基准 = `seg.startWallClock`,既有 `isLapFullyCovered` 完整覆盖 gate 原样);多段覆盖 SHALL fail 并给出明确文案("该圈横跨多段录像,导出暂不支持")+ `FileLogger.e`(含 "cross-segment" 字样),**MUST NOT 静默选某段导出**(降级段必然不完整覆盖,被既有 gate 拦截——做不可达降级不如诚实拒绝)。完整拼裁为 follow-up `video-export-cross-segment-concat`。

#### Scenario: 单段导出(正例主路径)

- **WHEN** 圈窗口被段 A 完整覆盖
- **THEN** 导出输入=A.filePath,映射基准=A.startWallClock,既有 clip/烧录链路不变

#### Scenario: 跨段明确拒绝带日志(反例锁)

- **WHEN** 圈窗口同时与 A、B 两段重叠
- **THEN** 导出 fail 文案含"横跨多段" + FileLogger.e 含 "cross-segment"——若实现静默选段继续导出,本 scenario 断言失败

### Requirement: playable 首播回写

回放首帧渲染成功(`onRenderedFirstFrame`)SHALL 对当前段执行 `updateSegmentPlayable(id, true)`(仅 `playable == null` 的段写,幂等);播放错误(`onPlayerError`)SHALL 写 `false`。`VideoSegmentDao` SHALL 新增 `updatePlayable(id, playable)` @Query。回写失败仅日志,MUST NOT 影响播放。

#### Scenario: 救援段首播成功收敛(正例)

- **WHEN** 段 playable=null,回放该段首帧渲染成功
- **THEN** 该段 playable 更新为 true

#### Scenario: 已知段不重复写(正例,幂等)

- **WHEN** 段 playable=true,再次播放成功
- **THEN** MUST NOT 触发 update(避免每次播放写库)

#### Scenario: 播放失败标记损坏(正例)

- **WHEN** 段 playable=null,ExoPlayer 抛 PlaybackException
- **THEN** 该段 playable 更新为 false(UI 灰显消费留 follow-up)
