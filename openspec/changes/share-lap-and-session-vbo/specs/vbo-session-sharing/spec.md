## ADDED Requirements

### Requirement: 单圈详情提供 VBO 分享菜单
系统 SHALL 将单圈详情页原单一 VBO 导出操作呈现为菜单，并提供“导出单圈 VBO”和“导出整节 VBO”两个互斥操作。

#### Scenario: 打开 VBO 菜单
- **WHEN** 用户点击单圈详情页的 VBO 操作入口
- **THEN** 系统显示“导出单圈 VBO”和“导出整节 VBO”两个选项，且不立即打开存储选择器或分享面板

#### Scenario: 选择单圈分享
- **WHEN** 用户选择“导出单圈 VBO”
- **THEN** 系统只导出当前 `lapIndex` 对应的完整单圈证据

#### Scenario: 选择整节分享
- **WHEN** 用户选择“导出整节 VBO”
- **THEN** 系统导出当前 `sessionId` 对应的整节原始遥测，而不只拼接当前圈

### Requirement: VBO 使用临时文件调用系统分享
系统 MUST 把选定范围的 VBO 写入应用专用缓存，并使用带临时只读授权的 `content://` URI 调起 Android 系统分享面板；系统 MUST NOT 要求用户先选择公共存储位置。

#### Scenario: 单圈文件准备成功
- **WHEN** 单圈 VBO 缓存文件和共享 URI 均准备成功
- **THEN** 系统以 `.vbo` 文件附件直接打开系统 chooser

#### Scenario: 整节文件准备成功
- **WHEN** 整节 VBO 缓存文件和共享 URI 均准备成功
- **THEN** 系统以单个 `.vbo` 文件附件直接打开系统 chooser

#### Scenario: 分享接收方异步读取
- **WHEN** 系统分享面板已经打开
- **THEN** 当前缓存文件在接收方可能读取期间保持存在且仅被授予只读权限

#### Scenario: 分享准备失败
- **WHEN** 遥测读取、VBO 写入、URI 创建或 chooser 启动失败
- **THEN** 系统显示明确失败反馈并恢复菜单可操作状态，且不报告分享成功

### Requirement: 单圈 VBO 保持既有证据真实性
单圈分享 MUST 继续使用当前 `RaceLogicVboLapExporter` 的圈边界、置信度、provenance、有效字段和省略计数规则，不得因分享链路迁移而伪造或扩充不可用字段。

#### Scenario: 单圈存在无效样本
- **WHEN** 当前圈同时包含可导出样本和无效样本
- **THEN** 文件只包含可导出样本，并在注释和反馈中保留省略数量

#### Scenario: 单圈没有可导出证据
- **WHEN** 当前圈没有有效位置或必需字段
- **THEN** 系统拒绝生成分享文件并显示对应原因

### Requirement: 整节 VBO 覆盖完整 session 证据
整节分享 SHALL 按时间顺序输出 session 二进制文件中的全部有效原始遥测样本，包括开圈前、完整圈、慢圈和收车阶段；系统 MUST NOT 仅循环拼接 `getLapTelemetry` 的完整圈窗口。

#### Scenario: session 包含圈外样本
- **WHEN** session 在首个起终点 crossing 前或最后 crossing 后存在有效样本
- **THEN** 整节 VBO 的 data 区仍包含这些样本

#### Scenario: session 没有完整圈
- **WHEN** session 存在有效原始样本但没有两次可配对的起终点 crossing
- **THEN** 系统仍允许分享整节 VBO，并在注释中标明没有完整圈

#### Scenario: session 没有有效样本
- **WHEN** session binary 缺失、为空或没有任何有效必需字段
- **THEN** 系统拒绝整节分享并显示无可分享遥测

### Requirement: 整节 VBO 携带可验证的计时上下文
当持久化事实可用时，整节 VBO SHALL 包含 session/赛道标识、Track 起终点和有序 sector 门、完整圈摘要及数据降级说明；缺失事实 MUST 被明确省略而不是猜测。

#### Scenario: Track 和 crossing 完整
- **WHEN** session 的 `trackId` 可解析且 crossing 可形成完整圈
- **THEN** 文件包含 `[laptiming]` 门线和可核对的完整圈数量/摘要

#### Scenario: 历史 session 无法解析 Track
- **WHEN** session 缺少 `trackId` 或 catalog 无法解析对应 Track
- **THEN** 文件仍包含原始 data，但省略 `[laptiming]` 并在 comments 中说明原因

#### Scenario: 可选传感器字段缺失
- **WHEN** session 未持久化卫星数、精度、加速度或部分 heading
- **THEN** 格式器省略相应列而不得填充零值或推测值

### Requirement: 整节 VBO 写出保持有界内存
整节 VBO 格式器 MUST 通过 `Writer` 顺序写出文档，不得把完整 VBO 先拼接成单个内存字符串。

#### Scenario: 导出长 session
- **WHEN** session 包含大量遥测样本
- **THEN** 格式器逐行写入目标缓存文件，并返回样本/省略计数而非完整文档字符串

### Requirement: 分享缓存访问范围受限
应用 MUST 只通过 `FileProvider` 暴露专用 VBO 缓存子目录，并在后续生成时清理该目录中过期文件；应用 MUST NOT 暴露整个内部 files/cache 根目录。

#### Scenario: 构造共享 URI
- **WHEN** VBO 文件位于批准的专用缓存子目录
- **THEN** `FileProvider` 能生成只读 `content://` URI

#### Scenario: 请求暴露其他私有文件
- **WHEN** 文件不位于批准的 VBO 缓存子目录
- **THEN** 分享组件拒绝为其生成 VBO 共享 URI
