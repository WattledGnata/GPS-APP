## 1. 时间轴桥接语义

- [x] 1.1 扩展 `VideoTimelinePlan`，分离实际 `coverage` 与历史相邻段 `isExportable`，仅允许两侧存在有效相邻 slices 且不超过 5 秒的圈内 gap 桥接。
- [x] 1.2 修改 `VideoTimelinePlanTest`，新增三圈轮换后第四圈圈头短 gap 可桥接、单侧短缺失不可桥接、5001ms 长 gap 禁止、完整单段不回归测试。

## 2. 回放与导出入口

- [x] 2.1 修改 `LapVideoPlaybackScreen`，让按钮与提示消费统一 `isExportable`；桥接圈显示明确的“分段衔接”状态，仍保留 `PARTIAL` 覆盖事实。
- [x] 2.2 修改 `VideoExportService`，使用与 UI 相同的桥接 gate，并保留长 gap/无录像失败清理与日志。
- [x] 2.3 修改相关 UI/Service 契约测试，锁定历史最快圈可以进入多段带图层导出，避免 UI 放行而 Service 二次拒绝。

## 3. 验证与交付

- [x] 3.1 运行 `VideoTimelinePlanTest`、`VideoExportClipTest` 及 feature:test 聚焦测试，修复全部回归。
- [x] 3.2 运行 `openspec validate rescue-auto-rotated-lap-video-export --type change --strict`、`git diff --check` 与 release 编译。
- [ ] 3.3 递增至 1.0.8，构建并核验签名 APK、版本信息、SHA-256；覆盖安装测试设备验证历史数据不被清除。
- [ ] 3.4 使用车友原设备覆盖安装并验证最快圈跨段导出、成片 overlay 和相册落盘；该现场验收需要车友设备配合，自动化与本地设备不能替代。
- [x] 3.5 发布蒲公英、创建提交与 `release+1.0.8` tag；push 分支/tag 前必须再次取得用户明确确认。
