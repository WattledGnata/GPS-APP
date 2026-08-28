## 1. 规格与状态映射

- [x] 1.1 明确动态 Main deadline、滤波速度权威和范围边界。
- [x] 1.2 新增纯 presentation mapper，覆盖 LIVE、静止 0、stale、无 Main、等待定位、恢复稳定和 BLE 断开。
- [x] 1.3 新增 mapper 单元测试。

## 2. 圈速 HUD

- [x] 2.1 收集 `filteredSpeedKmh` 并用单调 ticker 投影 Main age。
- [x] 2.2 实现中心速度岛和顶部 GPS 心跳，保留四个圈速指标及异常层级。
- [x] 2.3 增加 HUD 源码契约测试。

## 3. 验证

- [x] 3.1 运行相关 JVM 和契约测试。
- [x] 3.2 运行模块编译与整包 Debug 构建。
- [x] 3.3 运行严格 OpenSpec 校验和 `git diff --check`。
- [ ] 3.4 横屏视觉、真实 Main 心跳、BLE 硬中断和录像/相机页仍需真机验收。
