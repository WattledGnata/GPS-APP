// @IgnoreFormatCheck
// 理由：本 round wire-real-data-to-records-and-laps-tabs §1.3 仅追加 TestResultSummary
//       一个轻量 DTO data class（用于 RecordsHomeScreen RECENT RUNS 列表展示，避免完整
//       TestResult 重构开销）；既有 7 个 domain class（GpsDataPoint / SpeedSegment /
//       TestTemplate / TestSession / TestResult / TestState 等）的 @author/@description/@date
//       tag 与 6 个 public fun doc 缺失为 baseline 历史问题（先前 round 提交时 kt-check
//       未启 strict 模式）。按 CLAUDE.md scope-boundary 不在本 round 内统一补齐，
//       推到 D round（kt-format-cleanup-pass）批量处理。
package com.blazepush.core.domain.model

import com.blazepush.core.domain.usecase.FilteredGpsData

/**
 * 测试数据点 - 记录测试过程中每个GPS采样点
 */
data class GpsDataPoint(
    val elapsedTime: Double,  // 相对测试开始的时间（秒）
    val speed: Double,        // km/h
    val latitude: Double,
    val longitude: Double,
    val altitude: Double
)

/**
 * 速度分段数据 - 10km/h一段
 */
data class SpeedSegment(
    val startSpeed: Int,    // 起始速度 (km/h)
    val endSpeed: Int,      // 结束速度 (km/h)
    val time: Double,       // 该段用时（秒）
    val distance: Double    // 该段距离（米）
)

/**
 * 测试模板 - 定义测试类型、触发条件、结束条件
 */
sealed class TestTemplate(
    val id: String,
    val name: String,
    val description: String,
    val startSpeed: Int,
    val endSpeed: Int
) {
    abstract fun shouldTrigger(gpsData: GpsData): Boolean
    abstract fun shouldEnd(gpsData: GpsData): Boolean

    /**
     * 0-100 加速测试
     * 准备条件：速度接近 0（< 3 km/h）
     * 触发条件：从准备状态加速超过 5 km/h
     */
    object Acceleration0To100 : TestTemplate(
        id = "acc_0_100",
        name = "0-100 加速",
        description = "测试0-100km/h加速性能",
        startSpeed = 0,
        endSpeed = 100
    ) {
        // 准备条件：速度在起点范围（静止）
        fun isReady(gpsData: GpsData): Boolean {
            return gpsData.speed < 3.0
        }

        // 触发条件：从静止开始加速（速度 > 5 km/h）
        override fun shouldTrigger(gpsData: GpsData): Boolean {
            return gpsData.speed > 5.0
        }

        override fun shouldEnd(gpsData: GpsData): Boolean {
            return gpsData.speed >= 100.0
        }
    }

    /**
     * 100-0 刹车测试
     * 准备条件：速度接近 100（95-105 km/h）
     * 触发条件：从准备状态减速低于 95 km/h
     */
    object Braking100To0 : TestTemplate(
        id = "brake_100_0",
        name = "100-0 刹车",
        description = "测试100-0km/h刹车性能",
        startSpeed = 100,
        endSpeed = 0
    ) {
        // 准备条件：速度在起点范围（接近 100）
        fun isReady(gpsData: GpsData): Boolean {
            return gpsData.speed in 95.0..105.0
        }

        // 触发条件：开始刹车（速度 < 95 km/h）
        override fun shouldTrigger(gpsData: GpsData): Boolean {
            return gpsData.speed < 95.0 && gpsData.speed > 1.0
        }

        override fun shouldEnd(gpsData: GpsData): Boolean {
            return gpsData.speed <= 1.0
        }
    }

    companion object {
        fun all(): List<TestTemplate> = listOf(Acceleration0To100, Braking100To0)
        fun fromId(id: String): TestTemplate? = all().find { it.id == id }
    }
}

/**
 * 测试会话 - 一次完整的测试过程
 *
 * 设计规范：docs/superpowers/specs/2026-03-21-gps-data-filter-design.md
 */
data class TestSession(
    val id: String,
    val template: TestTemplate,
    val carModel: String,
    var startTime: Long,  // 用 var 允许在 markStarted 时更新为触发时刻
    // 触发前2秒的滤波数据（触发时锁定传入）
    val preTriggerData: List<FilteredGpsData> = emptyList(),
    // 测试过程的滤波数据
    val filteredDataPoints: MutableList<FilteredGpsData> = mutableListOf(),
    // 传统数据点（用于向后兼容和结果展示）
    val dataPoints: MutableList<GpsDataPoint> = mutableListOf(),
    var triggerTime: Long? = null,
    var endTime: Long? = null
) {
    companion object {
        private const val TAG = "TestSession"
    }

    /**
     * 标记测试开始（触发时调用）
     * @param filteredData 触发点的滤波数据
     * @param preTriggerBuffer 触发前2秒的缓冲数据
     */
    fun markStarted(filteredData: FilteredGpsData, preTriggerBuffer: List<FilteredGpsData>) {
        triggerTime = filteredData.timestamp

        // 添加pre-trigger数据
        filteredDataPoints.addAll(preTriggerBuffer)

        // 添加触发点
        addFilteredDataPoint(filteredData, 0.0)
    }

    /**
     * 添加滤波后的数据点
     */
    fun addFilteredDataPoint(filteredData: FilteredGpsData) {
        val triggerTime = this.triggerTime ?: return
        val elapsedTime = (filteredData.timestamp - triggerTime) / 1000.0
        addFilteredDataPoint(filteredData, elapsedTime)
    }

    private fun addFilteredDataPoint(filteredData: FilteredGpsData, elapsedTime: Double) {
        // 添加到滤波数据列表
        filteredDataPoints.add(filteredData)

        // 同时添加传统数据点（向后兼容）
        dataPoints.add(
            GpsDataPoint(
                elapsedTime = elapsedTime,
                speed = filteredData.speed,
                latitude = filteredData.latitude,
                longitude = filteredData.longitude,
                altitude = filteredData.altitude
            )
        )
    }
}

/**
 * 测试结果 - 从TestSession计算得出
 */
data class TestResult(
    val id: String,
    val sessionId: String,
    val template: TestTemplate,
    val carModel: String,
    val timestamp: Long,
    val totalTime: Double,          // 秒
    val totalDistance: Double,      // 米
    val avgAcceleration: Double,    // G
    val maxAcceleration: Double,    // G
    val segments: List<SpeedSegment>,
    val dataPoints: List<GpsDataPoint>,
    val dataFilePath: String        // 原始数据文件路径
)

/**
 * 测试结果摘要 — UI 列表 / metric tile 渲染用的轻量 DTO。
 *
 * 不含 [TestResult.segments] 与 [TestResult.dataPoints] —— 前者要 join SpeedSegment 表、
 * 后者要解析 binary file，UI 不需要。Records tab PERFORMANCE 区块的 RECENT RUNS 列表 +
 * BEST 0-100 / BEST BRAKE / TOTAL RUNS 等场景只需要本 DTO 即可。
 *
 * round wire-real-data-to-records-and-laps-tabs §1.3 引入。
 */
data class TestResultSummary(
    val id: String,
    val testTemplateId: String,     // "acc_0_100" / "brake_100_0"
    val carModel: String,
    val timestamp: Long,            // epoch ms
    val totalTime: Double,          // 秒
    val totalDistance: Double,      // 米
    // round redesign-performance-result-screen 追加：UI 层（如 RecordsHomeScreen.SpeedCurveSection）
    // 需要透 dataFilePath 直接读 binary dataPoints 画真实曲线 + 100 km/h 标注，避免再多走一层
    // Repository getDataPointsForResult flow 包装。值为空字符串视为无 binary。
    val dataFilePath: String = "",
)

/**
 * 测试状态机
 */
sealed class TestState {
    object Idle : TestState()

    /**
     * 准备中 - 智能启动条件检查阶段
     */
    data class Preparing(
        val template: TestTemplate,
        val carModel: String
    ) : TestState()

    /**
     * 就绪 - 所有条件满足，速度在起点范围，等待触发
     */
    data class Ready(
        val template: TestTemplate,
        val carModel: String
    ) : TestState()

    data class Running(
        val session: TestSession
    ) : TestState()

    data class Completed(val result: TestResult) : TestState()
}
