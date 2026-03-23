package com.blazepush.core.domain.usecase

import com.blazepush.core.domain.model.GpsData

/**
 * 数据平滑处理器
 * 使用移动平均算法平滑GPS数据，减少噪声
 */
class DataSmoothing(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE
) {

    private val dataWindow = ArrayDeque<GpsData>()

    /**
     * 平滑处理GPS数据
     * @param data 原始GPS数据
     * @return 平滑后的GPS数据
     */
    fun smooth(data: GpsData): GpsData {
        dataWindow.addLast(data)

        // 保持窗口大小
        if (dataWindow.size > windowSize) {
            dataWindow.removeFirst()
        }

        // 数据不足时返回原值
        if (dataWindow.size < windowSize) {
            return data
        }

        // 移动平均平滑
        return data.copy(
            speed = dataWindow.map { it.speed }.average(),
            latitude = dataWindow.map { it.latitude }.average(),
            longitude = dataWindow.map { it.longitude }.average(),
            altitude = dataWindow.map { it.altitude }.average(),
            bearing = dataWindow.map { it.bearing }.average()
        )
    }

    /**
     * 重置平滑器状态
     */
    fun reset() {
        dataWindow.clear()
    }

    /**
     * 获取当前窗口中的数据点数量
     */
    fun windowSize(): Int = dataWindow.size

    companion object {
        const val DEFAULT_WINDOW_SIZE = 5
    }
}
