## 1. Session 数据与 VBO 格式

- [x] 1.1 在 `TelemetryRepository` 增加整节次导出快照读取 API，并新增测试覆盖圈外样本、crossing/evidence 和缺失 binary 降级
- [x] 1.2 实现流式 `RaceLogicVboSessionExporter`，输出完整 session 样本、可选 laptiming、圈次摘要和真实字段省略说明
- [x] 1.3 新增整节次格式器单元测试，覆盖门线坐标、样本顺序、无完整圈、无 Track、无效样本和有界 Writer 接口

## 2. 临时文件与 Android 分享

- [x] 2.1 实现专用 `shared_vbo` 缓存文件写入、过期清理和分享 Intent 构造，并新增纯逻辑测试
- [x] 2.2 在 app manifest 和受限 paths XML 中配置只暴露 VBO 缓存子目录的 `FileProvider`

## 3. 单圈详情菜单交互

- [x] 3.1 将 `LapDetailHeader` 原单一导出按钮改为含“导出单圈 VBO / 导出整节 VBO”的菜单
- [x] 3.2 将单圈既有格式器改接缓存分享，并接入整节次 repository/formatter 分享；补齐生成中、拒绝和启动失败反馈
- [x] 3.3 更新中英文资源与 Compose/契约测试，验证不再使用 SAF `CreateDocument` 且两个菜单项均可达

## 4. 验证

- [x] 4.1 运行 VBO、repository、UI 契约相关 JVM 测试并修复失败
- [x] 4.2 运行 `:feature:test:compileDebugKotlin`、OpenSpec strict 校验和 `git diff --check`，记录未覆盖的微信真机分享验收边界
