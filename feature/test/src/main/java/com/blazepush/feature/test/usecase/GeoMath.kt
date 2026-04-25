package com.blazepush.feature.test.usecase

/**
 * change fix-active-lap-distance-accumulator（A22）：
 * 把 UI 私有 haversineDistanceMeters 实现迁出为 engine 可复用的 internal 工具。
 * 数学公式不变，仅改可见性 + 落点（feature/test 模块同包，避免污染 core/domain）。
 */
internal fun haversineDistanceMeters(
    startLatitude: Double,
    startLongitude: Double,
    endLatitude: Double,
    endLongitude: Double,
): Double {
    val earthRadiusMeters = 6_371_000.0
    val latitudeDelta = Math.toRadians(endLatitude - startLatitude)
    val longitudeDelta = Math.toRadians(endLongitude - startLongitude)
    val startLatitudeRadians = Math.toRadians(startLatitude)
    val endLatitudeRadians = Math.toRadians(endLatitude)
    val a = kotlin.math.sin(latitudeDelta / 2).let { it * it } +
        kotlin.math.cos(startLatitudeRadians) * kotlin.math.cos(endLatitudeRadians) *
        kotlin.math.sin(longitudeDelta / 2).let { it * it }
    val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    return earthRadiusMeters * c
}
