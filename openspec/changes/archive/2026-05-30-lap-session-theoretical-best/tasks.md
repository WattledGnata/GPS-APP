# Tasks

## 1. computeTheoreticalBest 纯函数 + 数据类
- [x] 1.1 `LapSessionDetailScreen.kt` 加 `TheoreticalBest(totalMs, sectors)` + `SectorBest(sectorIndex, bestMs, lapNumber)` 数据类
- [x] 1.2 `computeTheoreticalBest(crossings): TheoreticalBest?`：accepted SF wallClock 配对每圈窗口 → 窗口内 accepted Sector wallClock 派生每圈各段耗时 → 完整圈（段数==max）每 sector 取 min + 圈号 → 拼接总和；<2 SF / sectorCount<2 / 无完整圈 → null

## 2. 顶部面板
- [x] 2.1 `TheoreticalBestPanel(best, bestActualLapMs)`：CutCornerPanel，总时间（Score 字体）+「比最快圈快 X.XXXs」（Green，仅 gain>0）+ 各 sector 最快段耗时 + Lap 号；全单行 Ellipsis
- [x] 2.2 LazyColumn 顶部 `if (theoreticalBest != null) item { TheoreticalBestPanel(...) }`（OverviewSection 之前）+ `remember(crossings)` 缓存

## 3. 单测
- [x] 3.1 `LapSessionTheoreticalBestTest` 5 case：两圈拼接 / rejected 排除反例 / 单圈退化 / 无 sector→null / <2 SF→null（全绿）

## 4. 编译 + 真机
- [x] 4.1 `:feature:test:testDebugUnitTest` 绿 + `:app:assembleDebug` 编译
- [ ] 4.2 真机 vivo V2405A：进 session 圈列表屏看顶部理论最佳圈面板（总时间 + 各 sector 最快圈 + gain）。**待装机验证**（设备需人工确认安装）

## 10. follow-up backlog
- [ ] 10.1 best-sector 视觉高亮联动（圈列表里把每 sector 最快的圈行标记）——非阻塞，路测后视需要
- [ ] 10.2 理论最佳圈在 M3 多圈比较屏复用（M3 立项时评估）
