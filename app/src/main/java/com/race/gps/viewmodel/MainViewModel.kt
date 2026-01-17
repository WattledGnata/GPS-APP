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
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "RaceChronoGPS"
    }
    
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
        Log.d(TAG, "Loading test records...")
        viewModelScope.launch(Dispatchers.IO) {
            val records = testRecordRepository.getSavedTestRecords()
            Log.d(TAG, "Loaded ${records.size} test records")
            withContext(Dispatchers.Main) {
                _testRecords.value = records
            }
        }
    }

    fun loadCarModels() {
        Log.d(TAG, "Loading car models...")
        viewModelScope.launch(Dispatchers.IO) {
            val carModels = carModelRepository.getSavedCarModels()
            Log.d(TAG, "Loaded ${carModels.size} car models")
            withContext(Dispatchers.Main) {
                _carModels.value = carModels
            }
        }
    }

    fun addTestRecord(testRecord: TestRecord) {
        Log.d(TAG, "Adding test record: $testRecord")
        viewModelScope.launch(Dispatchers.IO) {
            testRecordRepository.addTestRecord(testRecord)
            Log.d(TAG, "Test record added successfully")
            loadTestRecords()
        }
    }

    fun removeTestRecord(testRecord: TestRecord) {
        Log.d(TAG, "Removing test record: $testRecord")
        viewModelScope.launch(Dispatchers.IO) {
            testRecordRepository.removeTestRecord(testRecord)
            Log.d(TAG, "Test record removed successfully")
            loadTestRecords()
        }
    }

    fun addCarModel(carModel: CarModel) {
        Log.d(TAG, "Adding car model: $carModel")
        viewModelScope.launch(Dispatchers.IO) {
            carModelRepository.addCarModel(carModel)
            Log.d(TAG, "Car model added successfully")
            loadCarModels()
        }
    }

    fun updateCarModel(carModel: CarModel) {
        Log.d(TAG, "Updating car model: $carModel")
        viewModelScope.launch(Dispatchers.IO) {
            carModelRepository.updateCarModel(carModel)
            Log.d(TAG, "Car model updated successfully")
            loadCarModels()
        }
    }

    fun removeCarModel(carModel: CarModel) {
        Log.d(TAG, "Removing car model: $carModel")
        viewModelScope.launch(Dispatchers.IO) {
            carModelRepository.removeCarModel(carModel)
            Log.d(TAG, "Car model removed successfully")
            loadCarModels()
        }
    }
}