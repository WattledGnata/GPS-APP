## Why

GPS App 已进入真实用户路测阶段（release 包发给车手在赛道实跑）。当线上出现问题（圈速异常、分段不触发、信号丢失、崩溃），开发侧目前**没有任何途径拿到用户设备上的诊断数据**：

- App 已用 `FileLogger`（`feature/test/.../FileLogger.kt`）持续写 `filesDir/debug_log.txt`（release 包也写 DEBUG 级，5MB×2 rotation）；圈速 telemetry 二进制流写在 `filesDir/telemetry/*.bin`；session/圈速/过线事件元数据在 Room `databases/race_chrono_database`(+`-wal`/`-shm`)。但这些都锁在用户设备的应用私有目录里。
- `adb run-as` 只对 debuggable 包有效，**release 包无法 pull**；让普通车手自己跑 adb 完全不现实。
- 结果：每次线上问题只能靠用户口述复现，开发无法定位根因。典型实例——2026-06-19 天府赛道圈速偏差排查，全靠开发本人用自己的 debug 包 `run-as` 才拿到 session `142605bb` 数据做出诊断；真实用户的同类问题则完全拿不到数据。

需要一个让用户**一键把诊断数据上传到我们服务器**的通道，开发凭返回的 `logId` 直接拉取排查，无需用户折腾 adb。

## What Changes

- **暗门入口**：`SettingsScreen` 显示 App 版本号，连点 7 次（Android 开发者模式风格）弹出"诊断上传"面板。普通用户看不到、不会误触；内部人员知道手势。
- **诊断数据打包**：把 `filesDir/telemetry/*.bin` + `databases/race_chrono_database`(+`-wal`/`-shm`) + `filesDir/debug_log.txt`(+`.1`) 打包成单个 zip。**明确排除 `filesDir/video/`**（GB 级，不上传）。
- **元数据采集**：设备型号、Android ID、`versionName`/`versionCode`、打包时间戳（毫秒）、可选用户填写的工单号/备注。
- **隐私确认**：上传前弹确认对话框（release 给真实用户，必须告知"将上传行驶轨迹/诊断数据用于排查"），用户同意才上传。
- **上传链路**：复用 `core/network` 的 OkHttp，新增 multipart `POST /api/v1/logs`（baseUrl/token 走 BuildConfig，与 livetiming 同源），上传带进度，成功返回 `logId` 显示给用户（用户报给开发对应）。
- **服务端契约（跨 repo）**：在独立的 livetiming-server（Go）新增 `POST /api/v1/logs` 端点接收 multipart（zip + meta），落存储返回 `logId`。**本 round 工件提供 API 契约文档**，Go 实现在该 repo 完成（不在本 repo scope）。

## Capabilities

### New Capabilities
- `diagnostic-log-upload`: 发布版应用内的诊断数据打包与上传能力——暗门入口、本地数据打包（含 video 排除规则）、元数据采集、隐私确认、multipart 上传与结果反馈，以及与服务端的上传 API 契约。

### Modified Capabilities
（无。不改任何现有 capability 的 spec 级行为：`FileLogger`/telemetry/Room 的**写入**行为完全不变，本 round 仅新增**读取并打包**；livetiming 现有 `/api/v1/laps` 上传不变，新增的是独立 `/api/v1/logs` 端点。）

## Impact

**客户端（本 repo）受影响模块路径**
- `feature/test/src/main/.../ui/settings/SettingsScreen.kt`：加版本号显示 + 连点 7 次暗门入口
- `feature/test/src/main/.../ui/...`（新增）：诊断面板 Composable（工单号输入 + 隐私确认 + 上传进度/结果 + logId 展示）
- `feature/test/src/main/.../diagnostic/`（新增）：日志打包 use case（读 filesDir/databases → zip，排除 video）+ 上传 orchestrator（复用 `livetiming/LapUploadOrchestrator` 状态机模式）
- `core/network/src/main/.../`（新增）：`DiagnosticLogUploader`（multipart `POST /api/v1/logs`，复用 `LivetimingClient` 的 OkHttp/baseUrl/token）
- `feature/test/src/main/.../di/AppModule.kt`：注册打包 use case + uploader + orchestrator
- `app/build.gradle`：BuildConfig 新增 logs 端点路径常量（baseUrl/token 复用 livetiming，不新增）
- 权限：`INTERNET` 已有（`AndroidManifest.xml:20`），无需新增
- 测试：打包逻辑（排除 `video/`、文件缺失容错、zip 完整性）、上传 multipart mapper、orchestrator 状态机、release variant 暗门可达性

**服务端（独立 livetiming-server Go repo，非本 repo scope）**
- 新增 `POST /api/v1/logs` 端点（multipart：zip 文件 + meta 字段 → 落存储 → 返回 `logId`）。API 契约见 `design.md` 与 `specs/diagnostic-log-upload/spec.md`。

**双端边界**
- 仅涉及接收端 gps-app（本 repo）+ 服务端；**不涉及发射端 simulator**，不涉及 RaceChrono BLE 协议。

**协议兼容性**
- 不涉及 RaceChrono BLE 公共协议改动。
- 新增 `/api/v1/logs` 为独立 HTTP 端点，与现有 `/api/v1/laps` 正交，无兼容性影响。

**隐私/合规**
- 上传内容含位置 / 行驶轨迹数据，release 面向真实用户，必须有明确的用户同意环节（隐私确认弹窗）方可上传。
