# Track Tech V2 Visual References

这个目录只保留当前认可的 V2 方向效果图。

这些图片是视觉参考，不是可切图的设计稿源文件。
不要把它们当作 production bitmap，也不要从里面裁按钮、卡片、图标直接进 App。

## Current References

- `four-tabs-v2-calmer.png`
  - 当前 App Shell 方向。
  - 展示 `Test | Laps | Records | Device` 四个 tab 的整体强度和信息层级。

- `test-home-v2-calm.png`
  - Test 首页方向。
  - 用于参考速度 hero、0-100 / 100-0 入口、Latest Result 的布局。

- `device-home-v2-calm.png`
  - Device 首页方向。
  - 用于参考连接控制台、轻量 GPS 指标、GPS Details 入口。

- `records-performance-laps-v2.png`
  - Records 首页方向。
  - 展示 `Performance | Laps` 两个子 tab 的职责拆分；Performance 只承载加速/制动历史，Laps 以赛道为上下文承载圈速记录。

- `lap-live-landscape-balanced-v1.png`
  - 圈速测试实时页当前方向。
  - 进入圈速 session 后强制横屏；采用 `Delta / Current / Last / Best` 2x2 仪表，圈号只是顶部小 badge。

- `lap-live-landscape-minimal-v1.png`
  - 圈速测试实时页早期横屏参考。
  - 进入圈速 session 后强制横屏；默认驾驶模板只展示当前圈计时、相对最佳差值、最佳圈、当前圈号；不展示速度和 GPS 细节。

- `lap-live-minimal-v1.png`
  - 圈速测试实时页早期竖屏参考。
  - 默认驾驶模板只展示当前圈计时、相对最佳差值、最佳圈、当前圈号；不展示速度和 GPS 细节。

- `ble-scan-sheet-v2-calmer.png`
  - BLE 设备扫描/选择 bottom sheet 方向。
  - 工具型弹层，低视觉强度。

- `app-icon-round-v1.png`
  - 圆形 App icon 概念图。
  - v1 赛道轨迹方向，已降级为对比参考。

- `app-icon-rounded-square-v1.png`
  - 圆角方形 App icon 概念图。
  - v1 赛道轨迹方向，已降级为对比参考。

- `app-icon-round-v2-speed-gauge.png`
  - 圆形 App icon 概念图。
  - 当前推荐方向：速度仪表 / 指针 / 遥测刻度，不使用赛道轨迹。

- `app-icon-rounded-square-v2-speed-gauge.png`
  - 圆角方形 App icon 概念图。
  - 当前推荐方向：速度仪表 / 指针 / 遥测刻度，不使用赛道轨迹。

## How To Use

CC / Claude Code 应该用这些图判断：

- 视觉强度。
- 信息层级。
- 组件形态。
- 页面大结构。
- 间距和密度大致感觉。

不应该用这些图判断：

- 精确像素。
- 最终图标资产。
- 最终字体文件。
- 可直接裁切的背景/按钮/卡片。

落地后请用真实 App 截图与这些参考图对比，再逐项调整视觉细节。
