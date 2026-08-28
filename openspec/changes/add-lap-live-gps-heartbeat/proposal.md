## Why

圈速驾驶页当前只显示 DELTA、CURRENT、LAST、BEST。虽然 `TestSessionViewModel.filteredSpeedKmh` 已提供与圈速过滤链路同源的速度，但驾驶页不可见；车辆静止时也无法仅凭旧数值判断 GPS Main 帧是否仍在刷新，现场容易误把 0 km/h 当作 GPS 中断并反复创建空 Session。

## What Changes

- 在圈速 HUD 驾驶页常驻显示滤波后当前时速。
- 在四宫格交叉中心显示速度岛；顶部单行显示 GPS 状态、Main 帧频率、卫星数和数据年龄。
- 以 `mainFrameReceivedAtElapsedRealtimeMs` 和动态 `mainFrameSilenceTimeoutMs` 判断新鲜度；失效时速度显示 `--`，不保留旧速度。
- 区分 Main 持续刷新时可信的 0 km/h 与没有新 Main 帧时的 `--`。
- 保留 CURRENT、DELTA、LAST、BEST、BLE 硬中断、GPS 软提示、相机页、录像状态和 HOLD TO END。

## Capabilities

### New Capabilities

- `lap-live-gps-heartbeat`: 圈速驾驶 HUD SHALL 常驻呈现可信滤波速度和基于真实 Main 帧新鲜度的 GPS 心跳。

### Modified Capabilities

无。

## Impact

- `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/`: 圈速 HUD 数据映射与布局。
- `feature/test/src/test/java/com/blazepush/feature/test/ui/tracktech/`: 新鲜、静止、stale、断连及等待定位测试。
- 不修改 BLE 协议、Main 解析、Livetiming API、计时门线或持久化 schema。
