## Why

承接 `lap-detail-sector-split-times`（step 1）的用户路测反馈第二点：看圈速真正有价值的是**理论最佳圈**（motorsport optimal/theoretical best lap）——session 内每个 sector 取所有圈中最快的那一段，拼接出"如果每段都跑最快"的最优圈速，并看出每个 sector 各自最快是哪圈（定位强弱段）。用户 2026-05-30 拍板放在 `LapSessionDetailScreen`（圈列表屏）顶部（跨圈 session 级指标，不属于单圈详情屏）。

关键：`LapSessionDetailScreen` 已经 `telemetryRepository.getCrossings(sessionId)` 把全部 crossings（StartFinish + Sector）加载进来（`deriveDetailMetrics` 即从它派生圈列表）。理论最佳圈可**纯函数从这份现成 crossings 直接算**——零 binary 读、零 repository 改、零 #16 契约改。真机 FileLogger 已确认数据有 sector（`sectors=5`），值得做。

## What Changes

- `computeTheoreticalBest(crossings): TheoreticalBest?` 纯函数（LapSessionDetailScreen.kt）：复用 deriveDetailMetrics/getLapTelemetry 同款 SF 配对 + sector 窗口逻辑算每圈各 sector 耗时 → 每 sector 取所有"完整圈"最快段 → 拼接总和 + 标最快圈号。无 sector 门或无完整圈 → null。
- `TheoreticalBestPanel` 顶部面板：理论最佳总时间（Score 字体）+「比最快圈快 X.XXXs」（绿）+ 各 sector 最快段耗时 + Lap 号；全单行 Ellipsis。
- 单测 `LapSessionTheoreticalBestTest`（5 case）。

**不改**：reader / repository / `getLapTelemetry` / 公共协议 / Room schema / `deriveDetailMetrics` 既有逻辑。

## Impact

- 受影响：`feature/test/.../ui/tracktech/LapSessionDetailScreen.kt`（+ 纯函数 + 数据类 + 顶部面板 composable + LazyColumn 顶部 item）；新增测试 1 文件。
- 完成 sectors 路测反馈两步走的 step 2，sectors 分析功能闭环。
