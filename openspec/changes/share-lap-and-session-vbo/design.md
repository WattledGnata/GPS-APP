## Context

现有 `RaceLogicVboLapExporter` 是纯 Kotlin 单圈格式器，`LapDetailScreen` 通过 SAF `CreateDocument` 把完整字符串写入用户选择的位置。视频导出已经证明系统 `ACTION_SEND` 可满足分享需求，但 VBO 尚无 `FileProvider`。整节次数据已存在 session 二进制文件中，repository 目前只有按圈窗口读取和一个命名偏向加减速业务的全文件读取器。

本变更跨越 UI、格式器、repository 和 Android URI 暴露边界，必须保证不伪造缺失的 GPS 字段、不泄露任意应用私有文件，并避免长 session 在内存中构造超大字符串。

## Goals / Non-Goals

**Goals:**

- 在单圈详情页通过一个菜单选择分享当前单圈或其所属整节次 VBO。
- 点击选项后直接生成缓存文件并打开系统分享面板，不经过公共存储或 SAF。
- 整节次文件包含全部有效 session 样本，并尽可能包含赛道门线和圈次摘要。
- 分享 URI 只允许接收方临时只读访问指定缓存文件。
- 对长 session 流式写出，保持内存占用有界。

**Non-Goals:**

- 不接入微信 SDK，不指定具体接收应用、好友或会话。
- 不改变 BLE 协议、圈速判定、持久化格式、置信度策略或视频导出。
- 不新增永久公共存储副本、后台上传、ZIP 打包或多文件分享。

## Decisions

### 1. 入口保留在单圈详情页并改为双选项菜单

复用现有用户已知的 VBO 入口，将标题栏操作改成菜单：“导出单圈 VBO”和“导出整节 VBO”。两项点选后都直接进入系统分享面板；整节选项使用当前 `sessionId`，不依赖当前圈是否为最佳圈。相比另增 session 页面按钮，此方案严格符合本次交互定义且减少重复入口。

### 2. 生成到专用缓存目录后使用系统分享

文件写入 `cacheDir/shared_vbo/`，通过 authority `${applicationId}.fileprovider` 的 `FileProvider` 生成 `content://` URI。分享 Intent 使用 `ACTION_SEND`、`EXTRA_STREAM`、`ClipData` 和 `FLAG_GRANT_READ_URI_PERMISSION`，MIME 使用 `application/octet-stream`，由系统 chooser 路由到微信等已声明普通文件接收能力的应用。

不使用 MediaStore，因为用户没有要求永久保存；不使用 `file://`，因为 Android 7+ 禁止跨应用暴露；不接微信 SDK，因为目标是通用文件分享而不是微信专属卡片能力。

### 3. 单圈沿用既有格式真实性，整节次新增流式格式器

单圈继续调用 `RaceLogicVboLapExporter`，但把生成文本写到缓存而非 SAF。整节次使用独立 `RaceLogicVboSessionExporter`，接收 session 元数据、全部样本、crossing、可选 `Track` 和各圈 evidence，通过 `Writer` 顺序输出：

- 标准 header/column/data；只声明并输出真实存在且全体有效的公共字段。
- `[laptiming]` 使用 Track 的起终点线及有序 sector 门；VBO 门线坐标按 longitude/latitude、North/West 正号规则输出。
- comments/session data 记录 session、赛道、完整圈数、缺失门线或无完整圈等降级事实。
- 数据覆盖 session 全部有效样本，包括开圈前、慢圈和收车后的证据；无效样本被省略并计数。

格式器不复制 RaceChrono 私有 BlazePush metadata chunk，也不伪造卫星数、精度或加速度。

### 4. repository 提供语义明确的整节次读取 API

新增 `getSessionTelemetryExport(sessionId)`（或等价命名）的 suspend API，在 IO dispatcher 中读取 session、完整 binary samples、crossing 与 evidence，并返回面向导出的领域快照。UI 不直接打开 `binaryFilePath`。全文件解码复用底层读取实现，但不把 `PerformanceTestTelemetryReader` 的业务命名泄漏到 UI。

### 5. 分享准备是可测试的状态机

菜单点击后禁用重复操作并显示生成反馈。准备结果区分：session/telemetry 缺失、无有效样本、缓存写入失败、URI 创建失败和 Ready。Ready 才打开 chooser；chooser 无接收者或启动异常时显示失败但保留缓存文件供下次重试。

缓存文件不在启动 chooser 后立即删除，避免接收方异步读取失败；每次生成前清理专用目录中过期文件，并保留当前文件。

## Risks / Trade-offs

- [部分微信版本可能不声明通用 VBO MIME] → 使用 `application/octet-stream` 和 `.vbo` 文件名；真机验收必须确认微信文件会话可见，若仍不可见再评估 ZIP 降级，而不是预先接 SDK。
- [超长 session 文件较大] → 格式器直接写 `BufferedWriter`，repository 读取仍受既有全文件解码模型约束；后续若出现小时级 session 再引入 binary 流式迭代器。
- [历史 session 没有 trackId 或 crossing wall clock] → 原始样本仍可分享，comments 明确 laptiming/lap summary 降级，禁止猜测门线。
- [共享缓存积累] → 只开放 `shared_vbo/`，生成前按年龄清理；不删除刚分享的文件。
- [当前工作区与已发布 VBO 基线分离] → 实现从包含提交 `068aac7` 的 `codex/gps-lap-defense-control` 基线派生，避免重复或回退单圈能力。

## Migration Plan

无需数据库迁移。发布后旧 SAF 单圈导出入口被菜单替换；回滚只需移除菜单、session 格式器和 FileProvider 声明，既有二进制数据不受影响。

## Open Questions

- 微信真机若拒收 `.vbo`，是否追加“以 ZIP 分享”的兼容选项；本轮先以真实系统分享验证决定。
