package com.race.gps.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.race.gps.domain.model.TestTemplate
import com.race.gps.ui.screen.*
import com.race.gps.viewmodel.TestSessionViewModel
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
                    testSessionViewModel.startTest(template, carModel)
                    currentRoute = TestNavRoute.Execution
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
                }
            )
        }
    }
}
