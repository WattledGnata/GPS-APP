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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.blazepush.feature.test.viewmodel.TestSessionViewModel
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

@OptIn(ExperimentalFoundationApi::class)
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

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(TrackTechColors.Background),
            containerColor = TrackTechColors.Background,
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
                            )
                            TabIndex.Device -> DeviceHomeScreen(
                                navController = navController,
                                onTabSelected = onTabSelected,
                                pendingShowScanSheet = pendingShowScanSheet,
                                onPendingShowScanSheetConsumed = { pendingShowScanSheet = false },
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
            }
        }
    }
}
