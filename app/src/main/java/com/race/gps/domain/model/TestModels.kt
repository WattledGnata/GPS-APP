package com.race.gps.domain.model

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
 */
data class TestSession(
    val id: String,
    val template: TestTemplate,
    val carModel: String,
    val startTime: Long,
    val dataPoints: MutableList<GpsDataPoint> = mutableListOf(),
    var triggerTime: Long? = null,
    var endTime: Long? = null
) {
    fun markStarted(gpsData: GpsData) {
        triggerTime = gpsData.timestamp
        addDataPoint(gpsData, 0.0)
    }

    fun addDataPoint(gpsData: GpsData) {
        val triggerTime = this.triggerTime ?: return
        val elapsedTime = (gpsData.timestamp - triggerTime) / 1000.0
        addDataPoint(gpsData, elapsedTime)
    }

    private fun addDataPoint(gpsData: GpsData, elapsedTime: Double) {
        dataPoints.add(
            GpsDataPoint(
                elapsedTime = elapsedTime,
                speed = gpsData.speed,
                latitude = gpsData.latitude,
                longitude = gpsData.longitude,
                altitude = gpsData.altitude
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
