## ADDED Requirements

### Requirement: 诊断上传暗门入口

系统 SHALL 在 `SettingsScreen` 展示应用版本号，并在用户于 3 秒滑动窗口内累计点击版本号达 7 次时打开诊断上传面板；计数在窗口超时后 MUST 清零。普通用户的偶发点击 MUST NOT 触发面板。

#### Scenario: 3 秒内连点版本号 7 次打开面板
- **WHEN** 用户在 3 秒内连续点击版本号区域第 7 次
- **THEN** 系统打开诊断上传面板

#### Scenario: 连点不足 7 次不打开面板（反例）
- **WHEN** 用户连续点击版本号 6 次后停止
- **THEN** 系统 MUST NOT 打开诊断上传面板，且不显示任何提示

#### Scenario: 连点间隔超时计数清零（反例）
- **WHEN** 用户点击版本号 4 次，停顿超过 3 秒后再点击 3 次
- **THEN** 计数已清零，第二段的 3 次不与前 4 次累加，系统 MUST NOT 打开面板

### Requirement: 诊断数据全量打包

系统 SHALL 把 `filesDir/telemetry/*.bin`、Room 数据库三件套（`race_chrono_database` 及存在的 `-wal`/`-shm`）、`filesDir/debug_log.txt` 与存在的 `debug_log.txt.1` 打包为单个 zip。系统 MUST NOT 把 `filesDir/video/` 下任何文件纳入打包。目标文件缺失时 MUST 跳过该文件而非整体失败。

#### Scenario: zip 含轨迹、数据库与日志
- **WHEN** 设备上存在 telemetry bin、Room 库与 debug_log.txt 时触发打包
- **THEN** 生成的 zip 同时包含这些 telemetry `*.bin`、`race_chrono_database`(+存在的 `-wal`/`-shm`) 与 `debug_log.txt`

#### Scenario: 打包排除 video 目录（反例）
- **WHEN** 设备 `filesDir/video/` 下存在 mp4 文件时触发打包
- **THEN** 生成的 zip 中 MUST NOT 包含 `video/` 下任何条目

#### Scenario: 部分文件缺失仍成功打包
- **WHEN** 设备上不存在 `debug_log.txt.1`（仅有当前 `debug_log.txt`）时触发打包
- **THEN** 打包跳过缺失的 `.1` 文件并成功生成 zip，不抛异常、不判失败

### Requirement: 上传前隐私确认

系统 SHALL 在真正打包并上传诊断数据之前，向用户展示明确告知"将上传行驶轨迹与诊断数据用于排查"的确认对话框；仅当用户确认同意后系统才打包并上传。用户拒绝时系统 MUST NOT 进行任何打包或网络上传。

#### Scenario: 同意后才打包上传
- **WHEN** 用户在诊断面板点上传并在确认对话框点"同意"
- **THEN** 系统开始打包并上传诊断 zip

#### Scenario: 拒绝则不打包不上传（反例）
- **WHEN** 用户在确认对话框点"取消"
- **THEN** 系统 MUST NOT 打包、MUST NOT 发起任何网络请求，面板回到可重新触发的空闲态

### Requirement: 上传元数据随包上送

系统 SHALL 随诊断 zip 一并上送元数据：设备型号（`Build.MANUFACTURER`+`MODEL`）、Android ID（`Settings.Secure.ANDROID_ID`）、`versionName`、`versionCode`、打包毫秒时间戳；并 SHALL 允许用户可选填写工单号/备注一并上送。工单号为空时上传 MUST 仍可成功。

#### Scenario: multipart 含必填元数据
- **WHEN** 系统发起诊断上传
- **THEN** 请求 multipart 表单包含 deviceModel、androidId、versionName、versionCode、capturedAt 字段且均非空

#### Scenario: 工单号为空仍可上传
- **WHEN** 用户未填写工单号即上传
- **THEN** 上传请求照常发出（ticket 字段省略或为空），上传可成功

#### Scenario: 工单号填写则随表单上送
- **WHEN** 用户填写工单号 "BUG-123" 后上传
- **THEN** 请求 multipart 表单包含 `ticket=BUG-123`

### Requirement: 上传状态与结果反馈

系统 SHALL 通过状态机向 UI 暴露上传过程：打包中、上传中（带进度）、成功（携带服务端返回的 `logId`）、失败（携带可区分的原因）。成功时 MUST 向用户展示 `logId`；失败时 MUST 展示失败原因而非成功，且 MUST NOT 展示 `logId`。

#### Scenario: 成功展示 logId
- **WHEN** 服务端返回 200 且响应体含 `logId`
- **THEN** 面板进入成功态并把该 `logId` 展示给用户

#### Scenario: 网络失败展示错误（反例）
- **WHEN** 上传过程网络中断或请求超时
- **THEN** 面板进入失败态、展示网络错误原因，MUST NOT 展示成功或任何 `logId`

#### Scenario: 上传中展示进度
- **WHEN** zip 正在上送
- **THEN** 面板展示上传进度（0..1 或百分比），不阻塞 UI 线程

### Requirement: 服务端诊断日志上传 API 契约

livetiming-server SHALL 提供 `POST /api/v1/logs` 端点，接收 `multipart/form-data`：必填 `file`(application/zip) 与必填 meta 字段（deviceModel、androidId、versionName、versionCode、capturedAt）、可选 `ticket`，鉴权复用 livetiming Bearer token。合法请求 MUST 返回 200 且响应 JSON 含 `logId`。缺必填项 MUST 返回 400，token 无效 MUST 返回 401。

#### Scenario: 合法 multipart 返回 logId
- **WHEN** 客户端以合法 token POST 含 `file`(zip) 与全部必填 meta 的 multipart 请求
- **THEN** 服务端落存储并返回 200，响应 JSON 含非空 `logId`

#### Scenario: 缺 file part 返回 400（反例）
- **WHEN** 请求 multipart 缺少 `file` part
- **THEN** 服务端返回 400，不落存储

#### Scenario: 缺必填 meta 返回 400（反例）
- **WHEN** 请求缺少 `deviceModel` 等任一必填 meta 字段
- **THEN** 服务端返回 400，不落存储

#### Scenario: token 无效返回 401（反例）
- **WHEN** 请求携带无效或缺失的 Bearer token
- **THEN** 服务端返回 401，不落存储
