# fix-file-logger-and-engine-coord-hygiene · code review

- **日期**：2026-04-24
- **评审对象**：`da3f537`
- **覆盖攻击点**：A18 / A39
- **评审方**：codex session
- **实施方**：session 2
- **轮次**：代码落地后第 1 轮核销 review
- **结论**：🔴 暂不核销；Round 2 暂缓启动

## 0. 结论摘要

主体方向认可：FileLogger 已从同步 I/O 改为异步 channel + IO 批量 flush，LogLevel、rotation、高频 call site `v()` 与坐标降精度都已落地。`da3f537` 的改动范围集中在 4 个文件，未混入其它模块。

但本轮发现 1 条 P1 + 2 条 P2。P1 是 `SimpleDateFormat` 在多线程调用方热路径中共享，可能让 `d/v/e` 在入队前就产生数据竞争；这与 A18 “业务线程非阻塞且日志异常不影响业务”目标冲突。两个 P2 分别是 full-buffer 控制命令会挤掉日志行、以及 DROP_OLDEST 测试断言没有真正检查 `drop-0` 不存在。

## 1. 🔴 P0 / P1

### Finding 1 · `SimpleDateFormat` 在多线程热路径共享

- **位置**：`feature/test/src/main/java/com/blazepush/feature/test/FileLogger.kt:83,127-128`
- **问题**：
  - `dateFormat` 是单例对象字段，`formatLine()` 在 `FileLogger.d/v/e` 调用方线程执行。
  - `SimpleDateFormat` 不是线程安全类型；多个业务线程同时打日志时，会共享其内部 `Calendar`/format 状态。
- **影响**：
  - 轻则 timestamp 串扰 / 格式错乱，重则日志调用在入队前抛异常。
  - 这会把风险重新带回业务线程，违背 A18 “FileLogger 业务侧调用安全、非阻塞、不把异常传给业务”的核心目标。
- **建议修复**：
  - 使用 `ThreadLocal<SimpleDateFormat>`，例如 `object : ThreadLocal<SimpleDateFormat>() { override fun initialValue() = SimpleDateFormat(...)}`
  - 或在 `formatLine` 内创建局部 formatter；若担心分配，可优先 ThreadLocal。
  - 不建议简单 `synchronized(dateFormat)`，因为会在多线程高频日志下引入全局锁等待。
  - 加一条并发 smoke test：多协程 / 多线程并发调用 `FileLogger.d` 若干次，确认调用方不抛且 `flushForTest` 后关键尾部日志存在。

## 2. 🟡 P2

### Finding 2 · full-buffer 下 Flush/Shutdown 会挤掉日志行

- **位置**：`feature/test/src/main/java/com/blazepush/feature/test/FileLogger.kt:67-70,93-97,224-228`
- **问题**：
  - `logChannel` 是 `capacity=1024 + DROP_OLDEST`。
  - `flushForTest()` / `init()` 用 suspending `send(Flush/Shutdown)` 可以保证控制命令本身入队，但在满 buffer 上仍会按 `DROP_OLDEST` 插入新命令并丢掉最老元素。
  - 因此 “flushForTest 调用前所有 trySend 成功的 Line 已落盘” 和 “Shutdown FIFO 排空确保零丢失” 在 channel 满时不成立。
- **影响**：
  - 生产上 overload 丢 Line 本身可以接受，但当前注释/spec 把 full-buffer 情况也描述成强保证，会误导核销。
  - graceful handoff 若刚好在 1024 条积压时发生，会因为插入 `Shutdown` 多丢 1 条 Line。
- **建议修复**：
  - 二选一拍板：
    - 降级契约：明确 `DROP_OLDEST` 压力下 Line 允许丢弃，`Flush/Shutdown` 只保证控制命令不丢，不保证已成功 trySend 的所有 Line 都落盘。
    - 或实现强保证：为控制命令使用独立 channel / mutex handoff / 不会挤占 Line 的 drain 机制。
  - 加一条测试覆盖 full-buffer 后 `flushForTest` / `init` 的预期语义，避免继续靠文字解释。

### Finding 3 · DROP_OLDEST 测试没有真正断言 `drop-0` 缺失

- **位置**：`feature/test/src/test/java/com/blazepush/feature/test/FileLoggerTest.kt:145`
- **问题**：
  - 断言写的是 `content.contains("drop-0 ")`，但实际日志行是 `... drop-0\n`，`drop-0` 后不是空格。
  - 即使 `drop-0` 被错误写入文件，这条断言也会通过。
- **影响**：
  - 测试只锁住了 `drop-1999` 存在，未锁住“最老消息被 DROP_OLDEST 丢弃”这一半契约。
- **建议修复**：
  - 改成按行精确匹配，例如：
    ```kotlin
    val lines = content.lineSequence().toList()
    assertFalse(lines.any { it.endsWith("drop-0") })
    assertTrue(lines.any { it.endsWith("drop-1999") })
    ```
  - 或用边界正则，避免 `drop-0` 误匹配 `drop-01` / `drop-099`。

## 3. 🟡 P3

暂无。

## 4. 已确认通过的部分

- `FileLogger.d/v/e` 已从直接 `FileWriter` 改为 `trySend(Line)`。
- `LogLevel` 默认 DEBUG，VERBOSE 在默认级别下不入队。
- 三个高频 call site 已改为 `v()`，并加 `isVerboseEnabled` 早退。
- 高频坐标日志已降至 `%.3f`。
- rotation 使用 `MAX_FILE_BYTES = 5 * 1024 * 1024`，写后 rotate 的策略与 v5 文档一致。
- `FileLoggerTest.kt` 使用 JUnit4 `TemporaryFolder` 与 Mockito `doReturn(...).when(...)`，未引入 JUnit5 / mockito-kotlin 依赖。

## 5. 本轮执行的复核命令

```bash
git show --stat --oneline --decorate da3f537
git show --name-only --format=fuller da3f537
rg -n "SimpleDateFormat|dateFormat|formatLine|ThreadLocal|synchronized|thread|concurrent|并发|多线程" \
  feature/test/src/main/java/com/blazepush/feature/test/FileLogger.kt \
  feature/test/src/test/java/com/blazepush/feature/test/FileLoggerTest.kt \
  openspec/changes/fix-file-logger-and-engine-coord-hygiene
rg -n "FileLogger\\.(d|v|e)\\(" feature/test/src/main core/bluetooth/src/main core/domain/src/main app/src/main
```

## 6. 给实施方的回复模板

Round 1 战役 F 代码暂不核销，Round 2 暂缓启动。请先修 1 条 P1 + 2 条 P2：`SimpleDateFormat` 改成线程安全方案；明确或修复 full-buffer 下 `Flush/Shutdown` 会挤掉 Line 的契约；修正 DROP_OLDEST 测试对 `drop-0` 的断言。修完后重提 mini review，若无新增问题即可迁 A18/A39 到 ✅ 并启动 Round 2。
