# 实施任务（依赖顺序）

本战役 scope 极小，2 个 Requirement × 共 4 行代码改动 + 2 行测试 @Ignore 解除
+ 2 条新增边界测试。按 3 组组织：

1. **R1 + R2 代码修复**：parser line 178 + 185 同源 mask 删除（两处）
2. **R2 测试解封**：RP16 / RP19 去 @Ignore + 新增 2 条边界测试
3. **合流门槛 + backlog 迁档**

---

## 1. R1 + R2 parser lat/lon signed int32 解码修复

- [x] 1.1 **预先审计下游消费者**确认无 "负 lat/lon = 异常数据" 假设。
      **机器门槛 grep**（用 `rg` 排除 build 产物、收窄 pattern 到 lat/lon 显式
      与 0 比较或与负号比较的场景，codex 2026-04-24 P2-2 复核后的命令）：
    ```bash
    rg -n "latitude\s*<\s*0|longitude\s*<\s*0|latitude\s*[!=]=\s*-|longitude\s*[!=]=\s*-" \
      core/*/src feature/*/src app/src
    ```
      **预期**：零命中于**生产代码**。如有命中，按以下**人工审计规则**甄别：
      - 命中在 `src/test/**` 的 fixture 断言（例如 `assertEquals(..., -33.8688, ..., 0.0001)`）
        **不算** "异常过滤"假设，可忽略
      - 命中在 `src/main/**` 的生产代码，**必须**人工审视其是否把负 lat/lon
        当异常（如 `if (gpsData.latitude < 0) return` 这类），有则先改下游
        再动 parser
      **反例**：原来的 `grep -Rn "...lat.*-1..." core/ feature/ app/` 会扫进
      `core/**/build`、误命中 `latitude + 1e-12` 等 test fixture（`1e-12` 含 `-1`
      字符），不能作为机器门槛（codex 实测）。
- [x] 1.2 **代码改动纬度**：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt:178`
    ```kotlin
    BEFORE:
    val currentLatitude = (latInt.toLong() and 0xFFFFFFFFL) / 10000000.0

    AFTER:
    // A16: 协议 / ESP32 ino 明确 lat 是 signed int32。`.toLong() and 0xFFFFFFFFL`
    //      会把 signed 抹成 unsigned，所有南纬解成 +[180°, 400°] 大数。Kotlin
    //      Int / Double 除法会按 IEEE 754 保留符号位扩展为 Double，结果 signed。
    val currentLatitude = latInt / 10_000_000.0
    ```
- [x] 1.3 **代码改动经度**：`RaceChronoParser.kt:185` 对称修复：
    ```kotlin
    BEFORE:
    val currentLongitude = (lonInt.toLong() and 0xFFFFFFFFL) / 10000000.0

    AFTER:
    // A16: 协议 / ESP32 ino 明确 lon 是 signed int32。参考 latitude 同源修复。
    val currentLongitude = lonInt / 10_000_000.0
    ```
- [x] 1.4 **门槛自检**：`./gradlew :core:bluetooth:assembleDebug` BUILD SUCCESSFUL
      （无 unresolved reference / 类型错误）。

## 2. R2 测试解封 + 新增边界测试

- [x] 2.1 **去 RP16 `@Ignore`**：`core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt:278-284`
    删除 `@Ignore("暴露 parser 实际 bug：parseGpsData L164 ...")` 整段（跨多行），
    保留 `@Test` 和函数体不变。
- [x] 2.2 **去 RP19 `@Ignore`**：`RaceChronoParserTest.kt:321-325` 对称删除
    `@Ignore("暴露 parser 实际 bug：parseGpsData L171 ...")` 整段。
- [x] 2.3 **新增测试** `parseGpsData_southernHemisphereAndWesternHemisphere_decodeBothNegativeCorrectly`
      （对应 Spec R3 Scenario 1）：
    ```kotlin
    @Test
    fun parseGpsData_southernHemisphereAndWesternHemisphere_decodeBothNegativeCorrectly() {
        // 布宜诺斯艾利斯：两轴同时为负
        val data = createValidGpsData20(latitude = -34.6037, longitude = -58.3816)
        val result = parser.parseGpsData(data, createTestData())
        assertEquals("南纬", -34.6037, result.latitude, 0.0001)
        assertEquals("西经", -58.3816, result.longitude, 0.0001)
    }
    ```
- [x] 2.4 **新增测试** `parseGpsData_extremeBoundaryValues_nearPolesAndAntimeridian`
      （对应 Spec R3 Scenario 2）：
    ```kotlin
    @Test
    fun parseGpsData_extremeBoundaryValues_nearPolesAndAntimeridian() {
        // 接近 -90° 南极 + -180° 反子午线
        val data = createValidGpsData20(latitude = -89.9999, longitude = -179.9999)
        val result = parser.parseGpsData(data, createTestData())
        assertEquals("接近南极纬度", -89.9999, result.latitude, 0.00001)
        assertEquals("接近反子午线经度", -179.9999, result.longitude, 0.00001)
    }
    ```
- [x] 2.5 **验证 `@Ignore` 残留数量**（只统计 JUnit 注解行，排除文件头
      `@IgnoreFormatCheck`）：
    ```bash
    grep -c "^[[:space:]]*@Ignore\b" core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt
    ```
    输出 `1`（只剩 RP22 的 JUnit `@Ignore`，留给 altitude change）。
    **反例**：裸 `grep -c "@Ignore"` 当前返回 `4`（含文件头 `// @IgnoreFormatCheck`
    + 3 条 `@Ignore(...)`），不能作为机器门槛（codex 2026-04-24 P2-1 复核发现）。
- [x] 2.6 **门槛自检**：`./gradlew :core:bluetooth:testDebugUnitTest --tests
      "*RaceChronoParserTest*"` 全绿，覆盖：
    - RP16 / RP19 现在参与执行且 PASS
    - 新增 2 条边界测试 PASS
    - 原 RP01~RP15 / RP17 / RP18 / RP20 / RP21 / RP23~RP40 均保持 PASS
    - RP22 仍显示 SKIPPED（留给 altitude change）

## 3. 合流门槛 + backlog 迁档（Non-negotiable）

- [x] 3.1 **Spec 验证**：`openspec validate fix-parser-signed-int-decoding --strict`
      退出码 0，无警告。（命令按项目最新约定使用 `openspec` 直接调用，避免绑定
      环境特定全路径；若当前 shell 未将 `openspec` 加入 PATH，按
      `memory/reference_openspec_cli_location.md` 配置 alias 或用
      `$(npm config get prefix)/bin/openspec`）
- [x] 3.2 **`core:bluetooth` 测试全绿**：`./gradlew :core:bluetooth:testDebugUnitTest`
      BUILD SUCCESSFUL。
- [x] 3.3 **`core:bluetooth` 编译**：`./gradlew :core:bluetooth:assembleDebug`
      BUILD SUCCESSFUL（不应有 unresolved / 类型错误）。
- [x] 3.4 **下游零回归**：
    - `./gradlew :core:domain:test` BUILD SUCCESSFUL
    - `./gradlew :feature:test:testDebugUnitTest` BUILD SUCCESSFUL（战役 A
      时钟 + 战役 C filter / engine 守卫 + 战役 G BLE 生命周期契约全绿）
    - `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL
- [x] 3.5 **E2E 契约全绿**：
      `./gradlew :feature:test:testDebugUnitTest --tests
      "*EndToEndLapTimingContractTest*"` 全绿（本 change 理论上不应影响 E2E，
      但作为保险回归）。
- [x] 3.6 **backlog 拆条 A16 → A16a + A16b**（评审方 codex 2026-04-24 P1-1 要求
      的闭环合规策略）：
      > **核销闭环原则**：未解决的 altitude 风险不能随 A16 整条迁出 pending。
      > codex 建议两方案，本 change 采纳**方案 1 拆条目**（更清晰，与 Q2 "RP22
      > 从 A16 拆出" 决策直接对齐）。

      步骤：

      (i) 在 `docs/superpowers/reviews/attack-backlog.md` 第一节 🔴 pending 的
          A16 原位置，把原条目**拆成两条独立条目**：

      ```
      ### A16a：lat/lon signed int32 解码 bug（RP16 / RP19）（战役 D 尾巴 /
        parser 字段解析）

      - **来源**：A16 原条目拆分（2026-04-24，本 change 实施时由 codex P1-1
        复核建议）
      - **证据**：`core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt:178, 185`
      - **攻击点**：`(latInt/lonInt).toLong() and 0xFFFFFFFFL` 把 signed int32
        抹成 unsigned，违反协议 int32 契约 + ESP32 ino 二进制补码 big-endian
        打包约定。南纬 / 西经全部解成 +[180°, 400°] 大数。
      - **核销条件**：
        - (1) 删除 parser line 178 + 185 的 `.toLong() and 0xFFFFFFFFL` mask
        - (2) RP16 / RP19 `@Ignore` 去除，断言通过
        - (3) 新增南半球 + 西半球 + 极地 / 反子午线边界测试
      - **状态**：🔴 `pending`（归属本 change `fix-parser-signed-int-decoding`）

      ---

      ### A16b：altitude 编解码四方契约不一致（RP22 + ino 截断 bug）（战役 D
        尾巴 / 协议对齐）

      - **来源**：A16 原条目拆分（2026-04-24，本 change 实施时由 codex P1-1
        复核建议）
      - **证据**：
        - `core/bluetooth/src/main/java/com/blazepush/core/bluetooth/parser/RaceChronoParser.kt:189-195`
        - `docs/RaceChrono_BLE_Protocol.md:94-99`
        - `docs/RaceChrono_ESP32_M9N.ino:294-298`
        - `core/bluetooth/src/test/java/com/blazepush/core/bluetooth/parser/RaceChronoParserTest.kt:370`
      - **攻击点**：bit15=0 / bit15=1 两分支公式四方（官方 BLE DIY API / 协议
        文档 / parser / RaceChronoParserTest helper）不一致，所有 altitude 字段
        被错解（见 `2026-04-24-a16-altitude-encoding-tri-party-audit.md` § 3 /
        § 6.2-6.4）；ESP32 ino 自身在 [2776.8m, 6053.4m] 区间有截断 bug。
      - **核销条件**：由独立 change `fix-altitude-encoding-contract-alignment`
        承接，包括：
        - (1) parser bit15=0 / bit15=1 两分支公式改为与 ino 编码对称
        - (2) 协议文档 § 3.4 altitude 公式修订
        - (3) RaceChronoParserTest helper 编码 + RP22 测试数据重构
        - (4) simulator generator 同步对齐
        - (5) [2776.8m, 6053.4m] 区间 ino 截断 bug 决策（改 ino 或接受精度
          丢失契约）
        - (6) 下游消费者已审计（仅透传 / 平滑 / 展示，不影响判圈）
      - **状态**：🔴 `pending`（等待 change `fix-altitude-encoding-contract-alignment`
        起 proposal）
      ```

      (ii) **删除**原 A16 整条（避免重复）。

      (iii) **附录 "攻击点编号总览" 表格**：
        - 删除 `| A16 | parser RP16/RP19/RP22 signed int bug | 🔴 | D 尾巴 |` 行
        - 新增两行：
          ```
          | A16a | parser lat/lon signed int32（RP16/RP19） | 🔴 | D 尾巴 (本 change) |
          | A16b | altitude 四方契约不一致（RP22 + ino 截断） | 🔴 | D 尾巴（fix-altitude-encoding-contract-alignment） |
          ```
- [x] 3.7 **A16a 迁 🟢 `pending_review`**（本 change 完成后做）：
      将刚拆出的 A16a 条目整体搬到第三节 🟢 `pending_review`，状态行追加：
      ```
      - **状态**：🟢 `pending_review`（@impl, commit <hash>, 2026-04-24）
        - 🔴 → 🟡：@impl 认领（2026-04-24）
        - 🟡 → 🟢：commit <hash>，lat/lon signed int32 解码修复 + RP16/RP19
          去 @Ignore + 2 条边界测试；本战役合流门槛全绿（2026-04-24）
      ```
      **A16b 保持 🔴** 在第一节不动（绑定独立 change）。
- [x] 3.8 **附录表格更新**：附录 "攻击点编号总览" 表格中：
      - A16a 状态列改为 `🟢（commit <hash>，战役 D 尾巴）`
      - A16b 保持 `🔴`（等 altitude change）
- [x] 3.9 **拆分 + 迁档 grep 自检**：
    - `grep -c "^### A16：" docs/superpowers/reviews/attack-backlog.md` 输出 `0`
      （原 A16 整条已删）
    - `grep -c "^### A16a：" docs/superpowers/reviews/attack-backlog.md` 输出 `1`
      （A16a 条目存在，在第三节）
    - `grep -c "^### A16b：" docs/superpowers/reviews/attack-backlog.md` 输出 `1`
      （A16b 条目存在，在第一节）
    - `awk '/^## 一、.*pending/,/^## 二、/' docs/superpowers/reviews/attack-backlog.md
      | grep -c "^### A16a"` 输出 `0`（A16a 不在第一节）
    - `awk '/^## 一、.*pending/,/^## 二、/' docs/superpowers/reviews/attack-backlog.md
      | grep -c "^### A16b"` 输出 `1`（A16b 在第一节）
    - `awk '/^## 三、.*pending_review/,/^## 四、/' docs/superpowers/reviews/attack-backlog.md
      | grep -c "^### A16a"` 输出 `1`（A16a 在第三节）

## 4. Commit 策略

本 change scope 小，**1 个代码 commit + 1 个 backlog commit**（后者不进 git，
因为 attack-backlog.md 被 `.git/info/exclude` 全局忽略 `*.md`）：

1. **commit 1**：`fix(bluetooth): 战役 D 尾巴 A16 lat/lon signed int32 解码修复`
   - `RaceChronoParser.kt` 两处 mask 删除
   - `RaceChronoParserTest.kt` 去 RP16 / RP19 @Ignore + 新增 2 条边界测试
   - body 要点：
     - 违反协议 int32 签名约定的 bug 来源
     - 手算 `-33.8688°` / `-122.4194°` 的当前错解值（+395.63° / +307.08°）
     - 生产影响：南半球 + 西半球所有坐标
     - RP22 / altitude 移交独立 change 的说明
     - codex 2026-04-24 复核批准（Q1 真相源 + Q2 scope 拍板）

2. **backlog 迁档（本地文档，不进 git）**：按 §3.6 把原 A16 拆为 A16a
   （lat/lon）+ A16b（altitude）；**只** 将 A16a 迁 🟢 并附 commit 1 hash；
   **A16b 保持 🔴**，绑定独立 change `fix-altitude-encoding-contract-alignment`。

**格式约束**：
- Conventional Commits `fix(bluetooth): ...`
- body 含 "A16" 攻击点 ID 便于 `git log --grep`
- Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
- 遵守战役 G commit 阶段 B 方案纪律：新建 / 改动的文件若触发 legacy 格式 hook，
  处理路径同战役 G（新文件合规 / legacy 动过的文件加 @IgnoreFormatCheck + 逐条
  理由）
