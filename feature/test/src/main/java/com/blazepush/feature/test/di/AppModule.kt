// @IgnoreFormatCheck
package com.blazepush.feature.test.di

import androidx.room.Room
import com.blazepush.core.bluetooth.BleDeviceManager
import com.blazepush.feature.test.FileLogger
import com.blazepush.feature.test.datastore.RecentTracksStore
import com.blazepush.feature.test.datastore.RecentTracksStoreApi
import com.blazepush.feature.test.recording.CameraRecordingEngine
import com.blazepush.feature.test.recording.RecordingCapabilityDetector
import com.blazepush.feature.test.datastore.RecordingPreferencesRepository
import com.blazepush.feature.test.datastore.UserProfileRepository
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
import com.blazepush.core.network.LivetimingUploader
import com.blazepush.feature.test.livetiming.LapUploadOrchestrator
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
        // session-video-metadata-persist round（2026-05-30）：
        // - migrationChain 包含 migration2To3 + migration3To4 + migration4To5 + migration5To6，严格覆盖 v2→v6
        // - 保留 destructiveMigrationFrom(1) 兜底 pre-A56 开发期 v1 schema（旧包名 com.race.gps.*，无 release tag 用户）
        // - v2→v6 全程由 migrationChain 严格覆盖，fallbackFrom 列表不含 2-6
        // - 移除无参 fallbackToDestructiveMigration()，防止 missing migration 静默清空用户数据
        //
        // 注意：MUST NOT 用 fallbackToDestructiveMigrationFrom(... 4) —— Room 检测
        // migration3To4.endVersion=4 与 fallbackFrom 列表 4 冲突，build() 时抛
        // IllegalArgumentException "Inconsistency detected"（已踩坑 2026-05-03）。
        // 同理 MUST NOT 在 fallbackFrom 列表里含 2、3、4、5、6 —— 对应 migrationChain 严格覆盖范围。
        // （P2 修正：restore round 写 `fallbackToDestructiveMigrationFrom(1, 2)`，
        //   2 是冗余——migration2To3 已提供完整 v2→v3 路径，Room 优先找迁移路径，
        //   fallback 列表中的 2 永不触发且与注释自相矛盾。已改为 `(1)` 自洽。）
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            "race_chrono_database"
        )
            .addMigrations(*AppDatabase.migrationChain.toTypedArray())
            .fallbackToDestructiveMigrationFrom(1)
            .build()
    }

    single { get<AppDatabase>().testRecordDao() }
    single { get<AppDatabase>().carModelDao() }
    single { get<AppDatabase>().bluetoothDeviceDao() }
    single { get<AppDatabase>().speedSegmentDao() }
    single { get<AppDatabase>().telemetrySessionDao() }
    single { get<AppDatabase>().crossingEventDao() }
    single { get<AppDatabase>().pendingLapUploadDao() }
}

/**
 * Bluetooth模块（替代原有的serviceModule）
 */
val bluetoothModule = module {
    single { RaceChronoParser() }
    single { BluetoothDataSource(androidContext(), get()) }
    // ble-device-memory round（design Decision 1）：闭包注入设备记忆能力，core/bluetooth 不依赖
    // core/data（模块图不动）。single lambda 体在 BleDeviceManager 首次被注入时才执行（Koin 惰性），
    // 彼时 repositoryModule 已注册完毕，get<BluetoothDeviceRepository>() 不会 MissingDefinition。
    single {
        val deviceRepository = get<BluetoothDeviceRepository>()
        BleDeviceManager(
            androidContext(),
            get(),
            lastDeviceProvider = {
                val last = deviceRepository.getLastConnectedDevice()
                FileLogger.d("BleDeviceMemory", "cold-start target=${last?.address ?: "none"}")
                last?.address
            },
            onDeviceConnected = { address, name ->
                val firstTime = deviceRepository.getSavedDevices().none { it.address == address }
                deviceRepository.recordConnected(address, name, System.currentTimeMillis())
                FileLogger.d("BleDeviceMemory", "persisted addr=$address name=$name firstTime=$firstTime")
            },
        )
    }
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
    // driver-display-name round：车手显示名（livetiming lap-upload driver 本地前置）
    single { UserProfileRepository(androidContext()) }
    // round `replace-nearby-tracks-with-recent-strip` §2.2：接口为 key、生产 RecentTracksStore 实例为 value
    single<RecentTracksStoreApi> { RecentTracksStore(androidContext()) }
    // livetiming-lap-upload round：上报门面（token 走 BuildConfig）+ 出圈上报编排
    single<com.blazepush.core.network.LapUploadApi> { LivetimingUploader.create() }
    single<com.blazepush.feature.test.livetiming.LapUploadTrigger> { LapUploadOrchestrator(get(), get(), get()) }
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
 * 录制模块（camera-recording-and-gps-sync round）
 */
val recordingModule = module {
    // CameraRecordingEngine 为 single（同一 LapLiveScreen 实例共享引擎状态）
    single { CameraRecordingEngine(get()) }
    // recording-params-config-screen round：录制参数持久化 + 设备能力探测
    single { RecordingPreferencesRepository(androidContext()) }
    single { RecordingCapabilityDetector() }
}

/**
 * ViewModel模块
 */
val viewModelModule = module {
    // GpsDataViewModel作为单例，所有页面共享同一个数据流
    // ble-device-memory：第 4 参 BluetoothDeviceRepository（设备记忆）
    single { GpsDataViewModel(get(), get(), get(), get()) }
    viewModel { TestSessionViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { TestHistoryViewModel(get()) }
}

/**
 * 工具模块
 */
val utilsModule = module {
    single { VoiceAnnouncer(androidContext()) }
}