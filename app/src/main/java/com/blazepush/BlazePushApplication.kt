package com.blazepush

import android.app.Application
import com.blazepush.core.data.repository.TelemetryRepository
import com.blazepush.feature.test.di.bluetoothModule
import com.blazepush.feature.test.di.databaseModule
import com.blazepush.feature.test.di.domainModule
import com.blazepush.feature.test.di.recordingModule
import com.blazepush.feature.test.di.repositoryModule
import com.blazepush.feature.test.di.utilsModule
import com.blazepush.feature.test.di.viewModelModule
import com.blazepush.feature.test.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class BlazePushApplication : Application() {

    // Application 级后台任务 scope（进程生命周期，无需取消）
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                utilsModule,
                // camera-recording-and-gps-sync round：录制引擎 single 注册
                recordingModule,
            )
        }

        // cleanup-perftest-telemetry-session-orphan round：存量 PERFORMANCE_TEST 孤儿行
        // 一次性 sweep（幂等，cascade 修复后理论恒 0）。失败不得影响 app 启动。
        appScope.launch {
            try {
                val removed = GlobalContext.get().get<TelemetryRepository>().cleanupPerftestOrphans()
                FileLogger.d("PerftestCascade", "startup sweep removed=$removed")
            } catch (t: Throwable) {
                FileLogger.e("PerftestCascade", "startup sweep failed", t)
            }
        }
    }
}
