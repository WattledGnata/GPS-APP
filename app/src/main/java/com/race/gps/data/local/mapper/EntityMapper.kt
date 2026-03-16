package com.race.gps.data.local.mapper

import com.race.gps.data.local.dao.TestRecordWithDataPoints
import com.race.gps.data.local.entity.AccelerationDataPointEntity
import com.race.gps.data.local.entity.BluetoothDeviceEntity
import com.race.gps.data.local.entity.CarModelEntity
import com.race.gps.data.local.entity.TestRecordEntity
import com.race.gps.data.model.AccelerationDataPoint
import com.race.gps.data.model.BluetoothDeviceModel
import com.race.gps.data.model.CarModel
import com.race.gps.data.model.TestRecord
import java.util.Date

// TestRecord <-> TestRecordEntity + AccelerationDataPointEntity
fun TestRecord.toEntity(): Pair<TestRecordEntity, List<AccelerationDataPointEntity>> {
    val entity = TestRecordEntity(
        id = this.id,
        testType = this.testType,
        carModel = this.carModel,
        deviceName = this.deviceName,
        deviceAddress = this.deviceAddress,
        result = this.result,
        timestamp = this.timestamp.time
    )
    val dataPoints = this.accelerationData.map {
        AccelerationDataPointEntity(
            testRecordId = this.id,
            time = it.time,
            speed = it.speed
        )
    }
    return Pair(entity, dataPoints)
}

fun TestRecordWithDataPoints.toModel(): TestRecord {
    return TestRecord(
        id = testRecord.id,
        testType = testRecord.testType,
        carModel = testRecord.carModel,
        deviceName = testRecord.deviceName,
        deviceAddress = testRecord.deviceAddress,
        result = testRecord.result,
        timestamp = Date(testRecord.timestamp),
        accelerationData = dataPoints.map {
            AccelerationDataPoint(
                time = it.time,
                speed = it.speed
            )
        }
    )
}

// CarModel <-> CarModelEntity
fun CarModel.toEntity(): CarModelEntity {
    return CarModelEntity(
        id = this.id,
        name = this.name,
        brand = this.brand,
        year = this.year,
        description = this.description
    )
}

fun CarModelEntity.toModel(): CarModel {
    return CarModel(
        id = this.id,
        name = this.name,
        brand = this.brand,
        year = this.year,
        description = this.description
    )
}

// BluetoothDeviceModel <-> BluetoothDeviceEntity
fun BluetoothDeviceModel.toEntity(): BluetoothDeviceEntity {
    return BluetoothDeviceEntity(
        address = this.address,
        name = this.name
    )
}

fun BluetoothDeviceEntity.toModel(): BluetoothDeviceModel {
    return BluetoothDeviceModel(
        name = this.name,
        address = this.address
    )
}
