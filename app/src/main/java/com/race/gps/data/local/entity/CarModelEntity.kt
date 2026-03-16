package com.race.gps.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "car_models")
data class CarModelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val brand: String,
    val year: String,
    val description: String
)
