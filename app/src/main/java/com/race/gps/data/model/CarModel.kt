package com.race.gps.data.model

data class CarModel(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val brand: String,
    val year: String,
    val description: String = ""
)