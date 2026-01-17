package com.race.gps.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.race.gps.data.model.CarModel
import com.race.gps.data.model.TestRecord
import com.race.gps.data.repository.CarModelRepository
import com.race.gps.data.repository.TestRecordRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val testRecordRepository = TestRecordRepository(application)
    private val carModelRepository = CarModelRepository(application)

    // Test records
    private val _testRecords = MutableLiveData<List<TestRecord>>(emptyList())
    val testRecords: LiveData<List<TestRecord>> = _testRecords

    // Car models
    private val _carModels = MutableLiveData<List<CarModel>>(emptyList())
    val carModels: LiveData<List<CarModel>> = _carModels

    init {
        loadTestRecords()
        loadCarModels()
    }

    fun loadTestRecords() {
        viewModelScope.launch(Dispatchers.IO) {
            val records = testRecordRepository.getSavedTestRecords()
            withContext(Dispatchers.Main) {
                _testRecords.value = records
            }
        }
    }

    fun loadCarModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val carModels = carModelRepository.getSavedCarModels()
            withContext(Dispatchers.Main) {
                _carModels.value = carModels
            }
        }
    }

    fun addTestRecord(testRecord: TestRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            testRecordRepository.addTestRecord(testRecord)
            loadTestRecords()
        }
    }

    fun removeTestRecord(testRecord: TestRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            testRecordRepository.removeTestRecord(testRecord)
            loadTestRecords()
        }
    }

    fun addCarModel(carModel: CarModel) {
        viewModelScope.launch(Dispatchers.IO) {
            carModelRepository.addCarModel(carModel)
            loadCarModels()
        }
    }

    fun updateCarModel(carModel: CarModel) {
        viewModelScope.launch(Dispatchers.IO) {
            carModelRepository.updateCarModel(carModel)
            loadCarModels()
        }
    }

    fun removeCarModel(carModel: CarModel) {
        viewModelScope.launch(Dispatchers.IO) {
            carModelRepository.removeCarModel(carModel)
            loadCarModels()
        }
    }
}