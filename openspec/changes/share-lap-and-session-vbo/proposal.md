## Why

现有 VBO 入口只把单圈文件写到用户选择的设备存储位置，无法直接完成“把圈速证据发给朋友或分析工具”的主要用户目标；同时单圈切片不足以分析整场极限圈、慢圈和稳定性。现在需要把入口升级为分享优先，并补齐整节次原始 VBO。

## What Changes

- 将单圈详情页原“导出 VBO”按钮改为 VBO 菜单，提供“分享单圈 VBO”和“分享整节 VBO”两个操作。
- 两个操作均在应用缓存中生成 `.vbo` 临时文件，并直接拉起 Android 系统分享面板，不再要求用户先选择设备存储位置。
- 保留现有单圈 VBO 的数据质量、置信度和字段真实性规则。
- 新增整节次 VBO：顺序导出 session 全部有效原始遥测样本，并携带赛道、session、门线和圈次摘要；缺少门线或完整圈时仍可分享原始数据并明确标注。
- 分享文件使用只读 `content://` URI 和临时读取授权；不接入微信 SDK，也不新增公共存储权限。

## Capabilities

### New Capabilities

- `vbo-session-sharing`: 定义单圈/整节 VBO 的菜单入口、文件生成、临时缓存、系统分享和失败反馈契约。

### Modified Capabilities


## Impact

- `feature/test/src/main/java/com/blazepush/feature/test/export/`: 扩展 VBO 格式化与临时分享文件生成。
- `feature/test/src/main/java/com/blazepush/feature/test/ui/tracktech/LapDetailScreen.kt`: 将原存储导出入口改成双选项分享菜单。
- `core/data/src/main/java/com/blazepush/core/data/repository/TelemetryRepository.kt`: 提供 session 级完整遥测读取边界。
- `app/src/main/AndroidManifest.xml`、`app/src/main/res/xml/`: 声明受限缓存目录的 `FileProvider`。
- `feature/test/src/test/`、`core/data/src/test/`: 增加格式、分享准备和完整 session 读取测试。

### 协议兼容性

RaceChrono BLE 公共协议和实时采集链路不变；新增能力只读取既有持久化遥测并生成 RaceLogic VBO 文件。
