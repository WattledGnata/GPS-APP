# unify-gps-telemetry-persistence ff artifacts review

## 0. TL;DR

不建议进入 `/opsx:apply`。`openspec validate --strict` 通过，整体方向（Room metadata + binary telemetry）正确，但四件套还有 3 个会阻塞实施的契约问题：旧 `TestDataFileStorage` 消费点未完整迁移、chunk header 更新与 append-only/no-seek 设计矛盾、`flush()`/`close()` 缺少确定性 ack 机制。另有 CrossingEvent 持久化字段被过度裁剪的 P2 风险。

## 1. Findings

### Finding 1 — [P1] 删除 `TestDataFileStorage` 前未迁完现有消费者

- **位置**：
  - `openspec/changes/unify-gps-telemetry-persistence/tasks.md:7.1-7.3`
  - `core/data/src/main/java/com/blazepush/core/data/repository/TestResultRepository.kt`
  - `feature/test/src/main/java/com/blazepush/feature/test/ui/screen/TestResultScreen.kt`
  - `feature/test/src/main/java/com/blazepush/feature/test/di/AppModule.kt`
- **问题**：tasks 只写把 `TestExecutionViewModel`（当前仓库未命中该类名）替换为 `TelemetryRepository`，然后 §7.3 删除 `TestDataFileStorage.kt`。但当前生产代码仍有 3 个直接消费者：
  - `TestResultRepository` 构造注入 `TestDataFileStorage`，`saveResult()` 调 `saveDataPoints()`，`deleteResult()` 调 `deleteDataFile()`。
  - `TestResultScreen` 通过 `koinInject<TestDataFileStorage>()` 调 `loadDataPoints()` 渲染速度 / G 值曲线。
  - `AppModule.kt` 仍注册 `single { TestDataFileStorage(androidContext()) }`，并把它注入 `TestResultRepository(get(), get(), get())`。
- **影响**：实施方如果按 §7.3 删除旧文件，工程会直接编译失败；即使不删除，旧 JSON 路径仍然在 Records / 结果页中继续使用，违反 proposal “废弃 JSON 方案”。
- **要求**：把 §7 扩成完整迁移面：
  - `TestResultRepository` 改为通过 `TelemetryRepository` 保存 binary 文件路径 / session 元数据，删除 `TestDataFileStorage` 构造参数。
  - `TestResultScreen` 改用新 reader（例如 `PerformanceTestTelemetryReader`）加载 `List<TelemetrySample>`，并明确到 `GpsDataPoint` / chart input 的适配层。
  - `AppModule` 删除 `TestDataFileStorage` 注册并迁移 `TestResultRepository` 注入参数。
  - 增加 grep 门槛：生产源码中 `TestDataFileStorage` / `loadDataPoints` / `saveDataPoints` / Gson JSON telemetry 路径零命中。

### Finding 2 — [P1] header 更新契约与 append-only/no-seek 设计冲突

- **位置**：
  - `openspec/changes/unify-gps-telemetry-persistence/design.md` D1 / D2 / Risks
  - `openspec/changes/unify-gps-telemetry-persistence/tasks.md:3.2-3.4`
- **问题**：D1 要求文件头含 `sampleCount` 和 `endTs`，tasks §3.3 要求 `flush()` 更新 header 中的 `sampleCount` 与 `endTs`；但 D2 同时写“逐条追加写入（不 seek）”。这两个要求不能同时成立：要在文件头原地更新 `sampleCount/endTs`，必须使用 `RandomAccessFile` / `FileChannel.position(0)` / 另写 sidecar metadata / footer index 之一。纯 `FileOutputStream` append-only 无法回写 header。
- **影响**：实施方会在 writer 实现时被迫自行拍板，测试也无法判断“正确实现”是 seek 回写、footer、还是 Room 元数据作为真相源。
- **要求**：在 design/spec/tasks 中明确选择一个方案：
  - 方案 A：使用 `RandomAccessFile` / `FileChannel`，允许 header seek 回写，并删除 D2 “不 seek”表述。
  - 方案 B：header 只写 immutable start metadata，`sampleCount/endTs` 存 Room 或 footer，读取时以文件大小 / footer 为准。
  - 同步补充崩溃恢复行为：header count 与实际文件大小不一致时以谁为准、是否截断半条 sample。

### Finding 3 — [P1] `flush()` / `close()` 缺少确定性 ack 机制

- **位置**：
  - `openspec/changes/unify-gps-telemetry-persistence/specs/binary-telemetry-storage/spec.md` BinaryTelemetryWriter / Flush 策略
  - `openspec/changes/unify-gps-telemetry-persistence/design.md` D2 / D3 / Risks
  - `openspec/changes/unify-gps-telemetry-persistence/tasks.md:3.1-3.6`
- **问题**：spec 要求 `write(sample)` 非阻塞、`flush()` 完成后所有已写入 samples 可读、`close()` 后所有已 write samples 均持久化。但 design 只说 `Channel<TelemetrySample>` + IO consumer，没有定义 `Flush` / `Close` 控制消息和 ack。若 `write()` 用 `trySend` 或非阻塞入队，调用方随后 `flush()` 时必须保证 consumer 已处理 flush 调用之前的所有 sample；仅检查本地 buffer 或直接 flush FileOutputStream 都不等价于“channel 中所有先前 sample 已落盘”。
- **影响**：测试会摇摆，session 结束可能丢尾部样本；这和 A18 FileLogger 之前踩过的坑同类。
- **要求**：把 writer channel 升级为命令通道，例如 `Channel<TelemetryCommand>`：
  - `Append(sample)`：非阻塞或有明确 backpressure 策略。
  - `Flush(ack: CompletableDeferred<Unit>)`：consumer 收到后先 drain 当前 batch / 写文件 / 更新元数据，再 `ack.complete(Unit)`。
  - `Close(ack)`：FIFO 排在已入队 samples 后，完成最终 flush、header/metadata 更新、关闭文件后 ack。
  - 明确 channel capacity / overflow 策略；若选择无界 channel，就不要再把“1000 帧兜底”描述成内存上界，需定义 consumer 内部 batch 上限。

### Finding 4 — [P2] CrossingEvent 持久化字段裁剪过度，丢失现有语义

- **位置**：
  - `openspec/changes/unify-gps-telemetry-persistence/specs/binary-telemetry-storage/spec.md` CrossingEvent 事务写入
  - `feature/test/src/main/java/com/blazepush/feature/test/model/laptiming/CrossingEvent.kt`
- **问题**：新 spec 的 `CrossingEventEntity` 只包含 `sessionId`、`lapIndex`、`crossingTimestampMs`、`speed_kmh`。但现有运行期 `CrossingEvent` 还包含 `gateId`、`gateType`、`sampleIndex`、`accepted`、`reason`、`directionalSpeedMps`、`directionScore`。这些字段是多门、拒绝事件、方向判定和诊断语义的重要信息；持久化时只保留 timestamp/speed 会让 Records / 回放 / debug 页面无法复原过线原因。
- **要求**：至少在 spec 中拍板：
  - 若 `CrossingEventEntity` 是完整事件表，应包含现有 `CrossingEvent` 的关键字段，尤其 `gateId/gateType/sampleIndex/accepted/reason`。
  - 若只存 accepted lap summary，则不要命名为 `CrossingEventEntity`，改成 `LapTimingEventEntity` / `LapSummaryEntity`，并明确 rejected/unexpected events 不进入持久化。

## 2. Verified

- `openspec validate unify-gps-telemetry-persistence --strict` PASS。
- 当前仓库存在 Room 基础设施：`core/data/src/main/java/com/blazepush/core/data/local/AppDatabase.kt` version 2，`feature/test` / `core/data` 已引入 Room。
- 当前仓库仍有 `TestDataFileStorage` 生产消费者：`TestResultRepository`、`TestResultScreen`、`AppModule`。
- 当前已有运行期 `feature/test/.../model/laptiming/CrossingEvent.kt`，字段比新 spec 多。

## 3. Verdict

暂不放行 `/opsx:apply`。请先修订 Finding 1-3；Finding 4 至少需在 spec 中明确选择“完整事件表”还是“summary 表”。修完后重跑 `openspec validate --strict` 并重提 mini review。

## 4. Round 2 mini review

### 4.1 Finding closure

- Finding 1 closed：tasks §7.3-7.6 已补 `TestResultRepository` / `TestResultScreen` / `AppModule` 迁移和 `TestDataFileStorage` / `saveDataPoints` / `loadDataPoints` 零残留 gate。
- Finding 3 closed in direction：spec/design/tasks 已升级为 `Channel<TelemetryCommand>`，`Append` / `Flush(ack)` / `Close(ack)`，`write(sample)` 为 suspend，capacity 1024 + `SUSPEND` 背压，不静默丢点。
- Finding 4 closed：spec/tasks 已把 `CrossingEventEntity` 定义为完整事件表，补 `gateId` / `gateType` / `accepted` / `reason` / `directionScore`，并声明 rejected crossing 也持久化。
- `openspec validate unify-gps-telemetry-persistence --strict` PASS。

### 4.2 New finding

#### Finding 5 — [P1] 多次 flush 追加 footer 会污染 sample 流

- **位置**：
  - `openspec/changes/unify-gps-telemetry-persistence/design.md:42`
  - `openspec/changes/unify-gps-telemetry-persistence/design.md:60-64`
  - `openspec/changes/unify-gps-telemetry-persistence/tasks.md:11`
  - `openspec/changes/unify-gps-telemetry-persistence/tasks.md:18`
  - `openspec/changes/unify-gps-telemetry-persistence/specs/binary-telemetry-storage/spec.md:13`
- **问题**：v2 选择了 footer 方案，但写成 “flush/close 时追加 footer”。这会让文件结构在第一次定时 flush 后变成：

  ```text
  header + samples[0..749] + footer(count=750,endTs=...)
  ```

  后续继续写样本时又变成：

  ```text
  header + samples[0..749] + footer1 + samples[750..] + footer2
  ```

  这样 footer1 被夹在 sample 流中间。除非 footer 有魔数/长度/类型标记且 reader 会跳过中间 footer，否则 `17 bytes/sample` 的顺序读取会把 footer1 当成 sample 数据，后续所有样本对齐都会错位。当前 spec 只说固定 17 bytes/sample + 末尾 footer，没有定义中间 footer 帧，因此多次 flush 后文件不可可靠解析。
- **额外矛盾**：design 说 header 是 `version(1)+type(1)+startTs(8)` 共 10 bytes，但 footer 恢复规则写 “文件大小不是 `22 + N×17`” / `floor((fileSize - 22) / 17)`，这里仍残留旧 22-byte header 口径，和新 header 10 bytes 不一致。
- **要求**：重新拍板一种不会污染样本流的文件结构：
  - 方案 A：只在 `close()` 写 final footer；`flush()` 只 flush samples，不追加 footer。崩溃恢复时通过 `(fileSize - headerSize) / 17` 推断已完整写入样本数，Room session metadata 可记录 last flushed count/endTs。
  - 方案 B：每次 flush 写 footer sidecar 文件（例如 `.meta`）或 Room metadata，不写入 telemetry binary 主文件。
  - 方案 C：引入 framed format（sample frame 与 footer frame 都有 magic/type/length），但这会放弃严格 `17 bytes/sample` 纯样本流，需重写 D1/spec/tests。
  - 同步修正 header size：新 header 若是 10 bytes，所有恢复公式、测试与 reader 都必须使用 10，而不是 22。

### 4.3 Non-blocking notes

- `design.md` Migration Plan 仍写第一步“删除 `TestDataFileStorage.kt`”且未点名 `TestResultRepository` / `TestResultScreen` / `AppModule` 迁移；tasks 已补完整。建议顺手同步 design，但按 P3 文档一致性处理，不单独阻塞。
- `tasks.md` §1.3 新增 `CrossingEvent` 领域模型仍只列 `sessionId, lapIndex, crossingTimestampMs, speed_kmh`，和 spec 的完整事件表不一致。由于 §5.2 entity 已补字段，建议同步 domain model；若 domain `CrossingEvent` 只是另一个 summary model，需要改名避免混淆。

### 4.4 Verdict

Round 2 仍不放行 `/opsx:apply`。请先修订 Finding 5；否则第一轮定时 flush 后 binary 文件会自我污染，reader / footer 恢复契约不可实现。

## 5. Round 3 mini review

### 5.1 Finding closure

- Finding 5 closed：design/spec/tasks 已放弃 footer 方案，改为 seek 回写固定 22-byte header。
- 文件结构已统一为 `22-byte header + N × 17 bytes sample`，无 footer。
- `flush()` / `close()` 契约已明确：pending samples 写完后保存文件尾 position，seek 到 offset 0 回写 `sampleCount/endTs`，force 刷新后 seek 回文件尾；close 额外关闭 channel/file。
- 崩溃恢复公式已统一为 `actualCount = floor((fileSize - 22) / 17)` 与 `validCount = min(header.sampleCount, actualCount)`；半条 sample 忽略，header count 大于实际 count 时按实际截断。
- 新增/修订测试任务覆盖：flush 后 header count、二次 flush 样本顺序无污染、半条 sample 截断、header count > actual 截断。
- `openspec validate unify-gps-telemetry-persistence --strict` PASS。

### 5.2 Non-blocking note

- `Channel` API 实现时请使用 Kotlin 实际参数形式（通常是 `BufferOverflow.SUSPEND`），避免把文档里的 `onBufferOverflow = SUSPEND` 原样照抄成 unresolved reference。此为实现细节提醒，不阻塞 apply。

### 5.3 Verdict

通过。允许进入 `/opsx:apply`。代码落地后请提交 commit diff 给评审方按 A56 持久化架构核销；重点复查 writer actor 的 ack / close drain、header seek 回写、旧 JSON 消费点零残留、CrossingEvent 完整字段。
