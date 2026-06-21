## Context

接收端 gps-app 已在真实用户的 release 包上运行。诊断数据（`FileLogger` 的 `debug_log.txt`、`telemetry/*.bin` 轨迹流、Room `race_chrono_database` 圈速元数据）持续生成并落在应用私有目录，但 release 包无法 `adb run-as` 导出，开发拿不到线上设备数据。

现有可复用地基：
- `core/network`：`LivetimingClient`（Retrofit + OkHttp，baseUrl/token 取 `BuildConfig.LIVETIMING_BASE_URL` / token，local.properties 注入）+ `LivetimingUploader`（现有 `POST /api/v1/laps`）。
- `feature/test/.../livetiming/LapUploadOrchestrator`：StateFlow 状态机式上传编排，本 round 复用其模式。
- `FileLogger`（`feature/test/src/main`）：release 也写 DEBUG 级日志。
- `SettingsScreen`（`feature/test/.../ui/settings`）：暗门入口宿主。
- `INTERNET` 权限已有。

约束：不改 RaceChrono BLE 公共协议；不改 FileLogger/telemetry/Room 的写入行为；release 给真实用户必须有上传同意环节。

## Goals / Non-Goals

**Goals:**
- 让真实用户在 release 包上一键把全量诊断数据（日志 + 轨迹 bin + Room 库）打包上传到我们服务器。
- 入口隐蔽（连点版本号 7 次），普通用户不误触。
- 上传前明确告知并取得用户同意（隐私）。
- 上传成功返回 `logId`，用户报给开发即可定位该次上传。
- 给出 livetiming-server 的 `POST /api/v1/logs` API 契约，供 Go repo 实现。

**Non-Goals:**
- 不上传 `video/`（GB 级，明确排除）。
- 不做服务端 Go 实现（在独立 repo，本 round 只给契约）。
- 不做自动/后台上传（仅用户主动暗门触发）。
- 不做日志查看/筛选 UI（只打包上传）。
- 不改任何现有数据写入链路或 schema。

## Decisions

### Decision 1：暗门入口 = SettingsScreen 版本号连点 7 次
- **选择**：在 `SettingsScreen` 显示 `versionName (versionCode)`，对其 `clickable` 累计点击；3 秒滑动窗口内累计达 7 次触发诊断面板，窗口超时计数清零。
- **Alternatives**：
  - (A) 设置里固定可见的"上传日志"按钮 —— 拒绝：普通用户会乱点、误传，失去"暗门"语义。
  - (B) 拨号式密码（输入框输 `*#log#*`）—— 拒绝：需要额外输入控件，SettingsScreen 无现成输入框，交互更重。
  - (C) 隐藏长按手势 —— 拒绝：无标准心智，内部人员也容易忘；连点版本号是 Android 开发者模式的既有心智，团队零学习成本。
- **Rationale**：复用 Android 开发者模式的通用手势，隐蔽性足够 + 内部零学习成本 + 实现最轻（一个计数器 + 时间窗）。

### Decision 2：打包 = zip 单文件，落 cacheDir，上传后删
- **选择**：用 `java.util.zip.ZipOutputStream` 把目标文件打成单个 `diag_<timestamp>.zip` 写到 `cacheDir`；上传成功或失败后删除临时 zip。
- **Alternatives**：
  - (A) 多文件分别 multipart 上传 —— 拒绝：服务端要处理可变数量 part，契约复杂，且无法整体压缩省流量。
  - (B) base64 塞 JSON —— 拒绝：体积膨胀 33%，大文件下内存压力大。
- **Rationale**：单 zip = 服务端契约简单（一个 file part）+ 压缩省流量（日志文本压缩率高）+ 临时文件易清理。

### Decision 3：Room 库打包 = main + wal + shm 三件套，不在用户设备强制 checkpoint
- **选择**：打包 `race_chrono_database` 连同 `-wal`、`-shm` 三个文件（缺失则跳过）。接收端开发用 sqlite 打开自动重建合并 WAL。
- **Alternatives**：
  - (A) 打包前 `PRAGMA wal_checkpoint(TRUNCATE)` 再只拷 main —— 拒绝：上传时可能有 session 正在写库，强制 checkpoint 干扰正在进行的写、有损坏风险，且需要拿到 Room 的 SupportSQLiteDatabase 句柄。
  - (B) 只拷 main 文件 —— 拒绝：会丢失 WAL 中尚未 checkpoint 的最新数据（恰恰是刚出问题那段）。
- **Rationale**：三件套是 sqlite 官方推荐的"热备份"最简形式；不动用户设备上的库状态，零干扰、零损坏风险。**实战依据**：2026-06-19 开发本人就是 pull 了 `race_chrono_database` + `-wal` + `-shm` 三件套用 sqlite 正常读出最新 session。

### Decision 4：打包范围 = 全量（所有 telemetry bin + 整个 Room + 全部日志），排除 video
- **选择**：`telemetry/` 下全部 `*.bin` + Room 三件套 + `debug_log.txt`(+`.1`)；硬排除 `video/`。
- **Alternatives**：
  - (A) 只最近 1 个 session 的 bin —— 拒绝：用户报问题时未必是最后一次；全量才能让开发自由选择排查哪段（用户已拍"全量"）。
  - (B) 含 video —— 拒绝：单文件 GB 级，上传不现实。
- **Rationale**：用户明确选全量；排除 video 后预估 zip 后 ~5–15MB（bin 累计 ~2MB + Room ~3MB + 日志 ~9MB，文本压缩率高），可接受。

### Decision 5：上传 = OkHttp multipart `POST /api/v1/logs`，复用 livetiming baseUrl/token
- **选择**：新增 `DiagnosticLogUploader`（`core/network`），复用 `LivetimingClient` 的 OkHttp 实例与 `BuildConfig` baseUrl/token；`MultipartBody`：`file`(zip, application/zip) + 各 meta 字段（text/plain）；带上传进度回调。
- **Alternatives**：
  - (A) Retrofit `@Multipart` 接口 —— 可行但进度回调要包 `RequestBody` 自定义，与现有 Retrofit 风格混用；裸 OkHttp 调用更直接。
  - (B) 直传对象存储（OSS/S3 预签名）—— 拒绝：当前无对象存储，且要服务端先发预签名 URL，多一跳；统一走 livetiming-server 最省事（用户已拍"复用 livetiming-server"）。
- **Rationale**：复用现成 OkHttp + token，服务端单端点，进度回调用自定义 `RequestBody` 包装即可。

### Decision 6：隐私确认 = 每次上传前弹确认对话框
- **选择**：诊断面板点"上传"后，先弹 `AlertDialog`"将上传你的行驶轨迹与诊断数据，用于排查问题，是否继续？"，确认才真正打包上传。
- **Alternatives**：
  - (A) 首次同意后记住 —— 拒绝：诊断上传低频，每次明确同意对隐私最稳，也避免"忘了自己同意过"。
- **Rationale**：release 面向真实用户，位置数据属敏感信息，每次显式同意合规且实现简单。

### Decision 7：元数据 = 设备型号 + Android ID + 版本 + 时间戳 + 可选工单号
- **选择**：`Build.MANUFACTURER+MODEL`、`Settings.Secure.ANDROID_ID`、`BuildConfig.VERSION_NAME`/`VERSION_CODE`、打包毫秒时间戳、用户可选填工单号/备注。随 multipart form 字段上送。
- **Alternatives**：
  - (A) 自生成 UUID 持久化 —— 拒绝：ANDROID_ID 无需权限、随设备稳定、足够区分，省一套持久化。
- **Rationale**：ANDROID_ID + 时间戳 + 工单号三重标识，足够开发对应到具体用户/某次问题。

### Decision 8：上传编排 = StateFlow 状态机（复用 LapUploadOrchestrator 模式）
- **选择**：`DiagnosticUploadOrchestrator` 暴露 `StateFlow<DiagnosticUploadState>`：`Idle / Packing / Uploading(progress 0..1) / Success(logId) / Failed(reason)`；打包 IO + 网络在 `Dispatchers.IO`，UI 订阅渲染。
- **Rationale**：与现有 `LapUploadOrchestrator` 一致的心智，纯函数式状态流，UI 只读渲染，易单测。

### Decision 9：跨 repo API 契约（livetiming-server 实现）

```
POST /api/v1/logs
Content-Type: multipart/form-data
Authorization: Bearer <token>   # 复用 livetiming token

Form parts:
  file        (required) : application/zip, 字段名 "file", 诊断 zip 包
  deviceModel (required) : text, 如 "vivo V2405A"
  androidId   (required) : text, Settings.Secure.ANDROID_ID
  versionName (required) : text, 如 "1.0.1"
  versionCode (required) : text, 如 "2"
  capturedAt  (required) : text, 毫秒时间戳
  ticket      (optional) : text, 用户填的工单号/备注

Response 200 application/json:
  { "logId": "<server-generated id>" }

Errors:
  400 : 缺 file 或必填 meta 字段
  401 : token 无效
  413 : 超过服务端大小上限（若设置）
```

服务端存储后端（对象存储 vs 磁盘）、`logId` 格式由 livetiming-server 自行决定，本契约只约束接口形状。

## Risks / Trade-offs

- **[全量 zip 偏大，弱网上传慢/超时]** → 排除 video；UI 显示进度；OkHttp 上传超时放宽到 120s；future 可加"仅最近 N session"选项与大小上限提示（本 round 不做）。
- **[Room WAL 一致性：上传时 session 正在写库]** → 打包 main+wal+shm 三件套交服务端 sqlite 重建，不在用户设备强制 checkpoint，零干扰零损坏。
- **[隐私：上传含位置/轨迹]** → 仅暗门主动触发 + 每次显式同意弹窗 + 文案明确告知用途。
- **[连点暗门误触]** → 连点需 3 秒窗口内 7 次，超时计数清零；普通用户极难凑齐。
- **[上传失败本身在 release 无 logcat 可查]** → 上传全程也写 `FileLogger`（下次打包自然带上）；UI 明确区分"打包失败/网络失败/服务端拒绝(状态码)"。
- **[Android ID 在个别 ROM 不稳定/可被重置]** → 接受；配合时间戳 + 工单号兜底标识，不依赖其全局唯一。
- **[cacheDir 空间不足以放 zip]** → 打包前 try/catch，IO 异常归 `Failed(打包失败)`，提示用户清理空间。

## Migration Plan

- **客户端**：纯新增能力，不改现有数据写入/schema，无数据迁移。
- **服务端**：新增独立端点 `/api/v1/logs`，独立部署，不影响 `/api/v1/laps`。
- **Rollback**：暗门入口可通过移除 SettingsScreen 的连点计数禁用（客户端发版）；服务端端点独立可随时下线，不影响主链路。

## Open Questions

- 服务端存储后端与 `logId` 格式 → 交 livetiming-server repo 决定，契约不约束。
- 是否对 zip 设硬大小上限（客户端预检/服务端 413）→ 本 round 不设，按真机实测体量再定（follow-up）。
- 工单号是否要做成下拉（关联已知工单）→ 本 round 先纯文本输入，future 可对接工单系统。
