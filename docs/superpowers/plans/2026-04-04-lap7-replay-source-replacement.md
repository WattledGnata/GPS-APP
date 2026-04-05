# Lap7 Replay Source Replacement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 `session_20260314_170249_天府赛道_lap7.rcz` 生成新的 simulator replay 源，替换当前发射资产，并修正 replay 速度/方位角字段标定，使 simulator 发射端与 LapDebug 遥测体感一致。

**Architecture:** 保持现有 replay 运行时架构不变：`SimulatorViewModel` 继续从 `assets/replay/*.json` 读取 `ReplaySession` 并逐帧发射。新增一个最小 RCZ→Replay JSON 转换测试与生成资产，直接替换当前 `tianfu_track_replay_5hz.json`，不引入外部文件选择能力。

**Tech Stack:** Kotlin、JUnit4、Gson、Android Gradle、现有 replay asset loader / playback planner

---

## File Structure

- **Create:** `simulator/src/test/java/com/blazepush/simulator/data/replay/RczLap7ReplayConversionTest.kt`
  - 责任：验证 lap7 RCZ 到 `ReplaySample` 的字段映射、单位换算、圈内时间范围与前后缓冲。
- **Modify:** `simulator/src/main/assets/replay/tianfu_track_replay_5hz.json`
  - 责任：替换为 lap7 RCZ 转换后的 replay 样本。
- **Modify:** `feature/test/src/main/assets/replay/tianfu_track_replay_5hz.json`
  - 责任：与 simulator 保持同一 replay 参考，保证 Track/Gate 对齐。
- **Optional Modify（仅当需要复用转换逻辑时）:** `simulator/src/test/java/com/blazepush/simulator/data/GpsDataGeneratorReplayTest.kt`
  - 责任：保留/补充字段编码断言，确认新资产速度值会被正确发射。

---

### Task 1: 写 RCZ→Replay 映射红测

**Files:**
- Create: `simulator/src/test/java/com/blazepush/simulator/data/replay/RczLap7ReplayConversionTest.kt`
- Test: `simulator/src/test/java/com/blazepush/simulator/data/replay/RczLap7ReplayConversionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.blazepush.simulator.data.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipFile

class RczLap7ReplayConversionTest {

    @Test
    fun `lap7 rcz maps to replay samples with corrected units`() {
        val path = Paths.get("/Users/wattledgnata/Downloads/session_20260314_170249_天府赛道_lap7.rcz")
        ZipFile(path.toFile()).use { zip ->
            val timestamps = zip.readLongs("channel_1_200_0_1_1")
            val coords = zip.readInts("channel_1_200_0_3_1")
            val speeds = zip.readInts("channel_1_200_0_4_0")
            val altitudes = zip.readInts("channel_1_200_0_5_0")
            val bearings = zip.readInts("channel_1_200_0_6_0")
            val satellites = zip.readInts("channel_1_200_0_30002_0")
            val fixTypes = zip.readInts("channel_1_200_0_30003_0")
            val hdops = zip.readInts("channel_1_200_0_30004_0")

            val samples = timestamps.indices.map { index ->
                ReplaySample(
                    timestampMillis = timestamps[index],
                    latitude = coords[index * 2] / 6000000.0,
                    longitude = coords[index * 2 + 1] / 6000000.0,
                    speedKmh = speeds[index] / 1000.0 * 3.6,
                    bearingDegrees = bearings[index] / 1000.0,
                    satellites = satellites[index],
                    fixType = fixTypes[index],
                    hdop = hdops[index] / 1000.0,
                    altitudeMeters = altitudes[index] / 1000.0,
                    altitudePrecisionMeters = 0.0
                )
            }

            assertEquals(2820, samples.size)
            assertEquals(1773478969360L, samples.first().timestampMillis)
            assertEquals(1773479082120L, samples.last().timestampMillis)
            assertEquals(30.49697, samples.first().latitude, 0.0000001)
            assertEquals(104.43317, samples.first().longitude, 0.0000001)
            assertEquals(89.0712, samples.first().speedKmh, 0.0001)
            assertEquals(164.17, samples.first().bearingDegrees, 0.001)
            assertEquals(12, samples.first().satellites)
            assertEquals(1, samples.first().fixType)
            assertEquals(0.6, samples.first().hdop, 0.0001)
            assertEquals(424.5, samples.first().altitudeMeters, 0.0001)
            assertTrue(samples.maxOf { it.speedKmh } > 170.0)
            assertTrue(samples.minOf { it.speedKmh } > 50.0)
        }
    }

    private fun ZipFile.readInts(name: String): List<Int> =
        getInputStream(getEntry(name)).use { input ->
            val bytes = input.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            List(bytes.size / Int.SIZE_BYTES) { buffer.int }
        }

    private fun ZipFile.readLongs(name: String): List<Long> =
        getInputStream(getEntry(name)).use { input ->
            val bytes = input.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            List(bytes.size / Long.SIZE_BYTES) { buffer.long }
        }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :simulator:testDebugUnitTest --tests com.blazepush.simulator.data.replay.RczLap7ReplayConversionTest`
Expected: FAIL，因为测试文件尚不存在。

- [ ] **Step 3: Write minimal implementation**

按上面的完整测试文件原样创建 `RczLap7ReplayConversionTest.kt`，不要先抽公共工具，不要引入生产代码解析器。

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :simulator:testDebugUnitTest --tests com.blazepush.simulator.data.replay.RczLap7ReplayConversionTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add simulator/src/test/java/com/blazepush/simulator/data/replay/RczLap7ReplayConversionTest.kt
git commit -m "test: verify lap7 RCZ replay conversion"
```

---

### Task 2: 替换 simulator 与 test 的 replay 资产

**Files:**
- Modify: `simulator/src/main/assets/replay/tianfu_track_replay_5hz.json`
- Modify: `feature/test/src/main/assets/replay/tianfu_track_replay_5hz.json`
- Test: `simulator/src/test/java/com/blazepush/simulator/data/replay/RczLap7ReplayConversionTest.kt`

- [ ] **Step 1: Generate the replacement JSON asset**

使用与 Task 1 相同映射生成 JSON，结构必须是：

```json
{
  "sessionTitle": "天府赛道 lap7 preview from RCZ",
  "samples": [
    {
      "timestampMillis": 1773478969360,
      "latitude": 30.49697,
      "longitude": 104.43317,
      "speedKmh": 89.0712,
      "bearingDegrees": 164.17,
      "satellites": 12,
      "fixType": 1,
      "hdop": 0.6,
      "altitudeMeters": 424.5,
      "altitudePrecisionMeters": 0.0
    }
  ]
}
```

要求：
- 生成 `2820` 个样本
- 使用 `bearingDegrees = raw / 1000.0`
- 使用 `speedKmh = raw / 1000.0 * 3.6`
- `altitudePrecisionMeters` 固定 `0.0`

- [ ] **Step 2: Replace simulator asset**

将生成好的完整 JSON 覆盖到：

```text
simulator/src/main/assets/replay/tianfu_track_replay_5hz.json
```

- [ ] **Step 3: Replace feature/test asset**

将相同内容覆盖到：

```text
feature/test/src/main/assets/replay/tianfu_track_replay_5hz.json
```

- [ ] **Step 4: Run targeted verification**

Run: `./gradlew :simulator:testDebugUnitTest --tests com.blazepush.simulator.data.replay.RczLap7ReplayConversionTest`
Expected: PASS

Run: `./gradlew :simulator:testDebugUnitTest --tests com.blazepush.simulator.data.GpsDataGeneratorReplayTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add simulator/src/main/assets/replay/tianfu_track_replay_5hz.json feature/test/src/main/assets/replay/tianfu_track_replay_5hz.json
git commit -m "feat: replace replay asset with lap7 RCZ source"
```

---

### Task 3: 构建并进行最小真机验证

**Files:**
- Modify: none
- Test: built APK + device install verification

- [ ] **Step 1: Build simulator APK**

Run: `./gradlew :simulator:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Install to iFlytek device**

Run: `adb -s DP011011255100142 install -r "/Users/wattledgnata/traeProjects/gps-app/.worktrees/track-lap-timing/simulator/build/outputs/apk/debug/simulator-debug.apk"`
Expected: `Success`

- [ ] **Step 3: Manual runtime check**

在讯飞设备上：
- 选择 `真实赛道回放 (天府 5Hz)`
- 开始广播
- 观察 UI 的 `currentSpeed/currentLatitude/currentLongitude`

验收标准：
- 速度不再呈现旧资产的异常低量级
- bearing 不固定在 `45`
- 坐标沿天府赛道变化

- [ ] **Step 4: Optional dual-device evidence capture**

如需复核，抓以下日志：

```bash
adb -s DP011011255100142 logcat -d | grep "Replay frame:"
adb -s 8KE0219522008434 logcat -d | grep -E "GpsDataViewModel|TestSessionVM|LapTimingEngine"
```

Expected:
- simulator 侧 `Replay frame` 的 `speed` 与新资产一致
- app 侧 `gpsData` 的速度、bearing 与 replay 明显同步变化

- [ ] **Step 5: Commit verification note (optional local-only if requested)**

不新增代码。若用户要求本地留痕，再单独记录。

---

## Self-Review

- **Spec coverage:** 本计划覆盖了“闭合当前 replay -> accepted crossing -> lap1 链路”的 replay 源替换范围，没有扩展外部文件选择能力，符合当前用户最新要求。
- **Placeholder scan:** 无 TBD/TODO；所有文件路径、命令、字段映射、缩放公式都已明确。
- **Type consistency:** `ReplaySample` 字段名与现有模型一致；`speedKmh`、`bearingDegrees`、`hdop`、`altitudeMeters` 的单位在所有任务里保持一致。
