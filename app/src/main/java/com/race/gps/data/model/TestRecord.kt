package com.race.gps.data.model

import java.io.Serializable
import java.util.Date

// Data class to store acceleration data points (time vs speed)
data class AccelerationDataPoint(
    val time: Double, // Time in seconds since test start
    val speed: Double  // Speed in km/h
) : Serializable

data class TestRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val testType: String,
    val carModel: String,
    val deviceName: String,
    val deviceAddress: String,
    val result: String,
    val timestamp: Date = Date(),
    val accelerationData: List<AccelerationDataPoint> = emptyList() // List of acceleration data points
) : Serializable