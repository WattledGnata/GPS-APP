package com.blazepush.feature.test.di

import androidx.room.Room
import com.blazepush.core.bluetooth.BluetoothDataSource
import com.blazepush.core.bluetooth.BleDeviceManager
import com.blazepush.core.bluetooth.GpsDataRepository
import com.blazepush.core.bluetooth.parser.RaceChronoParser
import com.blazepush.core.data.local.AppDatabase
import com.blazepush.core.data.local.file.TestDataFileStorage
import com.blazepush.core.data.repository.BluetoothDeviceRepository
import com.blazepush.core.data.repository.CarModelRepository
import com.blazepush.core.data.repository.TestResultRepository
import com.blazepush.core.domain.usecase.AnomalyDetector
import com.blazepush.core.domain.usecase.CalculateResultUseCase
import com.blazepush.core.domain.usecase.DataInterpolator
import com.blazepush.core.domain.usecase.DataQualityEvaluator
import com.blazepush.core.domain.usecase.DataSmoothing
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
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * 数据库模块
 */
val databaseModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            "race_chrono_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    single { get<AppDatabase>().testRecordDao() }
    single { get<AppDatabase>().carModelDao() }
    single { get<AppDatabase>().bluetoothDeviceDao() }
    single { get<AppDatabase>().speedSegmentDao() }
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
    single { TestDataFileStorage(androidContext()) }
}

/**
 * Domain模块
 */
val domainModule = module {
    factory { CalculateResultUseCase() }
    factory { SmartTestLauncher() }
    factory { GpsDataFilter() }
    factory { DataQualityEvaluator() }
    factory { AnomalyDetector() }
    factory { DataSmoothing() }
    factory { DataInterpolator() }
    factory { GateCrossingDetector() }
    factory { LapTimingEngine(get()) }
    single<ReplayTrackSource> { AssetReplayTrackSource(androidContext()) }
    single<TrackCatalog> { ReplayAlignedTrackCatalog(get(), PresetTrackCatalog()) }
}

/**
 * ViewModel模块
 */
val viewModelModule = module {
    // GpsDataViewModel作为单例，所有页面共享同一个数据流
    single { GpsDataViewModel(get(), get(), get(), get()) }
    viewModel { TestSessionViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { TestHistoryViewModel(get()) }
}

/**
 * 工具模块
 */
val utilsModule = module {
    single { VoiceAnnouncer(androidContext()) }
}
