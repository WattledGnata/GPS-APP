package com.race.gps.data.repository

import com.race.gps.data.local.dao.CarModelDao
import com.race.gps.data.local.mapper.toEntity
import com.race.gps.data.local.mapper.toModel
import com.race.gps.data.model.CarModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CarModelRepository(
    private val carModelDao: CarModelDao
) {
    // Flow自动从Room获取
    val carModelsFlow: Flow<List<CarModel>> =
        carModelDao.getAllCarModels()
            .map { list -> list.map { it.toModel() } }

    suspend fun saveCarModels(carModels: List<CarModel>) {
        carModels.forEach { carModel ->
            carModelDao.insertCarModel(carModel.toEntity())
        }
    }

    suspend fun getSavedCarModels(): List<CarModel> {
        return carModelDao.getAllCarModelsSync().map { it.toModel() }
    }

    suspend fun addCarModel(carModel: CarModel) {
        carModelDao.insertCarModel(carModel.toEntity())
    }

    suspend fun updateCarModel(carModel: CarModel) {
        carModelDao.updateCarModel(carModel.toEntity())
    }

    suspend fun removeCarModel(carModel: CarModel) {
        carModelDao.deleteCarModel(carModel.toEntity())
    }
}