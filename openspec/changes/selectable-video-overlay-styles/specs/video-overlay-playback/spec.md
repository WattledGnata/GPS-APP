## MODIFIED Requirements

### Requirement: 视频实时叠加遥测 HUD overlay（纯播放渲染，不烧录不导出）

`LapVideoPlaybackScreen` SHALL 用 media3 ExoPlayer 播放 session 视频，并按用户选择的 `FLAT`、`RAIL` 或 `MECHANICAL` 样式实时叠加遥测 HUD（速度 + 圈速计时与最佳圈差值 + G 值 + 赛道小地图当前位置点）。全屏回放与 `LapVideoPanel` 内嵌回放 MUST 消费同一个样式偏好和同一共享 Canvas HUD 绘制入口；overlay 随播放进度（含 seek / 暂停）跳变。

实现 MUST 满足：

1. **播放器**：MUST 用 `androidx.media3.exoplayer.ExoPlayer` + `androidx.media3.ui.PlayerView`；PlayerView 垫在 root `Box` 最底当背景，透明 HUD Canvas 浮其上。
2. **同步口径**：每刷新 tick MUST 计算帧 wall-clock，经 `VideoTelemetrySync.findNearestSampleIndex` 取最近邻样本；MUST NOT 用 `System.currentTimeMillis()` 当前墙钟代替视频时间轴。
3. **刷新机制**：MUST 用生命周期内 Compose 协程持续更新 overlay；seek / 暂停后 MUST 刷新到正确帧。
4. **样本读取**：进屏 MUST 在 `Dispatchers.IO` 一次性读整 session 样本进内存；轮询期 MUST NOT 做 binary IO。
5. **G 值离线重算**：binary 无加速度字段时，MUST 由 speed/bearing/时间差离线计算纵向/横向 G 并平滑。
6. **圈速 + delta**：当前圈 elapsed 与最佳圈 delta MUST 沿用既有 wall-clock、圈窗口和 `RealtimeDeltaCalculator` 口径；无有效值时显示占位。
7. **样式**：MUST 支持 `FLAT`（默认简洁平铺）、`RAIL`（统一底栏）、`MECHANICAL`（合并机械仪表簇）三套样式；三套均 MUST 显示相同五类遥测信息，并按画布尺寸等比缩放。
   `MECHANICAL` 的速度仪表 MUST 具有随速度扫动的清晰指针、刻度与中心轴帽，不得只用进度弧和数字模拟机械表。
8. **共享绘制**：全屏回放和内嵌回放 MUST 通过 `OverlayCanvasPainter` 的整帧 HUD 入口绘制，MUST NOT 各自复制布局。
9. **控制层隔离**：HUD 样式选择器、播放按钮、Slider 与导出进度 MUST 位于共享 HUD Canvas 外，MUST NOT 被视为视频 overlay 图层。
10. **生命周期**：离开播放屏时 MUST `player.release()`，更新协程 MUST 随 Composable 取消。
11. **公共协议边界**：MUST NOT 改 GPS 接收链路、binary 格式、crossing、`LapTimingEngine` 或 Room schema。

#### Scenario: 三种样式随选择即时切换且数据不变

- **GIVEN** 视频正在播放，当前帧速度、圈时、delta、G 值和地图位置均已解析
- **WHEN** 用户依次选择 `FLAT`、`RAIL`、`MECHANICAL`
- **THEN** HUD 布局 MUST 即时切换
- **AND** 三种样式展示的遥测数值 MUST 完全相同
- **AND** 视频 MUST NOT 因样式切换重新 prepare 或跳回起点

#### Scenario: 全屏与内嵌回放使用同一样式

- **GIVEN** 用户已选择 `RAIL`
- **WHEN** 同一 session 分别出现在全屏回放和详情页内嵌回放
- **THEN** 两处 MUST 都使用 `RAIL`
- **AND** 布局 MUST 由同一共享 Canvas HUD 入口按各自画布尺寸计算

#### Scenario: 机械速度表指针随速度扫动

- **GIVEN** 当前选择 `MECHANICAL`
- **WHEN** 速度从零增加到仪表上限
- **THEN** 速度指针 MUST 从量程起始角连续扫到终止角
- **AND** 指针、刻度与中心轴帽 MUST 在视频画面上保持清晰可辨

#### Scenario: seek 与暂停后 HUD 同步跟随

- **GIVEN** 视频正在播放且 HUD 显示中
- **WHEN** 用户 seek 到新位置或暂停播放
- **THEN** HUD MUST 更新到新位置对应的样本数据
- **AND** 当前选择的样式 MUST 保持不变

#### Scenario: 反例——无效样式不得导致空白或崩溃

- **GIVEN** 偏好值无法解析
- **WHEN** 回放 HUD 渲染
- **THEN** MUST 降级到 `FLAT`
- **AND** 速度、圈时、delta、G 值和小地图 MUST 继续正常显示

#### Scenario: 反例——离开播放屏释放播放器

- **GIVEN** 播放屏正在播放
- **WHEN** 用户离开页面
- **THEN** MUST 调用 `player.release()`
- **AND** HUD 更新协程 MUST 停止
