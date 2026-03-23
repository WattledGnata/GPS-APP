package com.blazepush.feature.test.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.blazepush.core.domain.model.TestTemplate
import com.blazepush.feature.test.ui.screen.*
import com.blazepush.feature.test.viewmodel.TestSessionViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * 应用导航图
 * 管理测试流程的页面跳转
 */
sealed class TestNavRoute {
    object Connection : TestNavRoute()
    object Selection : TestNavRoute()
    object Execution : TestNavRoute()
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

    // 返回手势处理
    BackHandler {
        when (currentRoute) {
            is TestNavRoute.Connection -> {
                // 不处理，保持在连接页面
            }
            is TestNavRoute.Selection -> {
                // 不处理，保持在选择页面
            }
            is TestNavRoute.Execution -> {
                testSessionViewModel.cancelTest()
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
                onHistoryClick = {
                    currentRoute = TestNavRoute.History
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
