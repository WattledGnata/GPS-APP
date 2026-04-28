package com.blazepush.feature.test.ui.tracktech
// @IgnoreFormatCheck

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.getValue

/**
 * Track Tech V2 App Shell：4 tab persistent bottom navigation 顶层容器。
 *
 * 替代旧 [com.blazepush.feature.test.ui.TestFlowNavigation] 单栈结构。
 * Test tab 内仍嵌套 TestFlowNavigation 的 nested nav，但 startDestination
 * 已改为 Selection（Connection 路由保留作 transitional fallback）。
 */
private val tabRoutes = setOf("test", "laps", "records", "device")

@Composable
fun TrackTechAppShell() {
    TrackTechTheme {
        val navController: NavHostController = rememberNavController()
        val backStack by navController.currentBackStackEntryAsState()
        val currentRoute = backStack?.destination?.route
        val showBottomNav = currentRoute in tabRoutes

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(TrackTechColors.Background),
            containerColor = TrackTechColors.Background,
            bottomBar = {
                if (showBottomNav) {
                    TrackTechBottomNav(navController = navController)
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "test",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(TrackTechColors.Background),
            ) {
                composable("test") { TestHomeScreen(navController = navController) }
                composable("laps") { LapsHomeScreen(navController = navController) }
                composable("records") { RecordsHomeScreen(navController = navController) }
                composable("device") { DeviceHomeScreen(navController = navController) }
                composable("gps_details") { GpsDetailsScreen(navController = navController) }
                composable("test_execution") { TrackTechTestExecutionScreen(navController = navController) }
            }
        }
    }
}
