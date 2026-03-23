package com.blazepush.simulator.data

/**
 * 测试场景枚举
 */
enum class TestScenario(val displayName: String) {
    STATIC("静态测试"),
    ACCELERATION("加速测试 (0-100 km/h)"),
    BRAKING("刹车测试 (100-0 km/h)")
}
