## 1. 预置几何

- [x] 1.1 计算并替换 TFIC、XIC、NIC、V1 起终点门的 120 米对称端点，保持中心、方向和 `passDirection` 不变
- [x] 1.2 计算并替换 debug-only 天投泊寓起终点门的 120 米对称端点

## 2. 几何契约测试

- [x] 2.1 新增 main 预置门宽与中心保持测试
- [x] 2.2 扩展 debug variant 测试，锁定天投泊寓 120 米起终点门宽

## 3. 验证

- [x] 3.1 运行 `:feature:test:testDebugUnitTest` 与 `:feature:test:testReleaseUnitTest` 相关预置赛道测试
- [x] 3.2 使用 TFIC、NIC、V1 现有实测轨迹复核目标方向过线序列，并记录扩宽前后差异
- [x] 3.3 运行 `openspec validate widen-preset-start-finish-gates --strict`
