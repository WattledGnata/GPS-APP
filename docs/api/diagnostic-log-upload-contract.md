# Diagnostic Log Upload API

供 livetiming-server（Go）实现 `POST /api/v1/logs` 端点的 API 契约文档。

## 端点

```
POST /api/v1/logs
Content-Type: multipart/form-data
Authorization: Bearer <token>
```

鉴权复用 livetiming 现有 Bearer token（与 `POST /api/v1/laps` 共用，不新增 key）。

## 请求 multipart 字段

| 字段名 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `file` | application/zip (binary) | ✅ | 诊断打包 zip（含 telemetry bin + Room 数据库 + 诊断日志） |
| `deviceModel` | text | ✅ | 设备型号，格式 `Manufacturer Model`，例 `vivo V2405A` |
| `androidId` | text | ✅ | `Settings.Secure.ANDROID_ID`，不同应用签名不同值 |
| `versionName` | text | ✅ | App 版本名，例 `1.0.1` |
| `versionCode` | text | ✅ | App 版本号（数字字符串），例 `2` |
| `capturedAt` | text | ✅ | 打包时刻毫秒时间戳（`System.currentTimeMillis()`）|
| `ticket` | text | ❌ 可选 | 用户填写的工单号或备注，为空或省略时表示未填写 |

## 成功响应

```
HTTP/1.1 200 OK
Content-Type: application/json

{
  "logId": "<服务端生成的唯一标识>"
}
```

`logId` 格式由服务端自行决定（建议：`"<timestamp>-<uuid-short>"` 或 `"<8-hex>"` 等人类可读短格式，方便用户口述给客服/开发）。

服务端 SHOULD 把 zip 与 meta 持久化存储以便开发后续拉取排查。存储后端（对象存储 / 本地磁盘 / 数据库）由服务端自行决定，不在本契约约束范围。

## 错误响应

| 状态码 | 条件 | 说明 |
|---|---|---|
| 400 | 缺少 `file` 或任一必填 meta 字段 | 请求格式不完整 |
| 401 | token 无效或缺失 | 鉴权失败，同 livetiming 现有 401 语义 |
| 413 | zip 超过服务端大小上限（若设置） | 本 round 客户端不设上限；服务端可按需设置，返回此码客户端 UI 可识别 |

## zip 内部结构（供服务端开发参考）

```
diag_<timestampMs>.zip
├── telemetry/
│   ├── <uuid>.bin
│   └── ...
├── databases/
│   ├── race_chrono_database       # SQLite 主文件
│   ├── race_chrono_database-wal   # WAL（可能缺失）
│   └── race_chrono_database-shm   # SHM（可能缺失）
├── debug_log.txt                  # FileLogger 当前日志（~5MB）
└── debug_log.txt.1                # rotation 日志（可能缺失）
```

服务端/开发端读取 SQLite 时，应将 `race_chrono_database` + `-wal` + `-shm` 三件套放同一目录，用 sqlite3 打开即可自动合并 WAL。

## 客户端实现参考

- 客户端代码：gps-app repo, `core/network/.../DiagnosticLogUploader.kt`
- 使用 OkHttp `MultipartBody.FORM`，字段如上表
- 进度通过自定义 `ProgressRequestBody`（okio `ForwardingSink`）上报
- 上传超时：connect 10s / write 120s / read 30s
