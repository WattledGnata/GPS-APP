package com.blazepush

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.blazepush.core.data.repository.IncompleteLapSessionRecoveryCoordinator
import com.blazepush.core.data.repository.TelemetryRepository
import com.blazepush.feature.test.di.bluetoothModule
import com.blazepush.feature.test.di.databaseModule
import com.blazepush.feature.test.di.domainModule
import com.blazepush.feature.test.di.recordingModule
import com.blazepush.feature.test.di.repositoryModule
import com.blazepush.feature.test.di.utilsModule
import com.blazepush.feature.test.di.viewModelModule
import com.blazepush.feature.test.FileLogger
import com.blazepush.feature.test.livetiming.LapUploadTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.dsl.module

class BlazePushApplication : Application() {

    // 冷启动恢复 cutoff：只处理本进程创建前遗留的占位 session，避免异步任务误收尾新计时。
    private val processStartedAtMs = System.currentTimeMillis()

    // Application 级后台任务 scope（进程生命周期，无需取消）
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            flushPendingLaps("network available")
        }
    }

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
                module {
                    single {
                        IncompleteLapSessionRecoveryCoordinator(
                            telemetryRepository = get(),
                            processStartedAtMs = processStartedAtMs,
                        )
                    }
                },
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

        recoverIncompleteLapSessions()

        // Livetiming 待传队列不能依赖“下一次出圈”才恢复。进程启动立即尝试一次；
        // 默认网络重新可用时再触发一次。LapUploadOrchestrator 内部互斥保证并发信号不会重复上传。
        flushPendingLaps("app startup")
        registerLivetimingNetworkRecovery()
    }

    private fun registerLivetimingNetworkRecovery() {
        try {
            val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            manager.registerDefaultNetworkCallback(networkCallback)
            connectivityManager = manager
            networkCallbackRegistered = true
        } catch (t: Throwable) {
            FileLogger.e("Livetiming", "register network recovery failed", t)
        }
    }

    private fun recoverIncompleteLapSessions() {
        appScope.launch {
            try {
                // 先把“已有文件、Finalize/绑定未落库”的视频按文件名恢复到 Session，
                // 再收尾 incomplete session，让 endTs 派生能看到恢复后的视频证据。
                val recoveredVideos = GlobalContext.get().get<TelemetryRepository>()
                    .recoverSessionVideoFiles()
                FileLogger.d("VideoRecovery", "startup recoveredVideos=$recoveredVideos")
                val report = GlobalContext.get().get<IncompleteLapSessionRecoveryCoordinator>()
                    .recover()
                FileLogger.d(
                    "LapRecovery",
                    "startup recovery candidates=${report.candidates} " +
                        "recovered=${report.recovered.size} failed=${report.failed.size}",
                )
                report.recovered.forEach { item ->
                    FileLogger.d(
                        "LapRecovery",
                        "recovered sidSuffix=${item.sessionId.takeLast(8)} laps=${item.lapCount} " +
                            "bestMs=${item.bestLapMs} videoSegments=${item.videoSegmentCount}",
                    )
                }
                report.failed.forEach { item ->
                    FileLogger.e(
                        "LapRecovery",
                        "recovery failed sidSuffix=${item.sessionId.takeLast(8)} type=${item.errorType}",
                    )
                }
            } catch (t: Throwable) {
                FileLogger.e("LapRecovery", "startup recovery failed", t)
            }
        }
    }

    private fun flushPendingLaps(reason: String) {
        appScope.launch {
            try {
                GlobalContext.get().get<LapUploadTrigger>().flush()
                FileLogger.d("Livetiming", "pending flush completed reason=$reason")
            } catch (t: Throwable) {
                // 启动和网络回调都是旁路恢复信号；失败必须保留队列且不得影响 App。
                FileLogger.e("Livetiming", "pending flush failed reason=$reason", t)
            }
        }
    }

    override fun onTerminate() {
        if (networkCallbackRegistered) {
            runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        }
        appScope.cancel()
        super.onTerminate()
    }
}
