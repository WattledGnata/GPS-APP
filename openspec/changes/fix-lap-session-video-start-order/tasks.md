## 1. Session 启动顺序

- [x] 1.1 进入圈速页立即启动 Room Session 持久化，GPS/REC 复用同一 Mutex 入口。
- [x] 1.2 快速退出先等 Session 创建再收尾，保留 0s/几秒短 Session。
- [x] 1.3 END 与 REC await 交错时关闭 active gate，不允许结束后启动 CameraX。

## 2. CameraX 状态与视频绑定

- [x] 2.1 `startRecording` 改为非空 Session 参数并新增 `Starting`。
- [x] 2.2 Back/END/onDispose 在 Starting/Recording 状态先 stop，迟到 Start 不回退 Stopping。
- [x] 2.3 Finalize 后等待视频段绑定写库，再进入 Idle/Error 并通知离页。

## 3. 强杀恢复与兼容

- [x] 3.1 新视频文件名固化 Session UUID 与 request wallClock。
- [x] 3.2 冷启动幂等恢复非空、Session 存在、尚未绑定的新格式 MP4。
- [x] 3.3 旧纯时间戳、未知 Session、空文件 fail closed，不猜归属。

## 4. 1.0.8 验证

- [x] 4.1 单测覆盖进页即建 Session、快速退出短 Session、恢复幂等与 CameraX 源码契约。
- [x] 4.2 运行 core/data 与 feature/test 相关测试、app 编译、OpenSpec strict 和 diff check。
- [x] 4.3 升级 `versionCode 9 / versionName 1.0.8`，构建 Release APK 并核对版本、包名、签名和 SHA-256。
- [ ] 4.4 真机覆盖快速进出、Back、Home、强杀重启与恢复段播放。
