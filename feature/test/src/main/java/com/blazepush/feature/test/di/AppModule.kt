package com.blazepush.feature.test.di

import androidx.room.Room
import com.blazepush.core.bluetooth.BleDeviceManager
import com.blazepush.feature.test.datastore.RecentTracksStore
import com.blazepush.feature.test.datastore.RecentTracksStoreApi
import com.blazepush.core.bluetooth.BluetoothDataSource
import com.blazepush.core.bluetooth.GpsDataRepository
import com.blazepush.core.bluetooth.parser.RaceChronoParser
import com.blazepush.core.data.local.AppDatabase
import com.blazepush.core.data.repository.BluetoothDeviceRepository
import com.blazepush.core.data.repository.CarModelRepository
import com.blazepush.core.data.repository.TelemetryRepository
import com.blazepush.core.data.repository.TestResultRepository
import com.blazepush.core.domain.usecase.CalculateResultUseCase
import com.blazepush.core.domain.usecase.DataQualityEvaluator
import com.blazepush.core.domain.usecase.GpsDataFilter
import com.blazepush.core.domain.usecase.SmartTestLauncher
import com.blazepush.feature.test.repository.AssetReplayTrackSource
import com.blazepush.feature.test.repository.PresetTrackCatalog
import com.blazepush.feature.test.repository.ReplayAlignedTrackCatalog
import com.blazepush.feature.test.repository.ReplayTrackSource
import com.blazepush.feature.test.repository.TrackCatalog
import com.blazepush.feature.test.usecase.GateCrossingDetector
import com.blazepush.feature.test.usecase.LapTimingEngine
import com.blazepush.feature.test.utils.VoiceAnnouncer
import com.blazepush.feature.test.viewmodel.GpsDataViewModel
import com.blazepush.feature.test.viewmodel.TestHistoryViewModel
import com.blazepush.feature.test.viewmodel.TestSessionViewModel
import org.koin.android.error.MissingAndroidContextException
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * 数据库模块
 */
val databaseModule = module {
    single {
        // persist-session-summary-fields round（schema v3 → v4）：
        // - addMigrations(migration3To4) 注册 v3→v4 严格 migration（保护现役数据）
        // - 全局 destructive fallback 已移除（防 v3→v4 失败时静默清库）
        // - 路径 A 选定：窄范围 destructiveMigrationFrom(1, 2) 兜底 pre-A56 开发期 schema
        //   （schema 历史确认：dc0c011 多模块重构创建 v2，d15a60c A56 升 v3，未见 v1；
        //    v2 是 pre-A56 开发期 schema，对它 destructive 可接受 — 不存在 release tag 用户）
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            "race_chrono_database"
        )
            .addMigrations(AppDatabase.migration3To4)
            // smooth-perftest-acceleration-curve round：test_records 加 maxDeceleration 列触发
            // schema v4 → v5。debug 阶段不写 strict migration，无参 fallbackToDestructiveMigration()
            // 兜底所有 missing migration（含 v1/v2/v4 → v5）。strict migration（migration3To4）仍优先生效，
            // missing migration 才走 destructive 重建表。
            //
            // 注意：MUST NOT 用 fallbackToDestructiveMigrationFrom(... 4) —— Room 检测
            // migration3To4.endVersion=4 与 fallbackFrom 列表 4 冲突，build() 时抛
            // IllegalArgumentException "Inconsistency detected"（已踩坑 2026-05-03）。
            //
            // 影响：装新包时存量 V1 测试记录会被清空（destructive 重建 test_records 表）。
            // 上线前 follow-up：补回严格 migration（见 backlog §8.9 restore-strict-migrations-pre-release）。
            .fallbackToDestructiveMigration()
            .build()
    }

    single { get<AppDatabase>().testRecordDao() }
    single { get<AppDatabase>().carModelDao() }
    single { get<AppDatabase>().bluetoothDeviceDao() }
    single { get<AppDatabase>().speedSegmentDao() }
    single { get<AppDatabase>().telemetrySessionDao() }
    single { get<AppDatabase>().crossingEventDao() }
}

/**
 * Bluetooth模块（替代原有的serviceModule）
 */
val bluetoothModule = module {
    single { RaceChronoParser() }
    single { BluetoothDataSource(androidContext(), get()) }
    single { BleDeviceManager(androidContext(), get()) }
}

/**
 * Repository模块
 */
val repositoryModule = module {
    single { GpsDataRepository(get()) }
    single { TestResultRepository(get(), get(), get()) }
    single { CarModelRepository(get()) }
    single { BluetoothDeviceRepository(get()) }
    single { TelemetryRepository(androidContext(), get(), get()) }
    // round `replace-nearby-tracks-with-recent-strip` §2.2：接口为 key、生产 RecentTracksStore 实例为 value
    single<RecentTracksStoreApi> { RecentTracksStore(androidContext()) }
}

/**
 * Domain模块
 */
val domainModule = module {
    factory { CalculateResultUseCase() }
    factory { SmartTestLauncher() }
    factory { GpsDataFilter() }
    factory { DataQualityEvaluator() }
    factory { GateCrossingDetector() }
    factory { LapTimingEngine(get()) }
    single<ReplayTrackSource> { AssetReplayTrackSource(androidContext()) }
    // change fix-di-fallback-and-anomaly-island-cleanup（A17）：
    // single<TrackCatalog> 通过 cause chain 检查决定是否降级。Koin 把 androidContext()
    // 在 JVM 缺 Context 时抛的 org.koin.android.error.MissingAndroidContextException 包成
    // InstanceCreationException 透传给本 provider 的 caller —— 直接 catch 自带类型不命中，
    // 必须遍历 e.cause 链查标记类型。命中（JVM 单测合法 fallback）降级到 PresetTrackCatalog；
    // 不命中（IOException / JsonSyntaxException / asset 损坏等真机异常）throw e 上抛
    // 让崩溃上报可见。
    single<TrackCatalog> {
        try {
            ReplayAlignedTrackCatalog(get(), PresetTrackCatalog())
        } catch (e: Throwable) {
            if (e.findInCauseChain<MissingAndroidContextException>() != null) {
                PresetTrackCatalog()
            } else {
                throw e
            }
        }
    }
}

/**
 * A17：cause chain 遍历工具 —— 配合 Koin 包装的 InstanceCreationException 找标记类型。
 */
private inline fun <reified T : Throwable> Throwable.findInCauseChain(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current as T
        current = current.cause
    }
    return null
}

/**
 * ViewModel模块
 */
val viewModelModule = module {
    // GpsDataViewModel作为单例，所有页面共享同一个数据流
    single { GpsDataViewModel(get(), get(), get()) }
    viewModel { TestSessionViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { TestHistoryViewModel(get()) }
}

/**
 * 工具模块
 */
val utilsModule = module {
    single { VoiceAnnouncer(androidContext()) }
}