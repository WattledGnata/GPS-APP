## 自动化与构建

- `:feature:test:testDebugUnitTest`：通过。
- `:core:data:testDebugUnitTest`：通过。
- `:feature:test:compileDebugKotlin`：通过。
- `:app:assembleDebug`：通过。
- `:app:assembleRelease`（含 lint vital）：通过。
- APK：`BlazePush_v1.0.4_release.apk`，versionCode `5`，57,143,040 bytes。
- APK Signature Scheme v2：通过；签名主体 `CN=BlazePush`。
- SHA-256：`f601c7d982195ad098e706616de33c3e663cd313d2e5aee45f20df721c949aac`。

## 下载

- 蒲公英下载页：`https://www.pgyer.com/blazepush`。
- 发布成功页确认 BlazePush 上传成功，下载页确认最新版本为 `1.0.4`。
- 更新说明：优化视频分段回放与导出体验，调整应用图标显示。
- 蒲公英页面的 Build 字段显示 `4`；本地上传 APK 经 `aapt` 核验的 Android `versionCode` 为 `5`。

## 设备冒烟

- vivo V2405A `10AF5T0XE3004ZX`：独立 Debug 包 `com.blazepush.debug` 安装成功，与 `com.blazepush` 并存。
- Debug 桌面名称和缩小后的图标已在真机确认。
- 为保留正式包数据，未在该设备卸载或清除 `com.blazepush`；本次正式签名 1.0.4 Release 未在该设备覆盖安装。
- 当前设备没有可直接复用的真实跨段圈录像，因此跨段编码结果仍需手机路测确认。

## 手机路测清单

1. 通过上述 URL 下载并覆盖安装 Release APK；如系统提示签名冲突，不要卸载，先保留数据并反馈旧包来源。
2. 进入 `Records → Laps → Session`：
   - 主内容流中不再出现通栏红色删除按钮。
   - 右上角 `⋮` 中出现“删除本场全部录像”。
   - 确认框显示分段数量、预计释放空间，并明确保留圈速与遥测。
3. 打开普通单段圈：
   - 小窗播放、拖动、图表游标联动正常。
   - 全屏底部控制坞可明确播放/暂停、拖动和导出。
4. 打开跨段圈：
   - 小窗和全屏都能越过分段边界。
   - 时间轴用青色显示有画面区间；真实长缺失用红色及文字提示。
   - 短轮换 gap 不应停在黑屏中等待。
5. 导出跨段圈：
   - 显示“跨 N 段 · 可导出”。
   - 导出过程可取消；取消后无相册半成品。
   - 成功后留在应用内，由用户选择“查看 / 分享 / 完成”，不自动弹分享。
   - 检查输出视频段边界前后画面、声音、圈时、速度、G 值和小地图连续性。
6. 部分缺失圈：
   - UI 显示缺失秒数或头尾缺失原因。
   - 不允许生成误导性的完整视频。
7. 删除本场全部录像：
   - 删除后播放入口和录像统计消失。
   - Session、圈速列表、最佳圈、crossing 和遥测图表仍存在。
8. 若发生卡顿、导出失败或画面错位，从诊断面板上传日志，并记录 Session、Lap 编号、分段边界位置和失败时间。
