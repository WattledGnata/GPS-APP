package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.blazepush.feature.test.viewmodel.TestSessionViewModel
import com.blazepush.feature.test.ui.settings.SettingsScreen
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * Track Tech V2 App Shell：4 tab persistent bottom navigation 顶层容器。
 *
 * 4 个 tab（Test / Laps / Records / Device）通过 [HorizontalPager] 同时驻留并支持
 * 左右滑动切换；NavHost 仅承载 home → 子页（test_execution / gps_details）跳转。
 */
object TabIndex {
    const val Test = 0
    const val Laps = 1
    const val Records = 2
    const val Device = 3
    const val Count = 4
}

@OptIn(ExperimentalFoundationApi::class, androidx.media3.common.util.UnstableApi::class)
@Composable
fun TrackTechAppShell() {
    TrackTechTheme {
        // Activity-scoped: 所有子屏幕共享同一 ViewModel 实例，保证状态连贯
        val sessionViewModel = koinViewModel<TestSessionViewModel>()

        val navController: NavHostController = rememberNavController()
        val backStack by navController.currentBackStackEntryAsState()
        val currentRoute = backStack?.destination?.route
        val showBottomNav = currentRoute == "home"
        val pagerState = rememberPagerState(pageCount = { TabIndex.Count })
        val coroutineScope = rememberCoroutineScope()

        val onTabSelected: (Int) -> Unit = { index ->
            coroutineScope.launch { pagerState.animateScrollToPage(index) }
        }

        // EventBus 是 SharedFlow(replay=0)，未组合的 page 收不到历史事件。
        // Shell 是唯一可靠 collector：收到事件后切 Device tab + 设置 pending flag，
        // 由 DeviceHomeScreen 组合后消费并 reset。
        var pendingShowScanSheet by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            TrackTechEventBus.showScanSheetEvent.collect {
                onTabSelected(TabIndex.Device)
                pendingShowScanSheet = true
            }
        }

        // HOLD TO END 完成后 LapLiveScreen popBackStack 立刻回 home，
        // Snackbar 在 Shell scope 显示（不阻塞 Live 屏退出）。
        val snackbarHostState = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
            LapSessionSaveBus.events.collect { result ->
                val snackResult = snackbarHostState.showSnackbar(
                    message = if (result.isDebugCapture) {
                        "自由采集 Session 已保存"
                    } else {
                        "Lap session saved · ${result.lapCount} laps"
                    },
                    actionLabel = "View Record",
                    duration = SnackbarDuration.Long,
                )
                if (snackResult == SnackbarResult.ActionPerformed) {
                    navController.navigate("lap_session_detail/${result.sessionId}")
                }
            }
        }

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(TrackTechColors.Background),
            containerColor = TrackTechColors.Background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (showBottomNav) {
                    TrackTechBottomNav(
                        currentPage = pagerState.currentPage,
                        onTabSelected = onTabSelected,
                    )
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "home",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(TrackTechColors.Background),
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                composable("home") {
                    HorizontalPager(
                        state = pagerState,
                        beyondBoundsPageCount = 1,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        when (page) {
                            TabIndex.Test -> TestHomeScreen(
                                navController = navController,
                                onTabSelected = onTabSelected,
                                sessionViewModel = sessionViewModel,
                            )
                            TabIndex.Laps -> LapsHomeScreen(
                                navController = navController,
                                onTabSelected = onTabSelected,
                                testSessionViewModel = sessionViewModel,
                            )
                            TabIndex.Records -> RecordsHomeScreen(
                                navController = navController,
                                onTabSelected = onTabSelected,
                                isActive = pagerState.settledPage == TabIndex.Records,
                            )
                            TabIndex.Device -> DeviceHomeScreen(
                                navController = navController,
                                onTabSelected = onTabSelected,
                                pendingShowScanSheet = pendingShowScanSheet,
                                onPendingShowScanSheetConsumed = { pendingShowScanSheet = false },
                                sessionViewModel = sessionViewModel,
                            )
                        }
                    }
                }
                composable("test_execution") {
                    TrackTechTestExecutionScreen(
                        navController = navController,
                        sessionViewModel = sessionViewModel,
                    )
                }
                composable("gps_details") {
                    GpsDetailsScreen(navController = navController)
                }
                composable("settings") {
                    SettingsScreen(navController = navController)
                }
                composable("lap_live") {
                    LapLiveScreen(
                        navController = navController,
                        sessionViewModel = sessionViewModel,
                    )
                }
                composable(
                    route = "lap_session_detail/{sessionId}",
                    arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val sessionId = backStackEntry.arguments?.getString("sessionId").orEmpty()
                    LapSessionDetailScreen(
                        navController = navController,
                        sessionId = sessionId,
                        sessionViewModel = sessionViewModel,
                    )
                }
                composable(
                    route = "performance_result/{testId}",
                    arguments = listOf(navArgument("testId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val testId = backStackEntry.arguments?.getString("testId").orEmpty()
                    PerformanceResultScreen(
                        testId = testId,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = "lap_detail/{sessionId}/{lapIndex}",
                    arguments = listOf(
                        navArgument("sessionId") { type = NavType.StringType },
                        navArgument("lapIndex") { type = NavType.IntType },
                    ),
                ) { backStackEntry ->
                    val sessionId = backStackEntry.arguments?.getString("sessionId").orEmpty()
                    val lapIndex = backStackEntry.arguments?.getInt("lapIndex") ?: 0
                    LapDetailScreen(
                        navController = navController,
                        sessionId = sessionId,
                        lapIndex = lapIndex,
                        sessionViewModel = sessionViewModel,
                    )
                }
                composable(
                    route = "lap_comparison/{sessionId}",
                    arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val sessionId = backStackEntry.arguments?.getString("sessionId").orEmpty()
                    LapComparisonScreen(
                        navController = navController,
                        sessionId = sessionId,
                        sessionViewModel = sessionViewModel,
                    )
                }
                // redo-video-playback-per-lap-with-blackout round：按圈回放的视频实时叠加遥测 HUD 播放屏。
                // route 带 lapIndex，从详情页圈列表点进，定位该圈起点前 3 秒，圈播完停在圈末。
                composable(
                    // lap-detail-triview-panel:startWc 可选参数——面板进全屏接力播放进度(wallClock)
                    route = "lap_video/{sessionId}/{lapIndex}?startWc={startWc}",
                    arguments = listOf(
                        navArgument("sessionId") { type = NavType.StringType },
                        navArgument("lapIndex") { type = NavType.IntType },
                        navArgument("startWc") {
                            type = NavType.LongType
                            defaultValue = -1L
                        },
                    ),
                ) { backStackEntry ->
                    val sessionId = backStackEntry.arguments?.getString("sessionId").orEmpty()
                    val lapIndex = backStackEntry.arguments?.getInt("lapIndex") ?: 0
                    val startWc = backStackEntry.arguments?.getLong("startWc") ?: -1L
                    LapVideoPlaybackScreen(
                        navController = navController,
                        sessionId = sessionId,
                        lapIndex = lapIndex,
                        initialPlayheadWallClock = startWc,
                    )
                }
            }
        }
    }
}
