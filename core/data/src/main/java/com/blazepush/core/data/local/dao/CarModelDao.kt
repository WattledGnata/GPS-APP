package com.blazepush.core.data.local.dao

import androidx.room.*
import com.blazepush.core.data.local.entity.CarModelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CarModelDao {
    @Query("SELECT * FROM car_models")
    fun getAllCarModels(): Flow<List<CarModelEntity>>

    @Query("SELECT * FROM car_models")
    suspend fun getAllCarModelsSync(): List<CarModelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCarModel(carModel: CarModelEntity)

    @Update
    suspend fun updateCarModel(carModel: CarModelEntity)

    @Delete
    suspend fun deleteCarModel(carModel: CarModelEntity)
}
