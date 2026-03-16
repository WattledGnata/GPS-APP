package com.race.gps.di

import androidx.room.Room
import com.race.gps.bluetooth.BluetoothDataSource
import com.race.gps.data.local.AppDatabase
import com.race.gps.data.local.file.TestDataFileStorage
import com.race.gps.data.local.migration.SharedPreferencesDataMigration
import com.race.gps.data.repository.BluetoothDeviceRepository
import com.race.gps.data.repository.CarModelRepository
import com.race.gps.data.repository.GpsDataRepository
import com.race.gps.data.repository.TestResultRepository
import com.race.gps.data.service.parser.RaceChronoParser
import com.race.gps.domain.usecase.CalculateResultUseCase
import com.race.gps.viewmodel.BluetoothDeviceViewModel
import com.race.gps.viewmodel.GpsDataViewModel
import com.race.gps.viewmodel.TestHistoryViewModel
import com.race.gps.viewmodel.TestSessionViewModel
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
            .addMigrations(AppDatabase.MIGRATION_1_2)
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
}

/**
 * ViewModel模块
 */
val viewModelModule = module {
    // GpsDataViewModel作为单例，所有页面共享同一个数据流
    single { GpsDataViewModel(get()) }
    viewModel { TestSessionViewModel(get(), get(), get()) }
    viewModel { TestHistoryViewModel(get()) }
    viewModel { BluetoothDeviceViewModel(get()) }
}

/**
 * 数据迁移模块
 */
val migrationModule = module {
    single { SharedPreferencesDataMigration(androidContext(), get()) }
}
