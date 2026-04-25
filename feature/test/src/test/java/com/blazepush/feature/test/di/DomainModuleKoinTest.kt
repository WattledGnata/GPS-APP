package com.blazepush.feature.test.di

import com.blazepush.core.domain.usecase.GpsDataFilter
import com.blazepush.feature.test.repository.PresetTrackCatalog
import com.blazepush.feature.test.repository.ReplayTrackSource
import com.blazepush.feature.test.repository.TrackCatalog
import java.io.IOException
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent.get

class DomainModuleKoinTest {

    @Test
    fun domainModule_providesGpsDataFilter() {
        stopKoin()
        startKoin {
            modules(domainModule)
        }

        try {
            val filter = get<GpsDataFilter>(GpsDataFilter::class.java)
            assertNotNull(filter)
        } finally {
            stopKoin()
        }
    }

    @Test
    fun domainModule_providesTrackCatalog() {
        stopKoin()
        startKoin {
            modules(domainModule)
        }

        try {
            val trackCatalog = get<TrackCatalog>(TrackCatalog::class.java)
            assertNotNull(trackCatalog)
        } finally {
            stopKoin()
        }
    }

    /**
     * A17 测试 1：JVM 单测环境降级到 PresetTrackCatalog（明确化现有隐式契约）
     *
     * cause chain 命中 Koin 自带 `org.koin.android.error.MissingAndroidContextException`
     * 标记 → AppModule single<TrackCatalog> 走 fallback 路径返回 PresetTrackCatalog
     */
    @Test
    fun providesTrackCatalog_jvmEnvironment_fallsBackToPresetGracefully() {
        stopKoin()
        startKoin { modules(domainModule) }

        try {
            val trackCatalog = get<TrackCatalog>(TrackCatalog::class.java)
            assertTrue(
                "JVM 环境应降级到 PresetTrackCatalog（不是 ReplayAlignedTrackCatalog）；" +
                    "实际类型 ${trackCatalog::class.simpleName}",
                trackCatalog is PresetTrackCatalog,
            )
        } finally {
            stopKoin()
        }
    }

    /**
     * A17 测试 2：真机异常传播（关键 P1 契约 —— Round 4 review 核心目标）
     *
     * fake `single<ReplayTrackSource>` provider 在 **DI 实例化期** 直接抛 `IOException`
     * （模拟真机 DI bootstrapping 期罕见异常，如 asset 损坏 / Koin 类加载失败 / 等等）。
     *
     * 行为契约：
     * - Koin 把 fake provider 抛的 IOException 包装为 `InstanceCreationException(cause = IOException)`
     * - `single<TrackCatalog>` 内 `get<ReplayTrackSource>()` 触发时拿到包装异常
     * - cause chain 中无 Koin `MissingAndroidContextException` 标记 → catch block 走 `throw e` 路径
     * - `get<TrackCatalog>()` 抛出（不静默降级到 PresetTrackCatalog），cause chain 含原始 IOException
     *
     * 注：本测试**不**用 `ReplayAlignedTrackCatalog.getAllTracks()` 触发异常路径，因为
     * A37 design D5 已固化 `ensureReplayTrackLoaded()` 用 `runCatching {}.getOrNull()` 容错，
     * `getAllTracks()` 内 IOException 被吞掉降级到 fallback，永远不会传到 DI 层 catch。
     * A17 关心的是 **DI 层 catch 范围**，所以异常必须在 DI 实例化期发生。
     */
    @Test
    fun providesTrackCatalog_realDeviceAssetFailure_propagatesNotSilenced() {
        stopKoin()
        val fakeIoFailureModule = module {
            single<ReplayTrackSource> {
                throw IOException("simulated DI-layer asset failure")
            }
        }
        startKoin { modules(domainModule, fakeIoFailureModule) }

        try {
            val thrown = assertThrows(Throwable::class.java) {
                get<TrackCatalog>(TrackCatalog::class.java)
            }

            // cause chain 应含原始 IOException（被 Koin 包装但 cause 链可达）
            var current: Throwable? = thrown
            var foundIo = false
            while (current != null) {
                if (current is IOException &&
                    current.message == "simulated DI-layer asset failure") {
                    foundIo = true
                    break
                }
                current = current.cause
            }
            assertTrue(
                "DI 层异常应原样上抛（cause chain 含原始 IOException），未被 single<TrackCatalog> 的 catch " +
                    "静默降级到 PresetTrackCatalog；实际 thrown=${thrown::class.qualifiedName}",
                foundIo,
            )
        } finally {
            stopKoin()
        }
    }
}
