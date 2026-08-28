# Lap Alignment · Vincenty Fallback Deferred Memo

> 本文件由 `.git/info/exclude` 的 `*.md` 规则自动排除，**不进远端 git**，仅本地有效。
>
> 起源：`lap-comparison-time-align` round (Phase 1 第四个 round / W3) L1 review 第一轮 P2-3 + design R2 沉淀（2026-05-04）。
>
> 当前 disposition：**Phase 1 内不做**。5 km 内赛道精度 < 0.5 m 满足；Phase 1 收尾或 Phase 2 决定是否启动。

---

## 1. 现状

`LapAlignment.alignByDistance` (本 round) 用**局部 equirectangular 投影**计算累计距离：

```
Δx = (Δlon) * cosLat0 * π/180 * R
Δy = (Δlat) * π/180 * R
Δd = sqrt(Δx² + Δy²)
```

`R = 6378137 m`（WGS-84 长半轴），`cosLat0` 提前算 1 次（参考圈起点纬度）。

**已知精度边界**：

- 赛道**边界框对角线 < 5 km** + 纬度跨度 < 0.05° 时，累积误差 < 0.5 m（远小于 5m 步长）
- 性能：3 圈 × ~1500 帧 = 4500 次基础算术 + sqrt ≈ 45 μs

## 2. 数据证据

- TFIC LPCC 赛道（`PresetTrackCatalog` 锁定坐标）：边界框对角线 ~3.2 km，纬度跨度 < 0.005°——投影误差 < 0.05 m，远低于 5m 步长精度，无需升级
- **触发场景**（不在当前 Phase 1 scope）：跨城市赛道 / 极地赛道 / 跨纬度大赛道（如 Nürburgring 北赛道边界框对角线 ~7 km）

## 3. 方案对比

| 方案 | 精度 | CPU 成本（4500 次累计）| 实施风险 |
|---|---|---|---|
| 当前 equirectangular | < 0.5 m @ 5 km | ~45 μs | 低 |
| Haversine | 严格球面准确 | ~1.5 ms | 低（4500 次 sin/cos）|
| Vincenty（椭球修正）| 精度过剩（赛道范围）| ~4 ms（迭代收敛）| 中 |
| PROJ4 / Proj C 库 | 任意椭球 + 任意投影 | ~10 ms（DLL 加载 + 计算）| 高（引入 native lib 依赖）|

## 4. 推荐方案 + 数学/性能分析

**推荐**：Phase 1 收尾时如确认要做，**升级到 Haversine**（不引入 Vincenty / native lib）：

```
hav(θ) = sin²(θ/2)
a = hav(Δφ) + cos(φ1) * cos(φ2) * hav(Δλ)
Δd = 2 * R * atan2(sqrt(a), sqrt(1-a))
```

`R = 6378137` m，`φ1/φ2/Δφ/Δλ` 为弧度。

- 4500 次 ≈ 1.5 ms（4 次 sin/cos + 2 次 sqrt + 1 次 atan2 / 帧）
- 球面严格准确，无需 cosLat0 近似
- 不需要新依赖；Kotlin / Java math 标准库即可

Vincenty 在赛道范围（< 100 km）精度提升 < 1 m 但 CPU 高 3 倍，**不推荐**。

## 5. 实施约束（MUST 条款）

- MUST 在 `LapAlignment.kt` 内保留 equirectangular 实现作为 fast path（默认）；新增 `useHaversine: Boolean = false` 参数（或重构为策略模式 sealed class `DistanceMetric`），调用方按需启用
- MUST 在 spec scenario "局部平面投影累计距离精度" 加并行 scenario "Haversine 距离累计精度"，断言两者在 5 km 内偏差 < 0.5 m（自洽校验）
- MUST 在 D1 alternatives 重新评估，提供"何时建议启用 Haversine"决策树（赛道边界框对角线 > X km 阈值）
- MUST 不引入 native lib（PROJ4 等）

## 6. 单元测试覆盖

- 新增测试 case：`alignByDistance(laps, refIdx, step, useHaversine = true)` vs `useHaversine = false` 在 5 km 内输出差异 < 5m × gridSize（保证两路径 round-trip 一致）
- 新增测试 case：跨城市赛道（边界框对角线 = 6 km）输入 → equirectangular 与 Haversine 输出差异 < 1 m × gridSize（验证 Haversine 在大赛道仍准确）

## 7. 与当前 round 的协同关系

- 本 round（lap-comparison-time-align）已锁死 equirectangular 为默认实现
- `LapAlignmentResult.distanceStepMeters` / `gridSize` 等返回字段保持不变 —— 升级 metric 不改 API
- 调用方（Tier 2 多圈比较屏 round）默认调 `alignByDistance(laps, refIdx, step)`（不传 useHaversine），与本 round 行为一致；如未来跨城市赛道需启用，加 `useHaversine = true` 参数即可

## 8. 不并入当前 round 的理由

1. **当前 scope 过剩**：本 round 仅服务 TFIC LPCC（边界框 ~3.2 km）+ 未来 5 km 内赛道；equirectangular 精度 < 0.5 m 满足 5m 步长精度
2. **性能差异不显著**：1.5 ms vs 45 μs 在 sub-ms 范围内，cursor 拖动每秒 ~25 fps × 16ms 帧时间预算下不可感知
3. **review surface 控制**：本 round 已有 6 个 SHALL/MUST requirement + 5 个 case 单测；引入 Haversine 增 1-2 个 requirement + 2 case，超出 small 复杂度边界
4. **调用方信号未就绪**：Tier 2 多圈比较屏 round 尚未启动，未触发跨城市赛道实证场景

## 9. 立项节奏估算

- **触发条件**：(a) Phase 2 引入跨城市赛道 (b) 路测发现 5 km+ 赛道 cursor 拖动有"位置不一致"投诉 (c) Codex review 提出更高精度要求
- **预估工作量**：small 复杂度（< 100 行 + 1 文件）+ L1 review 1 轮 + 0 真机验证 = 0.3-0.5 day
- **建议 round name（kebab-case）**：`upgrade-lap-alignment-to-haversine` 或 `lap-alignment-vincenty-fallback`（两 round name 都候选）

---

**回顾节点**：Phase 1 收尾的 phase exit review（看板 §7.Phase 1）需对本 memo 决议——是合并到 Phase 2 / 推迟 / 移除。
