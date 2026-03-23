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
    val avgAcceleration: Double = 0.0,
    val maxAcceleration: Double = 0.0,
    val dataFilePath: String = ""    // 原始数据文件路径
)
