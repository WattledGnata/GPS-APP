package com.race.gps

import android.app.Application
import com.race.gps.di.bluetoothModule
import com.race.gps.di.databaseModule
import com.race.gps.di.domainModule
import com.race.gps.di.repositoryModule
import com.race.gps.di.viewModelModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class RaceChronoApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 初始化文件日志
        FileLogger.init(this)

        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@RaceChronoApplication)
            modules(
                databaseModule,
                bluetoothModule,
                repositoryModule,
                domainModule,
                viewModelModule
            )
        }
    }
}
