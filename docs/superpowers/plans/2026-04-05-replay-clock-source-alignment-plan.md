# Replay 时钟源对齐 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 replay 模式下的圈速计算使用数字资产中的原始时间轴，而不是 simulator 发送时的本机系统时钟。

**Architecture:** 保持公共协议字节格式不变，只调整 simulator 在 `REAL_TRACK_REPLAY` 场景下对现有时间字段的取值来源。接收端继续按现有协议解码并透传 `GpsData.timestamp`，圈速引擎无需改公式，只需要吃到正确的协议时间值。

**Tech Stack:** Kotlin、Android、Gradle、JUnit4、现有 ESP32 GPS 协议编码/解码链路

---

## 文件结构与职责

- Modify: `simulator/src/main/java/com/blazepush/simulator/data/GpsDataGenerator.kt`
  - 当前 GPS 主包/时间包编码器；本次要把 replay 模式下的时间值来源从 `System.currentTimeMillis()` 切到 `ReplaySample.timestampMillis`
- Modify: `simulator/src/main/java/com/blazepush/simulator/viewmodel/SimulatorViewModel.kt`
  - 当前 replay 帧播放入口；需要确保每次发包前 generator 已持有当前 replay sample 的原始 timestamp 语义
- Test: `simulator/src/test/java/com/blazepush/simulator/data/GpsDataGeneratorReplayTest.kt`
  - 已有 replay 编码测试；补充 replay 时间编码断言，锁定协议时间字段在 replay 模式下来自数字资产
- Create: `simulator/src/test/java/com/blazepush/simulator/data/GpsDataGeneratorReplayClockTest.kt`
  - 新增 focused 测试，验证主包 time bits 与时间包 date/hour 在 replay 模式下与 `ReplaySample.timestampMillis` 对齐

---

### Task 1: 锁定 replay 模式下协议时间编码的失败测试

**Files:**
- Modify: `simulator/src/test/java/com/blazepush/simulator/data/GpsDataGeneratorReplayTest.kt`
- Create: `simulator/src/test/java/com/blazepush/simulator/data/GpsDataGeneratorReplayClockTest.kt`
- Reference: `simulator/src/main/java/com/blazepush/simulator/data/GpsDataGenerator.kt`
- Reference: `simulator/src/main/java/com/blazepush/simulator/data/replay/RaceChronoReplayModels.kt`

- [ ] **Step 1: 写主包 hour 内毫秒失败测试**

```kotlin
@Test
fun `replay sample drives main packet time bits`() {
    val generator = GpsDataGenerator(scenario = TestScenario.REAL_TRACK_REPLAY)
    val sample = ReplaySample(
        timestampMillis = 1773478969360L,
        latitude = 30.49697,
        longitude = 104.43317,
        speedKmh = 89.0712,
        bearingDegrees = 182.31,
        satellites = 11,
        fixType = 1,
        hdop = 0.8,
        altitudeMeters = 444.2,
        altitudePrecisionMeters = 0.0
    )

    generator.applyReplaySample(sample)
    val data = generator.generateGpsMainData()
    val encodedHalfMillis =
        ((data[0].toInt() and 0x1F) shl 16) or
        ((data[1].toInt() and 0xFF) shl 8) or
        (data[2].toInt() and 0xFF)

    val expectedHalfMillis = (((sample.timestampMillis % 3_600_000L).toInt()) / 2)
    assertEquals(expectedHalfMillis, encodedHalfMillis)
}
```

- [ ] **Step 2: 写时间包 date + hour 失败测试**

```kotlin
@Test
fun `replay sample drives gps time packet date and hour`() {
    val generator = GpsDataGenerator(scenario = TestScenario.REAL_TRACK_REPLAY)
    val sample = ReplaySample(
        timestampMillis = 1773478969360L,
        latitude = 30.49697,
        longitude = 104.43317,
        speedKmh = 89.0712,
        bearingDegrees = 182.31,
        satellites = 11,
        fixType = 1,
        hdop = 0.8,
        altitudeMeters = 444.2,
        altitudePrecisionMeters = 0.0
    )

    generator.applyReplaySample(sample)
    val data = generator.generateGpsTimeData()
    val encodedDateAndHour =
        ((data[0].toInt() and 0x1F) shl 16) or
        ((data[1].toInt() and 0xFF) shl 8) or
        (data[2].toInt() and 0xFF)

    val calendar = java.util.Calendar.getInstance().apply {
        timeInMillis = sample.timestampMillis
    }
    val expected =
        (calendar.get(java.util.Calendar.YEAR) - 2000) * 8928 +
        calendar.get(java.util.Calendar.MONTH) * 744 +
        (calendar.get(java.util.Calendar.DAY_OF_MONTH) - 1) * 24 +
        calendar.get(java.util.Calendar.HOUR_OF_DAY)

    assertEquals(expected, encodedDateAndHour)
}
```

- [ ] **Step 3: 运行测试确认当前实现失败**

Run:
```bash
zsh -lc 'cd "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing" && ./gradlew :simulator:testDebugUnitTest --tests "com.blazepush.simulator.data.GpsDataGeneratorReplayClockTest"'
```

Expected: FAIL，断言显示编码出的时间字段取自当前系统时间，而不是 `ReplaySample.timestampMillis`

- [ ] **Step 4: 保留现有回归测试不动**

确认以下已有测试仍作为回归基线存在：

```kotlin
@Test
fun `applyReplaySample updates encoded gps fields`()

@Test
fun `updateSimulation after replay sample keeps replay bearing and position`()
```

- [ ] **Step 5: 提交测试基线**

```bash
git add \
  "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/simulator/src/test/java/com/blazepush/simulator/data/GpsDataGeneratorReplayTest.kt" \
  "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/simulator/src/test/java/com/blazepush/simulator/data/GpsDataGeneratorReplayClockTest.kt"
git commit -m "test: lock replay clock source in simulator protocol encoding"
```

### Task 2: 让 replay 模式按资产时间填充现有协议时间字段

**Files:**
- Modify: `simulator/src/main/java/com/blazepush/simulator/data/GpsDataGenerator.kt`
- Reference: `simulator/src/main/java/com/blazepush/simulator/data/replay/RaceChronoReplayModels.kt`
- Reference: `simulator/src/main/java/com/blazepush/simulator/viewmodel/SimulatorViewModel.kt`

- [ ] **Step 1: 在 generator 内保存 replay 模式的时间源**

把当前只保存位置/速度/方位的状态扩展为同时保存 replay timestamp。实现应保持非 replay 场景继续走系统时间。

```kotlin
private var replayTimestampMillis: Long? = null

private fun currentTimestampMillis(): Long {
    return if (scenario == TestScenario.REAL_TRACK_REPLAY) {
        replayTimestampMillis ?: System.currentTimeMillis()
    } else {
        System.currentTimeMillis()
    }
}
```

- [ ] **Step 2: applyReplaySample 注入 replay 原始时间**

```kotlin
fun applyReplaySample(sample: ReplaySample) {
    currentLatitude = sample.latitude
    currentLongitude = sample.longitude
    currentSpeed = sample.speedKmh.toFloat()
    bearing = sample.bearingDegrees.toFloat()
    satellites = sample.satellites
    hdop = sample.hdop.toFloat()
    altitude = sample.altitudeMeters.toFloat()
    replayTimestampMillis = sample.timestampMillis
}
```

- [ ] **Step 3: 主包 hour 内毫秒改为使用 currentTimestampMillis**

```kotlin
fun generateGpsMainData(): ByteArray {
    val data = ByteArray(20)
    val timeMs = ((currentTimestampMillis() % 3_600_000L).toInt()) / 2
    val timeHigh = (timeMs shr 16)
    data[0] = (((syncCounter and 0x07) shl 5) or (timeHigh and 0x1F)).toByte()
    data[1] = ((timeMs shr 8) and 0xFF).toByte()
    data[2] = (timeMs and 0xFF).toByte()
    ...
}
```

- [ ] **Step 4: 时间包 date + hour 改为使用 currentTimestampMillis**

```kotlin
fun generateGpsTimeData(): ByteArray {
    val data = ByteArray(3)
    val now = currentTimestampMillis()
    val calendar = java.util.Calendar.getInstance().apply {
        timeInMillis = now
    }
    val year = calendar.get(java.util.Calendar.YEAR)
    val month = calendar.get(java.util.Calendar.MONTH) + 1
    val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
    val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
    val yearOffset = if (year > 2000) year - 2000 else 0
    val dateAndHour = yearOffset * 8928 + (month - 1) * 744 + (day - 1) * 24 + hour
    ...
}
```

- [ ] **Step 5: 保持非 replay 行为不变**

明确保留 fallback：

```kotlin
return if (scenario == TestScenario.REAL_TRACK_REPLAY) {
    replayTimestampMillis ?: System.currentTimeMillis()
} else {
    System.currentTimeMillis()
}
```

这样真实设备和普通模拟模式不受影响。

- [ ] **Step 6: 运行 replay 单测确认通过**

Run:
```bash
zsh "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/gradlew" :simulator:testDebugUnitTest --tests "com.blazepush.simulator.data.GpsDataGeneratorReplayTest" --tests "com.blazepush.simulator.data.GpsDataGeneratorReplayClockTest"
```

Expected: PASS，且原有位置/速度/方位编码测试仍通过

- [ ] **Step 7: 提交最小实现**

```bash
git add \
  "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/simulator/src/main/java/com/blazepush/simulator/data/GpsDataGenerator.kt" \
  "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/simulator/src/test/java/com/blazepush/simulator/data/GpsDataGeneratorReplayTest.kt" \
  "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/simulator/src/test/java/com/blazepush/simulator/data/GpsDataGeneratorReplayClockTest.kt"
git commit -m "fix: align replay protocol time with asset timestamps"
```

### Task 3: 验证 replay 播放链与接收端圈速时间恢复

**Files:**
- Reference: `simulator/src/main/java/com/blazepush/simulator/viewmodel/SimulatorViewModel.kt`
- Reference: `feature/test/src/main/java/com/blazepush/feature/test/viewmodel/TestSessionViewModel.kt`
- Reference: `feature/test/src/main/java/com/blazepush/feature/test/usecase/LapTimingEngine.kt`
- Verify with logs: `/tmp/huawei_clean_repro_debug_log.txt`

- [ ] **Step 1: 运行 simulator 相关单测全套**

Run:
```bash
zsh "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/gradlew" :simulator:testDebugUnitTest
```

Expected: PASS

- [ ] **Step 2: 构建并安装 debug 包到真机**

Run:
```bash
zsh "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/gradlew" :app:assembleDebug :app:installDebug
```

Expected: BUILD SUCCESSFUL，APK 成功安装到默认 Huawei 设备

- [ ] **Step 3: 清空旧日志后复现一轮 replay**

Run:
```bash
adb logcat -c
adb shell run-as com.blazepush.app sh -c 'rm -f files/debug_log.txt'
```

Expected: 无报错；随后在真机上进入圈速调试并完成一轮 replay 复现

- [ ] **Step 4: 导出新日志并检查 gpsTs 是否回到资产时间轴**

Run:
```bash
adb logcat -d > /tmp/huawei_replay_clock_alignment_logcat.txt
adb shell run-as com.blazepush.app cat files/debug_log.txt > /tmp/huawei_replay_clock_alignment_debug_log.txt
```

然后检查：

```bash
python3 - <<'PY'
import re
from pathlib import Path
pat = re.compile(r'gpsTs=(\d+)')
vals = [int(m.group(1)) for line in Path('/tmp/huawei_replay_clock_alignment_debug_log.txt').read_text(errors='ignore').splitlines() if (m := pat.search(line))]
print('first=', vals[0])
print('last=', vals[-1])
print('delta_s=', round((vals[-1]-vals[0])/1000, 3))
PY
```

Expected: `gpsTs` 的推进尺度接近 replay 资产时间轴，而不是被发送墙钟明显拉长

- [ ] **Step 5: 检查 start-finish accepted 长段是否回到约 106.7s**

Run:
```bash
python3 - <<'PY'
import re
from pathlib import Path
pat = re.compile(r'targetGate=start-finish, .*?current=\([^\)]*ts=(\d+)\), accepted=true')
ts = [int(m.group(1)) for line in Path('/tmp/huawei_replay_clock_alignment_debug_log.txt').read_text(errors='ignore').splitlines() if (m := pat.search(line))]
for a, b in zip(ts, ts[1:]):
    print(round((b-a)/1000, 3))
PY
```

Expected: 长段接近 `106.68s`，不再稳定出现 `125.xs`

- [ ] **Step 6: 记录结果并决定是否需要下一轮 replay 窗口排查**

如果结果满足：

- 长段约 `106.7s`
- 短段仍只对应 loop seam 近邻

则本次任务完成。

如果仍异常，再单开新任务分析 replay 资产窗口切口；不要在本任务里顺手扩大范围。

- [ ] **Step 7: 提交验证完成状态**

```bash
git add \
  "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/simulator/src/main/java/com/blazepush/simulator/data/GpsDataGenerator.kt" \
  "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/simulator/src/test/java/com/blazepush/simulator/data/GpsDataGeneratorReplayTest.kt" \
  "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/simulator/src/test/java/com/blazepush/simulator/data/GpsDataGeneratorReplayClockTest.kt"
git commit -m "test: verify replay lap timing uses asset clock"
```

---

## 自检结果

- **Spec coverage:** 已覆盖 spec 中的四个核心要求：协议零改动、replay 模式切换时间源、receiver 保持现状、真机验证 `106.7s` / 消除 `125.xs`
- **Placeholder scan:** 无 TBD / TODO / “自行处理”类占位描述；每个代码步骤都给出了实际代码或命令
- **Type consistency:** 计划统一使用现有类型 `ReplaySample.timestampMillis`、`GpsData.timestamp`、`GpsSample.timestampMillis`，未引入新的协议字段名
