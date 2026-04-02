package com.blazepush.feature.test.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.blazepush.core.domain.model.TestTemplate
import com.blazepush.feature.test.model.LapRunConfig
import com.blazepush.feature.test.ui.screen.DeviceConnectionScreen
import com.blazepush.feature.test.ui.screen.LapDebugConfigScreen
import com.blazepush.feature.test.ui.screen.LapDebugResultScreen
import com.blazepush.feature.test.ui.screen.TestExecutionScreen
import com.blazepush.feature.test.ui.screen.TestHistoryScreen
import com.blazepush.feature.test.ui.screen.TestResultScreen
import com.blazepush.feature.test.ui.screen.TestSelectionScreen
import com.blazepush.feature.test.viewmodel.TestSessionViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * 应用导航图
 * 管理测试流程的页面跳转
 */
sealed class TestNavRoute {
    object Connection : TestNavRoute()
    object Selection : TestNavRoute()
    object LapDebugConfig : TestNavRoute()
    object Execution : TestNavRoute()
    object LapDebugResult : TestNavRoute()
    data class Result(val testId: String) : TestNavRoute()
    object History : TestNavRoute()
}

@Composable
fun TestFlowNavigation(
    modifier: Modifier = Modifier,
    testSessionViewModel: TestSessionViewModel = koinViewModel()
) {
    var currentRoute by remember { mutableStateOf<TestNavRoute>(TestNavRoute.Connection) }
    var selectedTemplate by remember { mutableStateOf<TestTemplate?>(null) }
    var selectedCarModel by remember { mutableStateOf("") }
    val availableTracks by testSessionViewModel.availableTracks.collectAsState()
    val lapRunConfig by testSessionViewModel.lapRunConfig.collectAsState()
    val latestLapRecords by testSessionViewModel.latestLapRecords.collectAsState()
    val lapSession by testSessionViewModel.lapSession.collectAsState()

    // 返回手势处理
    BackHandler {
        when (currentRoute) {
            is TestNavRoute.Connection -> {
                // 不处理，保持在连接页面
            }
            is TestNavRoute.Selection -> {
                // 不处理，保持在选择页面
            }
            is TestNavRoute.LapDebugConfig -> {
                currentRoute = TestNavRoute.Selection
            }
            is TestNavRoute.Execution -> {
                if (testSessionViewModel.currentMode.value == com.blazepush.feature.test.viewmodel.TestMode.LapDebug) {
                    testSessionViewModel.stopLapDebugSession()
                    currentRoute = TestNavRoute.LapDebugResult
                } else {
                    testSessionViewModel.cancelTest()
                    currentRoute = TestNavRoute.Selection
                }
            }
            is TestNavRoute.LapDebugResult -> {
                currentRoute = TestNavRoute.Selection
            }
            is TestNavRoute.Result -> {
                currentRoute = TestNavRoute.History
            }
            is TestNavRoute.History -> {
                currentRoute = TestNavRoute.Selection
            }
        }
    }

    when (val route = currentRoute) {
        is TestNavRoute.Connection -> {
            DeviceConnectionScreen(
                onConnected = { currentRoute = TestNavRoute.Selection }
            )
        }
        is TestNavRoute.Selection -> {
            TestSelectionScreen(
                onTestSelected = { template, carModel ->
                    selectedTemplate = template
                    selectedCarModel = carModel
                    testSessionViewModel.enterSmartLaunch(template, carModel)
                    currentRoute = TestNavRoute.Execution
                },
                onLapDebugClick = {
                    currentRoute = TestNavRoute.LapDebugConfig
                },
                onHistoryClick = {
                    currentRoute = TestNavRoute.History
                }
            )
        }
        is TestNavRoute.LapDebugConfig -> {
            LapDebugConfigScreen(
                availableTracks = availableTracks,
                initialConfig = lapRunConfig,
                onConfirm = { config: LapRunConfig ->
                    testSessionViewModel.selectLapDebugMode(config)
                    currentRoute = TestNavRoute.Execution
                },
                onBack = {
                    currentRoute = TestNavRoute.Selection
                }
            )
        }
        is TestNavRoute.Execution -> {
            TestExecutionScreen(
                onTestCompleted = { testId ->
                    currentRoute = TestNavRoute.Result(testId)
                },
                onCancel = {
                    currentRoute = TestNavRoute.Selection
                },
                onLapDebugStopped = {
                    currentRoute = TestNavRoute.LapDebugResult
                }
            )
        }
        is TestNavRoute.LapDebugResult -> {
            LapDebugResultScreen(
                latestLapRecords = latestLapRecords,
                latestCrossing = lapSession?.crossingEvents?.lastOrNull(),
                onBackToSelection = {
                    currentRoute = TestNavRoute.Selection
                }
            )
        }
        is TestNavRoute.Result -> {
            TestResultScreen(
                testId = route.testId,
                onBack = { currentRoute = TestNavRoute.History }
            )
        }
        is TestNavRoute.History -> {
            TestHistoryScreen(
                onRecordClick = { testId ->
                    currentRoute = TestNavRoute.Result(testId)
                },
                onBack = {
                    currentRoute = TestNavRoute.Selection
                }
            )
        }
    }
}
