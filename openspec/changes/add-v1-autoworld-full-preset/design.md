## Context

用户提供的 2025-07-19 RaceChrono CSV 覆盖天津 V1 4.29 km 完整布局，官方 MYLAPS 成绩单中金政有六个完整可比圈及一个回 P 圈。原 track-only RCZ 同时包含共用起终点和两个疑似 2.4 km 布局 Split；直接用其 S1/S2 切分完整布局，与官方 S1/S2/S3 明显不一致。

将 RCZ 起终点沿 278° 行驶方向前后搜索，并以六圈 `computedLap - officialLap` 的 RMSE 为目标，最优中心位于原中心沿行驶方向反向约 149.6 m。该点六圈残差为 `+16/-11/+55/+10/-45/-34 ms`，均值约 -1.5 ms、标准差约 33 ms、最大绝对值 55 ms；留一圈交叉验证的最优偏移稳定在 -142 m 至 -159 m。

公开名称以场地方官网和 FIA 为准：中文“天津V1国际赛车场”，英文“V1 Autoworld Circuit”。宁波官网正式名称与当前代码“宁波国际赛道 / Ningbo International Circuit”一致。

## Goals / Non-Goals

**Goals:**

- 让 debug/release App 离线选择天津 V1 4.29 km 完整布局。
- 使用多圈最小方差拟合后的起终点，而不是原 RCZ 门点。
- referencePath 和静态矢量图只使用用户提供的有效圈 GPS。
- 自动化测试锁定官网正式名称、稳定 ID、起终点几何、无分段边界、目录顺序和矢量资源。

**Non-Goals:**

- 不把 2.4 km 布局的 S1/S2 放进 4.29 km 预置。
- 不在缺少 2.4 km 实测与官方成绩时新增短布局预置。
- 不接入服务端赛道下发、livetiming seed 或在线发布。
- 不复制第三方赛道图、Logo、水印或版式。

## Decisions

### D1：使用独立稳定 ID `preset-v1-autoworld-full`

ID 对齐 FIA 的 `V1 AUTOWORLD` 命名，并以 `full` 区分未来 2.4 km 布局。名称使用 `天津V1国际赛车场 / V1 Autoworld Circuit / V1`，官方展示长度采用场地方页面的 4.29 km。

### D2：起终点使用六圈 RMSE 最小的纵向拟合位置

保持 RCZ 的 278° 通过方向与 75 m 门宽，仅沿赛道方向移动中心。最终中心为 `39.3829583023, 116.9931881087`，门端点为：

- start：`39.3826247140, 116.9931274521`
- end：`39.3832918906, 116.9932487652`
- passDirection：`x=0.00003618500625, y=-0.00033311199330`

选择 RMSE 而不是只压低方差，避免得到方差小但整体带固定偏差的位置。75 m 宽度保留主赛道与 P 区同一计时断面的覆盖；回 P 圈是否计入最佳圈仍由既有圈记录/筛选语义处理。

### D3：完整布局不声明 sectorGates

`sectorGates = emptyList()`。原 RCZ S1/S2 与起终点仅相距约 126 m/101 m，更符合场地方明确存在的 2.4 km 布局；在没有独立验证前，完整布局 UI 不显示伪官方分段，圈速引擎仅依赖 S/F 完成整圈计时。

### D4：referencePath 取官方最快圈对应的连续 GPS 闭环

选取官方第 4 圈（2:22.661）对应的 GPS 区间，以拟合 S/F 两次穿线点裁切，按约 30 m 弧长等距抽样并显式首尾闭合。路径只服务地图轮廓和距离投影，不用 GPS 累计距离覆盖官方 `lengthKm`。

### D5：静态矢量复用宁波 TrackTech 预览规范

使用 100×120 viewport、120×144 dp、正北朝上等距矩形投影和 8 单位 padding。轮廓使用 `#FF67E8F9`、2 单位、round cap/join；起点使用 Cyan 实心圆加深色描边。`thumbnailDrawableResId` 优先于 PNG 和动态路径降级。

## Risks / Trade-offs

- [拟合只来自同一天同一车辆六圈] → 使用留一圈交叉验证证明纵向位置稳定；测试锁定当前候选，未来新 session 可重新联合拟合。
- [75 m 门同时覆盖 P 区] → 与官方 p 圈识别一致，但 App 不具备官方 transponder 的 p 标记；不得把 P 圈当性能最佳圈证据。
- [缺少完整布局官方分段坐标] → 明确输出空 sector 列表，避免错误信息优于虚构分段。
- [官网有 4.29 km、FIA 历史表有 4.319 km 等不同精度口径] → 面向用户展示采用场地方当前完整布局页面的 4.29 km。

## Migration Plan

新增 main preset、drawable 和测试，无数据库迁移。回滚时删除 `preset-v1-autoworld-full`、对应 drawable 与测试即可；已有赛道及历史 session 不受影响。

## Open Questions

2.4 km 布局需等待独立 GPS、官方圈速/分段或场地方门点后另立变更。
