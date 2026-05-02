package com.blazepush.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "test_records")
data class TestRecordEntity(
    @PrimaryKey val id: String,
    val testTemplateId: String,      // 测试模板ID（acc_0_100 / brake_100_0）
    val testType: String,
    val carModel: String,
    val deviceName: String,
    val deviceAddress: String,
    val result: String,
    val timestamp: Long,
    val totalTime: Double = 0.0,     // 总时间（秒）
    val totalDistance: Double = 0.0, // 总距离（米）
    val avgAcceleration: Double = 0.0, // G（绝对值平均，恒 ≥ 0）
    /** 正向最大加速 G。0-100 acc 测试有正值；100-0 brake 测试为 0.0；V1 历史记录含旧 abs 污染。 */
    val maxAcceleration: Double = 0.0,
    /**
     * 负向最大制动 G 的绝对值。100-0 brake 测试有正值；0-100 acc 测试为 0.0；
     * V1 历史记录该字段一律 0.0（迁移默认值），UI 显式 "—" + "V1 record" 副标降级。
     * round smooth-perftest-acceleration-curve 引入（Room v4 → v5）。
     */
    val maxDeceleration: Double = 0.0,
    val dataFilePath: String = ""    // 原始数据文件路径
)
