package com.race.gps

import android.app.Application
import com.race.gps.data.local.migration.SharedPreferencesDataMigration
import com.race.gps.di.bluetoothModule
import com.race.gps.di.databaseModule
import com.race.gps.di.domainModule
import com.race.gps.di.migrationModule
import com.race.gps.di.repositoryModule
import com.race.gps.di.viewModelModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class RaceChronoApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@RaceChronoApplication)
            modules(
                databaseModule,
                bluetoothModule,
                repositoryModule,
                domainModule,
                viewModelModule,
                migrationModule
            )
        }

        // 执行数据迁移
        applicationScope.launch {
            val migration: SharedPreferencesDataMigration by inject()
            migration.migrateIfNeeded()
        }
    }
}
