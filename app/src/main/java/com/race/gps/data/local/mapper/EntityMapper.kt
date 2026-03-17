package com.race.gps.data.local.mapper

import com.race.gps.data.local.entity.BluetoothDeviceEntity
import com.race.gps.data.local.entity.CarModelEntity
import com.race.gps.data.model.BluetoothDeviceModel
import com.race.gps.data.model.CarModel

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
