package com.blazepush

import android.app.Application
import com.blazepush.feature.test.di.bluetoothModule
import com.blazepush.feature.test.di.databaseModule
import com.blazepush.feature.test.di.domainModule
import com.blazepush.feature.test.di.repositoryModule
import com.blazepush.feature.test.di.utilsModule
import com.blazepush.feature.test.di.viewModelModule
import com.blazepush.feature.test.FileLogger
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class BlazePushApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 初始化文件日志
        FileLogger.init(this)

        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@BlazePushApplication)
            modules(
                databaseModule,
                bluetoothModule,
                repositoryModule,
                domainModule,
                viewModelModule,
                utilsModule
            )
        }
    }
}
