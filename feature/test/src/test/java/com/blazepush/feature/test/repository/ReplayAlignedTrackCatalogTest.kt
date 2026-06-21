// @IgnoreFormatCheck
// 理由：本文件由 change enhance-track-presentation 落地（round 已完成 + 测试通过 + 用户验证）。
//       本次 commit 仅作"补归档"，未触及代码内容；hook 报的 class-comment / public-fun-with-comment-block
//       / no-trailing-newline 属于该 round 内未触发的 pre-existing 风格债。
package com.blazepush.feature.test.repository

import com.blazepush.feature.test.model.laptiming.CrossingReason
import com.blazepush.feature.test.model.laptiming.GpsSample
import com.blazepush.feature.test.model.track.TimingGate
import com.blazepush.feature.test.model.track.TrackSource
import com.blazepush.feature.test.usecase.GateCrossingDetector
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayAlignedTrackCatalogTest {

    @Test
    fun buildReplayAlignedTrack_withReplayAssets_doesNotThrow() {
        val catalog = ReplayAlignedTrackCatalog(
            replayTrackSource = object : ReplayTrackSource {
                override fun loadReplayJson(): String = replayJson
                override fun loadTrackVbo(): String = replayVbo
            },
            fallbackCatalog = PresetTrackCatalog()
        )

        val method = ReplayAlignedTrackCatalog::class.java.getDeclaredMethod(
            "buildReplayAlignedTrack",
            String::class.java,
            String::class.java
        )
        method.isAccessible = true

        method.invoke(catalog, replayJson, replayVbo)
    }

    @Test
    fun presetStartFinishGate_acceptsReplayOpeningCrossingSamples() {
        val track = requireNotNull(PresetTrackCatalog().getTrack("preset-tfic-lpcc"))
        val crossing = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L)

        val detection = GateCrossingDetector().detect(
            previous = crossing.first,
            current = crossing.second,
            gate = track.startFinishGate
        )

        assertEquals(true, detection.accepted)
        assertEquals(CrossingReason.Accepted, detection.reason)
    }

    @Test
    fun generatedStartFinishGate_acceptsReplayOpeningCrossingSamples() = runTest {
        val catalog = ReplayAlignedTrackCatalog(
            replayTrackSource = object : ReplayTrackSource {
                override fun loadReplayJson(): String = replayJson
                override fun loadTrackVbo(): String = replayVbo
            },
            fallbackCatalog = PresetTrackCatalog()
        )
        // A37 warm cache：冷 getTrack(TFIC) 走 fallback 不触 asset IO，
        // 必须先 getAllTracks 把 cachedReplayTrack 填充后再 getTrack 才拿 replay-aligned 版
        catalog.getAllTracks()
        val track = requireNotNull(catalog.getTrack("preset-tfic-lpcc"))
        val crossing = crossingSamples(track.startFinishGate, 1773477876490L, 1773477876690L)

        val detection = GateCrossingDetector().detect(
            previous = crossing.first,
            current = crossing.second,
            gate = track.startFinishGate
        )

        assertEquals("accepted=${detection.accepted}, reason=${detection.reason}, score=${detection.directionScore}, gate=${track.startFinishGate}", true, detection.accepted)
        assertEquals(CrossingReason.Accepted, detection.reason)
    }

    @Test
    fun presetTrack_matchesTficRczTrapGeometry() {
        val track = requireNotNull(PresetTrackCatalog().getTrack("preset-tfic-lpcc"))

        assertGateLine(
            gate = track.startFinishGate,
            startLatitude = 30.496167246506413,
            startLongitude = 104.43343794245452,
            endLatitude = 30.49619075349359,
            endLongitude = 104.43291739087881,
            passDirectionX = -0.0002602757878550089,
            passDirectionY = -0.000023506987175358924
        )
        assertGateLine(
            gate = track.sectorGates.first { it.id == "s1" },
            startLatitude = 30.49004451419976,
            startLongitude = 104.43252709154902,
            endLatitude = 30.48959781913357,
            endLongitude = 104.43258157511764,
            passDirectionX = 0.00002724178431097556,
            passDirectionY = 0.00044669506619011374
        )
        assertGateLine(
            gate = track.sectorGates.first { it.id == "s2" },
            startLatitude = 30.4957579139104,
            startLongitude = 104.4369620745035,
            endLatitude = 30.495765752756267,
            endLongitude = 104.43748325882984,
            passDirectionX = 0.0002605921631704301,
            passDirectionY = -0.000007838845867048829
        )
    }

    @Test
    fun generatedTrack_reusesCorrectedTficGateGeometry() = runTest {
        val catalog = ReplayAlignedTrackCatalog(
            replayTrackSource = object : ReplayTrackSource {
                override fun loadReplayJson(): String = replayJson
                override fun loadTrackVbo(): String = replayVbo
            },
            fallbackCatalog = PresetTrackCatalog()
        )

        // A37 warm cache（见 §5.2b）
        catalog.getAllTracks()
        val track = requireNotNull(catalog.getTrack("preset-tfic-lpcc"))

        assertGateLine(
            gate = track.startFinishGate,
            startLatitude = 30.496167246506413,
            startLongitude = 104.43343794245452,
            endLatitude = 30.49619075349359,
            endLongitude = 104.43291739087881,
            passDirectionX = -0.0002602757878550089,
            passDirectionY = -0.000023506987175358924
        )
        assertGateLine(
            gate = track.sectorGates.first { it.id == "s1" },
            startLatitude = 30.49004451419976,
            startLongitude = 104.43252709154902,
            endLatitude = 30.48959781913357,
            endLongitude = 104.43258157511764,
            passDirectionX = 0.00002724178431097556,
            passDirectionY = 0.00044669506619011374
        )
        assertGateLine(
            gate = track.sectorGates.first { it.id == "s2" },
            startLatitude = 30.4957579139104,
            startLongitude = 104.4369620745035,
            endLatitude = 30.495765752756267,
            endLongitude = 104.43748325882984,
            passDirectionX = 0.0002605921631704301,
            passDirectionY = -0.000007838845867048829
        )
    }

    @Test
    fun getTrack_buildsGeneratedTficTrackFromReplayAssets() = runTest {
        val catalog = ReplayAlignedTrackCatalog(
            replayTrackSource = object : ReplayTrackSource {
                override fun loadReplayJson(): String = replayJson
                override fun loadTrackVbo(): String = replayVbo
            },
            fallbackCatalog = PresetTrackCatalog()
        )

        // A37 warm cache（见 §5.2b）
        catalog.getAllTracks()
        val track = requireNotNull(catalog.getTrack("preset-tfic-lpcc"))

        assertEquals(TrackSource.Generated, track.source)
        assertEquals(3.260, track.lengthKm, 1e-6)
        assertEquals("track_thumbnails/chengdu_tianfu.png", track.thumbnailAssetPath)
        assertTrue(track.referencePath.points.size > 100)
        assertEquals("起点", track.startFinishGate.name)
        assertEquals(listOf("s1", "s2"), track.sectorGates.map { it.name })
        assertEquals("start-finish", track.startFinishGate.id)
        assertNullMinDirectionalSpeed(track)
    }

    @Test
    fun getAllTracks_exposesReplayAlignedTrackToRuntimeSelection() = runTest {
        val catalog = ReplayAlignedTrackCatalog(
            replayTrackSource = object : ReplayTrackSource {
                override fun loadReplayJson(): String = replayJson
                override fun loadTrackVbo(): String = replayVbo
            },
            fallbackCatalog = PresetTrackCatalog()
        )

        val track = catalog.getAllTracks().firstOrNull { it.id == "preset-tfic-lpcc" }

        assertNotNull(track)
        assertEquals(TrackSource.Generated, track?.source)
    }

    // ==================== A37 新增测试（change fix-gps-stats-and-lazy-catalog-hot-start）====================

    /**
     * A37 spec R3 Scenario "源码包含 withContext(Dispatchers.IO)"：
     * 锁死 IO 边界的实现位置，不依赖脆弱的线程名字面量断言。
     */
    @Test
    fun getAllTracks_sourceContainsWithContextDispatchersIO() {
        val source = java.io.File(
            projectRoot(),
            "feature/test/src/main/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalog.kt",
        ).readText()
        assertTrue(
            "ReplayAlignedTrackCatalog.getAllTracks 源码必须包含 withContext(Dispatchers.IO)（A37 IO 边界唯一防线）",
            source.contains("withContext(Dispatchers.IO)"),
        )
    }

    /**
     * A37 spec R3 Scenario "首次调用 asset 读取切出调用方 / Main / Test 线程"：
     * runtime 断言线程切换发生，不做线程名字面量匹配。
     */
    @Test
    fun getAllTracks_doesNotBlockCallerThread_firstCallExecutesOffCallerThread() = runTest {
        val callThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val fakeSource = object : ReplayTrackSource {
            override fun loadReplayJson(): String {
                callThread.compareAndSet(null, Thread.currentThread())
                return replayJson
            }
            override fun loadTrackVbo(): String = replayVbo
        }
        val catalog = ReplayAlignedTrackCatalog(fakeSource, PresetTrackCatalog())

        val callerThread = Thread.currentThread()
        catalog.getAllTracks()

        val captured = callThread.get()
        assertNotNull("asset 读取应真实发生（fake.loadReplayJson 被调）", captured)
        assertTrue(
            "asset 读取应切出调用方线程（withContext(Dispatchers.IO) 生效），caller=${callerThread.name}, asset=${captured?.name}",
            captured !== callerThread,
        )
    }

    /**
     * A37 spec R4 Scenario "冷缓存 getTrack(TFIC) 不触发 asset 读"：
     * 核心契约——没有 getAllTracks 前提的 getTrack(TFIC) 必须 0 IO + fallback 降级。
     */
    @Test
    fun getTrack_whenCacheCold_returnsFallbackWithoutAssetIo() {
        var loadReplayCount = 0
        var loadVboCount = 0
        val fakeSource = object : ReplayTrackSource {
            override fun loadReplayJson(): String {
                loadReplayCount++
                return replayJson
            }
            override fun loadTrackVbo(): String {
                loadVboCount++
                return replayVbo
            }
        }
        val catalog = ReplayAlignedTrackCatalog(fakeSource, PresetTrackCatalog())

        val track = catalog.getTrack("preset-tfic-lpcc")

        assertNotNull("冷缓存应降级到 fallback，不返回 null", track)
        assertEquals(
            "冷 getTrack(TFIC) 应返回 PresetTrackCatalog 版（TrackSource.Preset），不是 replay-aligned",
            TrackSource.Preset,
            track?.source,
        )
        assertEquals("loadReplayJson 不应被调用（冷缓存不触 IO）", 0, loadReplayCount)
        assertEquals("loadTrackVbo 不应被调用（冷缓存不触 IO）", 0, loadVboCount)
    }

    /**
     * A37 spec R4 Scenario "热缓存 getTrack(TFIC) 返回 replay-aligned 版"。
     */
    @Test
    fun getTrack_whenCacheWarm_returnsReplayAlignedTrack() = runTest {
        var loadReplayCount = 0
        val fakeSource = object : ReplayTrackSource {
            override fun loadReplayJson(): String {
                loadReplayCount++
                return replayJson
            }
            override fun loadTrackVbo(): String = replayVbo
        }
        val catalog = ReplayAlignedTrackCatalog(fakeSource, PresetTrackCatalog())

        // warm cache
        catalog.getAllTracks()
        val countAfterWarm = loadReplayCount

        val track = catalog.getTrack("preset-tfic-lpcc")

        assertEquals("热缓存命中应返回 replay-aligned 版", TrackSource.Generated, track?.source)
        assertEquals("热缓存命中沿用 preset 长度，不重算", 3.260, track?.lengthKm ?: 0.0, 1e-6)
        assertEquals("热缓存命中沿用 preset thumbnail", "track_thumbnails/chengdu_tianfu.png", track?.thumbnailAssetPath)
        assertEquals(
            "热 getTrack 不再触发 asset IO（仅首次 getAllTracks 触发一次）",
            countAfterWarm,
            loadReplayCount,
        )
    }

    /**
     * A37 spec R4 Scenario "冷缓存 getTrack(非 TFIC) 走 fallback 路径不读 asset"。
     */
    @Test
    fun getTrack_whenNonTficAndCold_doesNotTouchReplayAsset() {
        var loadReplayCount = 0
        val fakeSource = object : ReplayTrackSource {
            override fun loadReplayJson(): String {
                loadReplayCount++
                return replayJson
            }
            override fun loadTrackVbo(): String = replayVbo
        }
        val catalog = ReplayAlignedTrackCatalog(fakeSource, PresetTrackCatalog())

        catalog.getTrack("some-other-track-id")

        assertEquals("非 TFIC 路径不触 replay asset", 0, loadReplayCount)
    }

    /**
     * A37 spec R3 Scenario "缓存命中 asset 不重复读"。
     */
    @Test
    fun getAllTracks_cacheHit_doesNotRereadAssets() = runTest {
        var loadReplayCount = 0
        var loadVboCount = 0
        val fakeSource = object : ReplayTrackSource {
            override fun loadReplayJson(): String {
                loadReplayCount++
                return replayJson
            }
            override fun loadTrackVbo(): String {
                loadVboCount++
                return replayVbo
            }
        }
        val catalog = ReplayAlignedTrackCatalog(fakeSource, PresetTrackCatalog())

        catalog.getAllTracks()
        val firstReplayCount = loadReplayCount
        val firstVboCount = loadVboCount
        catalog.getAllTracks()

        assertEquals("缓存命中 loadReplayJson 不应再次调用", firstReplayCount, loadReplayCount)
        assertEquals("缓存命中 loadTrackVbo 不应再次调用", firstVboCount, loadVboCount)
    }

    /**
     * A37 spec R5 Scenario "类字段级 by lazy 被显式缓存替代"：源码断言。
     */
    @Test
    fun replayAlignedTrackCatalog_sourceHasNoByLazy() {
        val source = java.io.File(
            projectRoot(),
            "feature/test/src/main/java/com/blazepush/feature/test/repository/ReplayAlignedTrackCatalog.kt",
        ).readText()
        assertFalse(
            "ReplayAlignedTrackCatalog 不应使用 by lazy（A37 改为显式 @Volatile + synchronized 双检锁）",
            source.contains("by lazy"),
        )
        assertFalse(
            "禁止使用 LazyThreadSafetyMode.NONE / lazy(NONE)（spec R6）",
            source.contains("LazyThreadSafetyMode.NONE") || source.contains("lazy(NONE)"),
        )
    }

    private fun assertNullMinDirectionalSpeed(track: com.blazepush.feature.test.model.track.Track) {
        assertEquals(null, track.startFinishGate.minDirectionalSpeedMps)
        assertTrue(track.sectorGates.all { it.minDirectionalSpeedMps == null })
    }

    private fun assertGateLine(
        gate: TimingGate,
        startLatitude: Double,
        startLongitude: Double,
        endLatitude: Double,
        endLongitude: Double,
        passDirectionX: Double,
        passDirectionY: Double
    ) {
        assertEquals(startLatitude, gate.line.start.latitude, 0.0000000001)
        assertEquals(startLongitude, gate.line.start.longitude, 0.0000000001)
        assertEquals(endLatitude, gate.line.end.latitude, 0.0000000001)
        assertEquals(endLongitude, gate.line.end.longitude, 0.0000000001)
        assertEquals(passDirectionX, gate.passDirection.x, 0.0000000001)
        assertEquals(passDirectionY, gate.passDirection.y, 0.0000000001)
    }

    private fun crossingSamples(gate: TimingGate, previousTimestamp: Long, currentTimestamp: Long): Pair<GpsSample, GpsSample> {
        val centerLatitude = (gate.line.start.latitude + gate.line.end.latitude) / 2.0
        val centerLongitude = (gate.line.start.longitude + gate.line.end.longitude) / 2.0
        val offsetScale = 0.25
        return GpsSample(
            timestampMillis = previousTimestamp,
            latitude = centerLatitude - (gate.passDirection.x * offsetScale),
            longitude = centerLongitude - (gate.passDirection.y * offsetScale),
            speedKmh = 36.0
        ) to GpsSample(
            timestampMillis = currentTimestamp,
            latitude = centerLatitude + (gate.passDirection.x * offsetScale),
            longitude = centerLongitude + (gate.passDirection.y * offsetScale),
            speedKmh = 36.0
        )
    }

    private val replayJson = java.io.File(
        projectRoot(),
        "feature/test/src/main/assets/replay/tianfu_track_replay_5hz.json"
    ).readText()

    private val replayVbo = java.io.File(
        projectRoot(),
        "feature/test/src/main/assets/replay/tianfu_track.vbo"
    ).readText()

    private fun projectRoot(): java.io.File {
        val classesDir = java.io.File(javaClass.protectionDomain.codeSource.location.toURI())
        val userDir = java.io.File(System.getProperty("user.dir"))
        return sequenceOf(classesDir, userDir)
            .flatMap { start -> generateSequence(start) { current -> current.parentFile }.filterNotNull() }
            .first { java.io.File(it, "settings.gradle").exists() || java.io.File(it, "settings.gradle.kts").exists() }
    }
}
