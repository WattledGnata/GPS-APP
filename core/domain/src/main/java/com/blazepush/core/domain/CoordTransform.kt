package com.blazepush.core.domain

/**
 * 坐标转换工具
 * 实现 WGS84 (GPS原始坐标) 与 GCJ-02 (火星坐标系/高德地图) 之间的转换
 * 参考国测局制定的坐标偏移算法
 */
object CoordTransform {
    private const val PI = 3.1415926535897932384626
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    /**
     * WGS84 -> GCJ-02 (GPS坐标转高德地图坐标)
     * 用于将 GPS 接收器获取的原始坐标转换为高德地图可用的坐标
     */
    fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        if (isInChina(lat, lon)) {
            val d = delta(lat, lon)
            return Pair(lat + d.first, lon + d.second)
        }
        return Pair(lat, lon)
    }

    /**
     * GCJ-02 -> WGS84 (高德地图坐标转GPS坐标)
     * 用于将高德地图 SDK 获取的坐标转换为 GPS 原始坐标
     * 使用迭代法近似转换
     */
    fun gcj02ToWgs84(lat: Double, lon: Double): Pair<Double, Double> {
        if (isInChina(lat, lon)) {
            val d = delta(lat, lon)
            return Pair(lat - d.first, lon - d.second)
        }
        return Pair(lat, lon)
    }

    /**
     * 计算偏移量 delta
     */
    private fun delta(lat: Double, lon: Double): Pair<Double, Double> {
        val dLat = transformLat(lon - 105.0, lat - 35.0)
        val dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = Math.sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        val dLatScaled = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        val dLonScaled = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI)
        return Pair(dLatScaled, dLonScaled)
    }

    /**
     * 判断坐标是否在中国境内（简化判断，用于决定是否需要转换）
     */
    private fun isInChina(lat: Double, lon: Double): Boolean {
        return lat in 18.0..54.0 && lon in 72.0..135.0
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
        ret += 0.2 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * PI)) / 3.0
        ret += (20.0 * Math.sin(2.0 * x * PI)) / 3.0
        ret += (20.0 * Math.sin(y * PI)) / 3.0
        ret += (40.0 * Math.sin(y / 3.0 * PI)) / 3.0
        ret += (160.0 * Math.sin(y / 12.0 * PI)) / 3.0
        ret += (320.0 * Math.sin(y * PI / 30.0)) / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * PI)) / 3.0
        ret += (20.0 * Math.sin(2.0 * x * PI)) / 3.0
        ret += (20.0 * Math.sin(x * PI)) / 3.0
        ret += (40.0 * Math.sin(x / 3.0 * PI)) / 3.0
        ret += (150.0 * Math.sin(x / 12.0 * PI)) / 3.0
        ret += (300.0 * Math.sin(x / 30.0 * PI)) / 3.0
        return ret
    }
}
