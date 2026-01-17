package com.race.gps.data.model

import java.util.Date

data class TestRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val testType: String,
    val carModel: String,
    val deviceName: String,
    val deviceAddress: String,
    val result: String,
    val timestamp: Date = Date()
)