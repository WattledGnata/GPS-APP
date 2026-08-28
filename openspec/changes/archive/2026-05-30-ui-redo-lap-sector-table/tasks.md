# Tasks

## 1. 数据
- [x] 1.1 删 TheoreticalBest/SectorBest/computeTheoreticalBest/TheoreticalBestPanel
- [x] 1.2 加 computeLapSectorTable + LapSectorTable/LapSectorRow（每圈 splits 对齐 sectorCount 缺段 null + bestSplitPerSector/bestLapPerSector + theoreticalTotalMs；同源配对 + rejected/null 排除 + 仅完整圈）

## 2. 表 UI
- [x] 2.1 LapSectorTableBlock：表头 + OPT 理论行(各 sector 最快段 Green + 总时间 + gain) + 各圈行(命中最快段 Green / 缺段 — / BEST Purple) + clickable 导航 + FileLogger
- [x] 2.2 窄屏：共享 hScroll + 固定列宽(LAP48/TIME76/Sector60dp) + 每 Text 单行 Ellipsis
- [x] 2.3 INVALID/INCOMPLETE 圈表下方原 LapRecordRow；无 sector 门 fallback 原列表
- [x] 2.4 formatSectorSplit(紧凑 23.456，>60s 退化 m:ss.mmm)

## 3. 测试
- [x] 3.1 删 LapSessionTheoreticalBestTest，加 LapSectorTableTest 6 case 全绿；feature:test 全量 343/0fail

## 4. 真机
- [x] 4.1 编译 + build APK
- [x] 4.2 vivo V2405A 真机：圈列表表 + 最快段高亮 + 横滚（用户路测签收 OK，lastUpdateTime 11:27）

## 10. follow-up backlog
- [ ] 10.1 窄屏横滚可发现性：加 edge fade/渐变遮罩提示可右滑（真机反馈后视需要）
- [ ] 10.2 列宽极端值（sector 跨分钟）真机微调（64-66dp）
- [ ] 10.3 best-sector 在 M3 多圈比较屏复用表/高亮逻辑（M3 立项评估）
