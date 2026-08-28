# Lap Session Phase 1 Product Requirements

这份文档用于给 CC / Claude Code 开 OpenSpec change。

目标是落地一期可路测的圈速 session 闭环，不提前承诺分段、理论最佳、图表、地图、视频等高级能力。

## Current Decision

一期只做：

```text
Laps home
→ track selection
→ live lap session
→ end and save
→ Records / Laps session record
```

核心原则：

- 圈速实时页是驾驶仪表，不是数据分析页。
- 圈速测试结束后不强制进入传统“结果页”。
- 结束后保存 session，用户可从 `Records > Laps` 查看记录。
- 一期记录详情只展示 Overview 和圈速记录列表。
- 不做假的 sector、theoretical best、chart、map、video 入口。

## Visual References

这些是视觉参考，不是可切图设计稿。

当前应使用：

- `docs/design/visual-refs/lap-live-landscape-balanced-v1.png`
  - 当前圈速实时页方向。
  - 强制横屏，2x2 仪表，`Delta / Current / Last / Best`。

- `docs/design/visual-refs/lap-live-landscape-minimal-v1.png`
  - 早期横屏方向。
  - 仅参考横屏 cockpit 气质，不作为最终布局优先级。

- `docs/design/visual-refs/records-performance-laps-v2.png`
  - Records tab 方向。
  - 参考 `Performance | Laps` 的职责拆分。

- `docs/design/visual-refs/four-tabs-v2-calmer.png`
  - App shell 和底部四 tab 方向。

- `docs/design/visual-refs/ble-scan-sheet-v2-calmer.png`
  - Bottom sheet 克制程度参考。

赛道图资产：

- `feature/test/src/main/assets/track_thumbnails/chengdu_tianfu.png`
  - App runtime 小缩略图，`720 x 405`，透明底。

- `docs/design/track-thumbnails-v2/highres/chengdu-tianfu-transparent-1440.png`
  - 高清派生卡片尺寸，透明底。

- `docs/design/track-thumbnails-v2/highres/chengdu-tianfu-transparent-3840.png`
  - 高清派生 4K 版本，透明底。

不要使用：

- `docs/design/visual-refs/lap-live-minimal-v1.png`
  - 竖屏早期稿，只作为历史参考。

- 任何带 `Sectors`、`Theoretical Best`、`Chart`、`Map` 作为一期主入口的探索稿。
  - 这些不是一期范围。

## User Flow

### Start Session

入口：

```text
Laps tab
```

用户在 Laps 首页选择当前赛道后点击开始。

如果设备/GPS 未 ready，按全局 gating 逻辑引导到 Device tab。

首页 gating 只判断设备/GPS/data readiness，不检查速度区间。

### Live Session

进入 live lap session 后：

- 强制横屏。
- 不显示 bottom tab bar。
- 保持屏幕常亮。
- 拦截返回手势/返回键。
- 返回手势不直接退出 session，而是触发结束确认或提示使用 `HOLD TO END`。
- App 进入后台不应因为 UI `onPause/onStop` 直接停止 recorder。

### End Session

结束入口：

```text
HOLD TO END
```

结束后：

- 停止 recorder。
- 保存 session。
- 显示轻量保存反馈。
- 默认回到 Laps 首页或 Records/Laps 列表。
- 可以提供 `View Record`，进入 Records/Laps 的 session record detail。

不要强制跳转传统结果页。

## Live Session Screen

页面用途：

```text
驾驶中一眼扫视
```

方向：

```text
Landscape only
```

默认模板：

```text
Lap Timing Balanced
```

必须展示：

```text
Delta to best
Current lap
Last lap
Best lap
Lap number
```

布局：

```text
Top strip:
  LAPS · track name · LAP n · tiny Ready · pause/stop controls

2x2 dashboard:
  Delta to best
  Current lap
  Last lap
  Best lap

Bottom:
  HOLD TO END
```

视觉优先级：

1. `Delta to best`
2. `Current lap`
3. `Last lap`
4. `Best lap`
5. `Lap number`

`Current lap` 不要独占视觉中心。

默认不要展示：

- speed
- GPS details
- satellite count
- HDOP
- 25Hz
- telemetry chart
- lap list
- sector table
- track map

正常状态只允许极小状态，例如：

```text
Ready
```

异常状态可以打断：

```text
GPS SIGNAL LOST
WAITING FOR GPS LOCK
BLE DISCONNECTED
LAP INVALIDATED
```

## Keep Screen Awake

所有测试执行页都必须保持亮屏：

- 0-100 acceleration execution
- 100-0 braking execution
- lap session live execution
- future video recording template

要求：

- 进入执行页时启用 keep-screen-on。
- 离开执行页或 session 结束后释放。
- 不要全 App 常亮。

## Session Recorder Lifecycle

Recorder 不应该依赖 UI `onPause/onStop`。

但 recorder 必须有明确 owner 和终止边界。

要求：

- UI 负责展示和发送 start/end 操作。
- Recorder / session controller 持有 session 状态。
- 配置变化、横竖屏重建、退后台不应直接停止 active recording。
- 用户明确结束 session 才停止 recorder。
- 如果 Activity/Service 真正销毁，需要 cleanup 或将 active session 标记为异常结束。

一期如果不做完整 Foreground Service，也至少不要把 recorder 绑在 Composable 页面 scope 上。

## Back Navigation

Live session 中返回键/返回手势必须拦截。

行为：

```text
Back → show end-session confirmation / prompt HOLD TO END
```

不允许：

```text
Back → directly pop page / lose session
```

## Records / Laps Session Detail

一期记录详情是竖屏记录页，不是测试结束强制承接页。

入口：

```text
Records tab
→ Laps sub tab
→ session row
```

或：

```text
Session saved feedback
→ View Record
```

一期只做 Overview，不做 tabs。

必须展示：

```text
Track name
Session date/time
Best lap
Total laps
Valid laps
Invalid laps
Lap records list
```

如果当前数据可靠，也可以展示：

```text
Top speed
Duration
Distance
```

不要展示：

- theoretical best
- S1/S2/S3
- sector matrix
- chart tab
- map tab
- video tab
- lap-vs-lap comparison

### Lap Records List

一期列：

```text
Lap
Time
Diff
Status
```

如果每圈 top speed 已有可靠字段，可以加：

```text
Top speed
```

状态：

- `BEST`
- `VALID`
- `INVALID`
- `INCOMPLETE`

无效圈需要展示原因，如果已有：

```text
GPS LOST
PIT
MANUAL END
```

## Future Scope

这些不是一期：

- sector timing
- theoretical best
- RaceChrono-style configurable data template
- advanced chart mode
- map replay
- lap-vs-lap comparison
- video recording
- video overlay/export

未来建议：

```text
Analysis Mode
```

作为独立高级模式，默认横屏，从右上角菜单或 session detail 进入。

Analysis Mode 可以承载：

- split/sector table
- lap-vs-lap comparison
- speed curve
- acceleration channels
- track map replay
- video overlay

## Acceptance

一期完成后应满足：

- 可以从 Laps 首页进入一次圈速 session。
- Live session 强制横屏。
- Live session 不显示速度和 GPS 细节。
- Live session 展示 `Delta / Current / Last / Best / Lap number`。
- Live session 保持亮屏。
- Live session 返回手势不会直接退出。
- Session 可以明确结束并保存。
- 结束后不强制进入传统结果页。
- Records/Laps 能看到保存的 session。
- Session detail 只展示 Overview 和 lap records list。
- 不出现假的 sector/theoretical best/chart/map/video 入口。
