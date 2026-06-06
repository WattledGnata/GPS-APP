// @IgnoreFormatCheck
package com.blazepush.core.data.local.mapper

import com.blazepush.core.data.local.entity.BluetoothDeviceEntity
import com.blazepush.core.data.local.entity.CarModelEntity
import com.blazepush.core.data.model.BluetoothDeviceModel
import com.blazepush.core.data.model.CarModel

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
        name = this.name,
        alias = this.alias,
        lastConnectedAtMs = this.lastConnectedAtMs
    )
}

fun BluetoothDeviceEntity.toModel(): BluetoothDeviceModel {
    return BluetoothDeviceModel(
        name = this.name,
        address = this.address,
        alias = this.alias,
        lastConnectedAtMs = this.lastConnectedAtMs
    )
}
