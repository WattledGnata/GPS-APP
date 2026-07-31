## 1. 样式契约与持久化

- [x] 1.1 新增 `VideoOverlayStyle` 稳定枚举及未知值降级到 `FLAT` 的解析函数；新增枚举解析单元测试。
- [x] 1.2 新增 `VideoOverlayStylePreferences`，以 Preferences DataStore + Flow 保存上次选择并默认 `FLAT`；新增默认值、保存恢复和损坏值降级测试。
- [x] 1.3 在 Koin 中注册样式偏好仓库，保证全屏回放与内嵌回放消费同一实例。

## 2. 三套共享 HUD 绘制

- [x] 2.1 定义统一 `OverlayHudFrame` 和按短边缩放的布局几何模型，覆盖 `FLAT`、`RAIL`、`MECHANICAL`；新增三套布局的边界/安全区单元测试。
- [x] 2.2 在 `OverlayCanvasPainter` 实现三套整帧 HUD 入口，复用现有速度、G 值、小地图数学与统一数值格式。
- [x] 2.3 新增 Android instrumented 预览/截图宿主，使用固定遥测数据分别渲染三套样式，供真机视觉核对。

## 3. 回放体验与选择器

- [x] 3.1 将 `OverlayHud` 改为透明 Compose Canvas 调用共享整帧绘制入口，全屏与内嵌回放按各自画布尺寸使用同一实现。
- [x] 3.2 在全屏回放加入 HUD 样式选择入口和三张可点击样式卡，点击即时预览并持久化；选择器控制层不得进入导出 overlay。
- [x] 3.3 保持 seek、暂停、跨段和小地图降级语义，运行既有视频回放测试并补样式切换不重建播放器的 contract test。

## 4. 导出样式透传

- [x] 4.1 `VideoExportService.start` 冻结当前样式到 `EXTRA_OVERLAY_STYLE`，Service 对缺失/非法值降级 `FLAT`；新增 Intent 值解析单元测试。
- [x] 4.2 单段与跨段 pipeline 将冻结样式传入 `ExportOverlayRenderer`，后者调用与回放相同的整帧 HUD 绘制入口。
- [x] 4.3 新增单段/跨段样式透传 contract test，证明导出期间切换全局偏好不改变运行中任务。

## 5. 验证与交付

- [x] 5.1 运行 `:feature:test:testDebugUnitTest`、`:feature:test:connectedDebugAndroidTest`（有设备时）及 `:app:assembleDebug`。
- [x] 5.2 `openspec validate selectable-video-overlay-styles --strict` 通过，真机或渲染宿主产出三套实际样式截图。
- [x] 5.3 提交本变更实现；push 与 Release 发布属于高风险外部动作，需用户当次明确要求后执行。

## 6. 真机反馈修正

- [x] 6.1 为 `MECHANICAL` 速度表补充随速度扫动的指针、刻度、尾针和中心轴帽；新增角度映射单元测试并构建 Debug APK，不占用用户设备执行测试。

## 7. 全屏系统栏修正

- [x] 7.1 将沉浸式系统栏控制限定在 `LapVideoPlaybackScreen`，进入时隐藏系统栏、离开时恢复现场状态；将全局主题状态栏兜底色改为黑色；完成本机构建与单测，不连接用户设备。
