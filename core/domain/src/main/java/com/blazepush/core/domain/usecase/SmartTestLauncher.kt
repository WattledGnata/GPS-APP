package com.blazepush.core.domain.usecase

import com.blazepush.core.domain.model.ConnectionState
import com.blazepush.core.domain.model.GpsData

/**
 * 智能启动测试系统
 * 检测5项启动条件，确保测试在最佳状态下开始
 */
class SmartTestLauncher {

    /**
     * 启动条件
     */
    data class LaunchCondition(
        val id: String,
        val name: String,
        val description: String,
        val isMet: Boolean,
        val icon: ConditionIcon
    )

    /**
     * 条件状态图标
     */
    enum class ConditionIcon {
        CHECKED,   // ✅ 已满足
        WAITING,   // ⏳ 等待中
        ERROR      // ❌ 未满足
    }

    /**
     * 启动状态
     */
    data class LaunchStatus(
        val conditions: List<LaunchCondition>,
        val canLaunch: Boolean,
        val unmetConditionIds: List<String>
    )

    /**
     * 检查所有启动条件
     * @param startSpeedMin 起点速度下限（用于判断是否在起点）
     * @param startSpeedMax 起点速度上限（用于判断是否在起点）
     */
    fun checkLaunchConditions(
        gpsData: GpsData,
        connectionState: ConnectionState,
        lastDataAge: Long,
        startSpeedMin: Double = 0.0,
        startSpeedMax: Double = 3.0
    ): LaunchStatus {
        val conditions = listOf(
            // 1. BLE连接状态
            LaunchCondition(
                id = "ble_connection",
                name = "BLE连接",
                description = "确保GPS设备已连接",
                isMet = connectionState == ConnectionState.CONNECTED,
                icon = when (connectionState) {
                    ConnectionState.CONNECTED -> ConditionIcon.CHECKED
                    ConnectionState.CONNECTING -> ConditionIcon.WAITING
                    ConnectionState.DISCONNECTED -> ConditionIcon.ERROR
                    ConnectionState.DISCONNECTING -> ConditionIcon.WAITING
                }
            ),
            // 2. 数据接收（< 1000ms）
            LaunchCondition(
                id = "data_reception",
                name = "数据接收",
                description = "能正常接收GPS数据",
                isMet = lastDataAge < 1000,
                icon = when {
                    lastDataAge < 1000 -> ConditionIcon.CHECKED
                    lastDataAge < 3000 -> ConditionIcon.WAITING
                    else -> ConditionIcon.ERROR
                }
            ),
            // 3. 卫星数 >= 6
            LaunchCondition(
                id = "satellite_count",
                name = "卫星数量",
                description = "至少6颗卫星",
                isMet = gpsData.satelliteCount >= 6,
                icon = when {
                    gpsData.satelliteCount >= 6 -> ConditionIcon.CHECKED
                    gpsData.satelliteCount >= 4 -> ConditionIcon.WAITING
                    else -> ConditionIcon.ERROR
                }
            ),
            // 4. HDOP < 2.0
            LaunchCondition(
                id = "hdop",
                name = "定位精度",
                description = "HDOP < 2.0",
                isMet = gpsData.hdop < 2.0 && gpsData.hdop > 0,
                icon = when {
                    gpsData.hdop < 2.0 && gpsData.hdop > 0 -> ConditionIcon.CHECKED
                    gpsData.hdop < 5.0 -> ConditionIcon.WAITING
                    else -> ConditionIcon.ERROR
                }
            ),
            // 5. 速度在起点范围
            LaunchCondition(
                id = "speed_at_start",
                name = "速度在起点",
                description = "${startSpeedMin.toInt()}-${startSpeedMax.toInt()} km/h",
                isMet = gpsData.speed in startSpeedMin..startSpeedMax,
                icon = when {
                    gpsData.speed in startSpeedMin..startSpeedMax -> ConditionIcon.CHECKED
                    gpsData.speed < startSpeedMax + 10 -> ConditionIcon.WAITING
                    else -> ConditionIcon.WAITING
                }
            )
        )

        val unmetConditions = conditions.filter { !it.isMet }
        val canLaunch = unmetConditions.isEmpty()

        return LaunchStatus(
            conditions = conditions,
            canLaunch = canLaunch,
            unmetConditionIds = unmetConditions.map { it.id }
        )
    }

    /**
     * 检查是否可以启动
     */
    fun canLaunch(
        gpsData: GpsData,
        connectionState: ConnectionState,
        lastDataAge: Long
    ): Boolean {
        return checkLaunchConditions(gpsData, connectionState, lastDataAge).canLaunch
    }
}
