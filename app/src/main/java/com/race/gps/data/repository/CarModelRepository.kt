package com.race.gps.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.race.gps.data.model.CarModel

class CarModelRepository(private val context: Context) {
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("car_models", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val carListType = object : TypeToken<List<CarModel>>() {}.type

    fun saveCarModels(carModels: List<CarModel>) {
        val carModelsJson = gson.toJson(carModels)
        sharedPreferences.edit().putString("saved_car_models", carModelsJson).apply()
    }

    fun getSavedCarModels(): List<CarModel> {
        val carModelsJson = sharedPreferences.getString("saved_car_models", "[]")
        return gson.fromJson(carModelsJson, carListType)
    }

    fun addCarModel(carModel: CarModel) {
        val carModels = getSavedCarModels().toMutableList()
        carModels.add(carModel)
        saveCarModels(carModels)
    }

    fun updateCarModel(carModel: CarModel) {
        val carModels = getSavedCarModels().toMutableList()
        val index = carModels.indexOfFirst { it.id == carModel.id }
        if (index != -1) {
            carModels[index] = carModel
            saveCarModels(carModels)
        }
    }

    fun removeCarModel(carModel: CarModel) {
        val carModels = getSavedCarModels().toMutableList()
        carModels.remove(carModel)
        saveCarModels(carModels)
    }
}