# fix-lap-timing-campaign-c-tail-cleanup code review

- **日期**：2026-04-24
- **评审方**：codex
- **评审对象**：commit `7ee9122`（A36 / A43 / A44）
- **结论**：🔴 暂不核销 A36 / A43 / A44，需修 1 个 P2 后重提 mini review

## 1. Findings

### P2-1 · A43 仍有大小写 / 中文语义残留，命名纠偏未闭环

- **位置**：`core/domain/src/test/java/com/blazepush/core/domain/usecase/GpsDataFilterTest.kt:382-408, 935-954`
- **问题**：
  - A43 的目标是 `circularMedian → circularMean` 命名纠偏，避免维护者误以为该算法是"中位数 / 对离群鲁棒"。
  - 实施确实把 main 函数和部分测试注释改成了 `circularMean`，但仍有大小写绕过和中文语义残留：
    - `GF20b_bearingCircularMedian_crossesZero`
    - GF09 / GF20b 多处注释仍写"循环中位数应..."
  - 当前 tasks §4.8 的 `grep -rn "circularMedian"` 是大小写敏感的，所以会漏掉 `CircularMedian`。这会让合流门槛显示 PASS，但 A43 的语义目标并未真正闭环。
- **建议修订**：
  - 将 `GF20b_bearingCircularMedian_crossesZero` 改为 `GF20b_bearingCircularMean_crossesZero`。
  - 将 GF09 / GF20b 中所有描述当前算法的"循环中位数"改为"循环均值"或"循环向量均值"。
  - 合流 grep 升级为大小写不敏感 + 中文残留：
    ```bash
    rg -n -i "circularmedian|循环中位数" core/domain/src feature/test/src core/bluetooth/src
    ```
    期望无残留。若需要保留"普通中位数"对照说明，建议只保留 `普通中位数`，不要再出现 `循环中位数`。

## 2. 已通过项

- A36：`Track.orderedSectorGates` 已落地，engine 两处 + UI 一处消费方收敛合理；UI 额外 sort 属于 A36 单点真理意图范围。
- A44：`wrappedDeltaLon` 实现和物理自洽 antimeridian fixture 已对齐；新测试能硬区分 v1/v2。
- `openspec validate fix-lap-timing-campaign-c-tail-cleanup --strict`：此前 spec/tasks review 已通过；本轮未发现 spec 级新问题。

## 3. 核销建议

暂不迁 A36 / A43 / A44 到 ✅。修复 A43 残留后重跑：

- `rg -n -i "circularmedian|循环中位数" core/domain/src feature/test/src core/bluetooth/src`
- `./gradlew :core:domain:test --tests "*GpsDataFilterTest*"`

修订项 1 条，未达到 5 条，不单独产出 patches 清单。

---

## 4. mini review：commit `48837a4` 复核

- **日期**：2026-04-24
- **评审对象**：commit `48837a4`（P2 A43 命名纠偏残留修订）
- **结论**：🔴 仍差 1 个 P2 文案尾巴，暂不核销

### 4.1 上轮 finding 关闭情况

主体已修：

- `GF20b_bearingCircularMedian_crossesZero` 已改为 `GF20b_bearingCircularMean_crossesZero`
- GF09 / GF20b 正文注释里的当前算法描述已改为"循环均值"
- `./gradlew :core:domain:test --tests "*GpsDataFilterTest*"`：BUILD SUCCESSFUL

但新增的文件头格式豁免说明又引入了同一组残留词。

### 4.2 新发现

#### P2-2 · `@IgnoreFormatCheck` 说明文本导致升级后的残留 grep 仍失败

- **位置**：`core/domain/src/test/java/com/blazepush/core/domain/usecase/GpsDataFilterTest.kt:5-6`
- **问题**：
  - 上轮要求的最终门槛是：
    ```bash
    rg -n -i "circularmedian|循环中位数" core/domain/src feature/test/src core/bluetooth/src
    ```
  - 当前实测仍命中：
    - 文件头说明里的 `CircularMedian→CircularMean`
    - 文件头说明里的 `"循环中位数→循环均值"`
  - 这些是修订说明，不是业务注释，但它们仍在 `core/domain/src` 内，会让机器门槛失败。
- **建议修订**：
  - 将文件头说明改成不含被禁词，例如：
    - `旧英文方法名→新英文方法名`
    - `旧中文术语→循环均值`
  - 或把豁免说明缩短为："本次仅做 A43 命名纠偏残留清理与格式豁免，legacy 格式债不在 scope"。

### 4.3 复核记录

- `./gradlew :core:domain:test --tests "*GpsDataFilterTest*"`：BUILD SUCCESSFUL
- `rg -n -i "circularmedian|循环中位数" core/domain/src feature/test/src core/bluetooth/src`：仍有 2 行命中，均位于 `GpsDataFilterTest.kt` 文件头豁免说明

### 4.4 核销建议

修掉文件头 2 处残留词后重跑 grep；无需再跑全量，只要 grep 为空即可过 mini review。

---

## 5. mini review：commit `9605258` 复核

- **日期**：2026-04-24
- **评审对象**：commit `9605258`（P2 豁免说明禁词残留修订）
- **结论**：✅ 通过，A36 / A43 / A44 可迁入 ✅ `resolved`

### 5.1 上轮 finding 关闭情况

| Finding | 复核结论 |
|---|---|
| P2-2 `@IgnoreFormatCheck` 说明文本导致升级后的残留 grep 仍失败 | 已修。文件头改为 "旧英文名 → 新英文名" / "旧中文术语 → 循环均值"，不再包含禁词 |

### 5.2 复核记录

- `rg -n -i "circularmedian|循环中位数" core/domain/src feature/test/src core/bluetooth/src`：无残留
- `./gradlew :core:domain:test --tests "*GpsDataFilterTest*"`：BUILD SUCCESSFUL
- A36 / A44 已在 commit `7ee9122` code review 中通过；本轮仅补齐 A43 命名纠偏门槛

### 5.3 backlog 同步

- A36 / A43 / A44 已迁入第五节 ✅ resolved
- 绑定 commit 链：`7ee9122` + `48837a4` + `9605258`
