# lap-alignment-trajectory-divergence-warning — 延期立项设计 memo

**状态**：placeholder（2026-05-05 由 W3 lap-comparison-time-align L2 hostile review P1-4 触发补建）
**触发来源**：W3 design.md [R4] risk mitigation —— "Tier 2 多圈比较屏 round 引入轨迹偏差报警"
**预计立项时机**：Phase 1 Tier 2 `lap-detail-screen-with-cursor` round 期间评估是否合并 scope；如不合并则 Phase 1 后期或 Phase 2 单独立项

> ⚠️ **本 memo 当前为 placeholder**，非完整 9 章设计。立项时（`/opsx:ff lap-alignment-trajectory-divergence-warning`）依本 memo 起草 proposal/design 时需补全 §3 方案对比、§4 数学分析、§6 测试覆盖等细节段。**禁止以本 placeholder 状态直接进入 apply 期**。

---

## 1. 现状

W3 round 实施的 `LapAlignment.alignByDistance` 用累计距离作为对齐 key，把比较圈每个 distance bucket 重采样到与参考圈同样的 distance grid 上。这个算法的**固有限制**（W3 design [R4] 已透明声明）：

> 当比较圈轨迹与参考圈轨迹在同一段距离区间走的物理路径不同（如 turn 切线不同 / racing line 偏移），同一 distance bucket 在两圈的物理位置（lat/lon）会不同。

W3 spec.md 已透明声明此限制不属本 round scope，由本 follow-up round 解决。

## 2. 数据证据

**待立项时收集**：
- 真实赛道（如华为 8KE0219522008434 路测的天府赛车场）多圈对比数据
- turn 内 racing line 偏移的典型像素 / 米差距
- user 拖动 cursor 在偏离段的体感困惑度（P0 / P1 / P2）

**已知数据点**：
- W3 测试 case A-F 全部基于 mock LapTelemetry（lat/lon 完全一致或线性递增），未覆盖真实 racing line 偏移
- LapAlignment.alignByDistance 在偏离段返回的 `samplesPerLap[i][k].lat/lon` 是比较圈的 lat/lon（不是参考圈），但 `elapsedMsInLap` 是对比时间——**这正是 user 在 Tier 2 屏看到的状态**

## 3. 方案对比（待补全）

至少需评估以下三选：

- **A. UI 层报警**（轻量）：cursor 处计算 `dist(refLapLatLon, compareLapLatLon)`，> 阈值时 cursor tooltip 染色 + 文字提示
- **B. 数据层标记**（中量）：LapAlignmentResult 新增 `divergenceFlags: BooleanArray`，alignByDistance 计算每个 grid 点的 lat/lon 偏离并打标
- **C. 双轴可视化**（重量）：地图上画两条轨迹叠加 + cursor 关联，让 user 直观看偏离段

**待立项时**：补对比表（实施成本 / UX 价值 / 性能 / 与现有契约兼容性 / Tier 2 屏 ViewModel 改动量）

## 4. 推荐方案 + 数学/性能分析（待补全）

**当前倾向**（无 hard data 支撑）：方案 A 轻量起步，留 B 作 follow-up。

**待立项时**：
- 阈值 `divergenceThresholdMeters` 默认值（参考 W3 高频盲点 #6 NOT NULL DEFAULT 哨兵风险，避免 0.0 / -1.0 哨兵）
- 计算成本（每帧 vs 每 lap selection 一次）
- 与 W3 alignByDistance 的调用关系（是 alignByDistance 内部计算还是外部 wrapper）

## 5. 实施约束（MUST 条款）

**已确定**（继承自 W3）：
- **MUST NOT** 把轨迹偏差判定写在 `LapAlignment.alignByDistance` 纯函数内（保持纯函数职责单一），新增独立 `TrajectoryDivergenceDetector` 函数 OR LapAlignmentResult 新增字段计算
- **MUST NOT** 在每帧 cursor 拖动期重算偏差（参 W3 OQ2 性能约束："SHALL 在 lap selection 改变时调用一次，cursor 拖动期 SHALL NOT 重调"）
- **MUST** 与 Tier 2 屏 ViewModel 数据流契约一致——cursor 处偏差计算 = 一次 lap selection + 多次 cursor 查询的 O(1) lookup

**待立项时补**：阈值常量位置、UI tooltip 视觉规则（与 V2 视觉约束兼容）、染色与 V1 brake/V1 record 副标降级 pattern 是否复用

## 6. 单元测试覆盖（待补全）

至少需覆盖：
- 反例 case：两圈轨迹 racing line 偏移（mock lat/lon 同 distance 但物理位置不同）→ 锁死偏差计算 > 阈值时报警激活
- 反例 case：两圈轨迹完全一致（W3 case A 复用）→ 锁死无报警
- 边界 case：偏差恰好等于阈值
- v3 高频盲点 #5：至少 1 反例 scenario 锁死"违反约束时测试 fail"

## 7. 与当前 round 的协同关系

- **W3 lap-comparison-time-align**：本 round 是 W3 [R4] mitigation 的实质落地。W3 已用 spec 反例锁死"算法固有限制"，本 round 解锁"识别 + 提示"能力。本 round 启动时 W3 已合回 + 完整 review 闭环（含 hostile L2）。
- **Tier 2 lap-detail-screen-with-cursor round**：本 round 的输出（divergence 标记 / 阈值 / UI 报警）必须与 Tier 2 屏 cursor 拖动 UX 契约一致。**强烈建议合并 scope**——避免独立立项后再做一次 cross-round drift review。
- **CLAUDE.md v3 高频盲点 #16（跨 round 共享字段扩展同步）**：如果本 round 给 LapTelemetrySample / LapAlignmentResult 加新字段，MUST 触发 W2 chart-and-map-components 已合回 round 的 drift review（chart 组件是否消费新字段是否 graceful fallback）。

## 8. 不并入当前 round（W3）的理由

W3 scope 收敛到"距离对齐核心算法 + 6 case 单测"。轨迹偏差报警涉及：
- UI 层：tooltip 染色 / 视觉规则 / Tier 2 屏数据流
- 数据层：LapAlignmentResult 字段扩展 / divergence 计算策略
- 测试层：真实赛道路测数据 / 视觉签收 gate

这些跨 W3 + Tier 2 屏 + UI 视觉规则三层；W3 期间合并 scope 会拖慢 W3 闭环（W3 已经 5 轮 L1 review，多 1 轮就拖到 0.5+ 天）。**P0/P1 严重度评估**：W3 已 spec 透明声明 [R4] 是固有限制，无任何路径产出"假数据" → P2/P3 体验问题，不阻塞 Phase 1 数据底座 + 不阻塞 Tier 2 屏起步。

## 9. 立项节奏估算

**当前估算**（待立项时复审）：
- 复杂度：medium（cross-layer 但 scope 收敛在 alignment + Tier 2 屏 cursor 渲染）
- 预估工时：0.5-1 天（含 L1 review 2-3 轮 + L2 review 1 轮 + 真机签收）
- 推荐时机：
  - **首选**：合并到 Tier 2 `lap-detail-screen-with-cursor` round scope（同 round 内拍板，避免 cross-round drift review）
  - **次选**：Tier 2 屏闭环后单独 round（避免 Tier 2 scope 膨胀）
  - **最差**：Phase 2（路测发现报警频次过高 → 需要算法升级到方案 B/C）

---

## 修订历史

- **2026-05-05**：W3 lap-comparison-time-align L2 hostile adversarial review（Opus 单线）发现本 follow-up memo placeholder 缺失（违反 CLAUDE.md "延期立项的设计 memo 规矩" 三处沉淀第 1 项），由 CC 主会话补建 placeholder（非完整 9 章设计，立项时需补全）。
