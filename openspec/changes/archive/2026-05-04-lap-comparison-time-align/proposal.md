## Why

Phase 1 的 5 个 round 分两类：数据底座（W1 lap-data-readers）+ UI 与算法外围（W2 chart-and-map-components / W3 lap-comparison-time-align / 后续 Tier 2 单圈详情屏 + 多圈比较屏）。其中 **多圈比较屏** 的核心交互——"游标拖到任一空间位置，三圈各自速度/时间对齐显示"——必须有一个 pure function 把多圈在 distance 维度重采样到统一网格上，否则三圈之间因长度不同（圈耗时 60s 与 65s 的 sample 数不一致）无法同位置比较。

本 round 把这个算法**先于 Tier 2 集成屏**单独抽出实现 + 单测覆盖：原因是 (a) 算法本身可作为 pure function 完全独立验证，不依赖 Repository / Android Context / W1 实施；(b) 把算法风险前置释放，让 Tier 2 集成屏在 Phase 1 收尾阶段能直接消费一个已被单测锁死的稳定 API；(c) 与 W1/W2/W4 文件级 0 交叉，可与其他 worktree 真正并行。

## What Changes

- **新增** `core/domain/src/main/java/com/blazepush/core/domain/usecase/LapAlignment.kt`：一个 `object LapAlignment` 内含一个公开方法 `alignByDistance(laps, referenceLapIndex, distanceStepMeters)`，按 distance 等差网格对每圈重采样，返回 **`LapAlignmentResult`** data class（不是裸 `List<List<LapTelemetrySample>>`，便于调用方 reverse-lookup distance ↔ grid index）。
- **新增** `core/domain/src/test/java/com/blazepush/core/domain/usecase/LapAlignmentTest.kt`：6 个 testcase（A 三圈不同 pace 对齐 / B 单圈输入 / C 距离过短 / D 参考圈越界 / E 累计距离含重复值即车静止 / F 比较圈样本退化 fallback），mock LapTelemetry 直接构造，0 Android 依赖。
- **协议兼容性**：本 round 0 协议改动；不修改 RaceChrono BLE / replay / GPS 接收链路任何字段；只在 `core/domain` 内新增纯函数与测试。
- **依赖契约**：消费 W1 round 在 entry sketch §1 已稳定的 `LapTelemetry` / `LapTelemetrySample` 类型。**W3 不依赖 W1 实施完成**——若 W1 尚未合回，本 round MUST 在 `core/domain/.../model/` 内**临时**新建 `LapTelemetry.kt`（与 W1 签名完全一致）。Rebase 时如 W1 已合回，**worktree 内 git rebase 期 conflict 是预期行为**（同 package 同名 data class 二次声明 Kotlin 编译器报错）——后合回方在 rebase 期 `git rm` 占位 LapTelemetry.kt + `git rebase --continue` + 重测全绿。详见 design D6。

## Capabilities

### New Capabilities

- `lap-comparison-alignment`：定义"多圈 telemetry 按 distance 重采样到统一网格 + 调用方 reverse-lookup helper"的算法契约——输入多圈 + 参考圈 index + 距离步长，输出每圈在统一 distance 网格上重采样后的 sample 序列（包装在 `LapAlignmentResult` 中含 `distanceStepMeters` / `refTotalDist` / `gridSize` 元数据 + `gridIndexFor(distance)` 反查 helper）。包含正常路径、参考圈越界、距离步长越界、单圈输入、累计距离含重复值（车静止）等边界行为的 normative 约束。

### Modified Capabilities

（无）

## Impact

**受影响代码 / 模块**：

- `core/domain/src/main/java/com/blazepush/core/domain/usecase/LapAlignment.kt`（新建）
- `core/domain/src/main/java/com/blazepush/core/domain/model/LapTelemetry.kt`（新建占位；W1 合回后由 W1 替换为正式版本）
- `core/domain/src/test/java/com/blazepush/core/domain/usecase/LapAlignmentTest.kt`（新建）
- `openspec/specs/lap-comparison-alignment/spec.md`（归档时由变更目录的 delta spec 同步生成）

**0 改动**：

- `core/data` 任意文件
- `feature/test` 任意文件（Tier 2 集成屏在后续 round 内消费）
- `feature/test/.../viewmodel/TestSessionViewModel.kt`（与 W4 round 0 交叉）
- `core/data/.../repository/TelemetryRepository.kt`（与 W1 round 0 交叉）
- `feature/test/.../ui/components/*.kt`（与 W2 round 0 交叉）
- 任何 Android Manifest / build.gradle / Room schema

**API surface**：

- 公开新增：`object LapAlignment.alignByDistance(laps: List&lt;LapTelemetry&gt;, referenceLapIndex: Int, distanceStepMeters: Double): LapAlignmentResult`
- 公开新增：`data class LapTelemetry` / `data class LapTelemetrySample`（占位；W1 round 合回时归并）

**双端任务划分**：本 round 仅接收端；发射端 simulator 无任何改动。

**性能 / 内存**：

- 算法复杂度：每圈累计距离 O(N) 一次扫描 + 每个 grid 点二分查找 O(log N) → **总复杂度 O(N + M log N)**，N = 单圈 sample 数（~1500 帧 @ 25Hz × 60s），M = 输出网格点数（~600 点 @ 5m 步长 × 3km 圈长）。3 圈输入时累计 O(3N + 3M log N) ≈ 4500 + 3·600·11 ≈ 24300 基础操作 + 1800 次插值，主线程边界内 sub-ms 完成
- 内存：(a) 累计距离数组 3 × N × 8 bytes ≈ 36 KB（per-call），(b) grid 数组 M × 8 bytes ≈ 4.8 KB，(c) 输出 List<List<LapTelemetrySample>> 3 × M × ~80 bytes ≈ 144 KB；总 ~185 KB per-call
- **调用模式约束**：算法**预期"每次圈选择改变重算 1 次，cursor 拖动只查表"**——本 round design D7 决定改 return type 为 data class `LapAlignmentResult`，让调用方 Tier 2 屏拿到结果后用 reverse-lookup helper 在 O(1) 内由 cursor distance 找 grid index，**不需要每帧重调** `alignByDistance`。若调用方误用按帧重调，每秒 ~25 调用 × 185KB = 4.6 MB/s 持续分配，会触发 GC——文档与 KDoc MUST 显式禁止
