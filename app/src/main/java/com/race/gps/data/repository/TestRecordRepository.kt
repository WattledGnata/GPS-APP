package com.race.gps.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.race.gps.data.model.TestRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TestRecordRepository(private val context: Context) {
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("test_records", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val recordListType = object : TypeToken<List<TestRecord>>() {}.type
    
    // StateFlow for real-time test record updates
    private val _testRecordsFlow = MutableStateFlow<List<TestRecord>>(emptyList())
    val testRecordsFlow: StateFlow<List<TestRecord>> = _testRecordsFlow
    
    init {
        // Load initial test records and update flow
        _testRecordsFlow.value = getSavedTestRecords()
        
        // Register listener for shared preferences changes
        sharedPreferences.registerOnSharedPreferenceChangeListener {
            _, key ->
            if (key == "saved_test_records") {
                _testRecordsFlow.value = getSavedTestRecords()
            }
        }
    }

    fun saveTestRecords(testRecords: List<TestRecord>) {
        val testRecordsJson = gson.toJson(testRecords)
        sharedPreferences.edit().putString("saved_test_records", testRecordsJson).apply()
        // Flow will be updated via shared preference listener
    }

    fun getSavedTestRecords(): List<TestRecord> {
        val testRecordsJson = sharedPreferences.getString("saved_test_records", "[]")
        return gson.fromJson(testRecordsJson, recordListType)
    }

    fun addTestRecord(testRecord: TestRecord) {
        val testRecords = getSavedTestRecords().toMutableList()
        testRecords.add(0, testRecord) // Add to beginning of list for chronological order
        saveTestRecords(testRecords)
        // Flow will be updated via shared preference listener
    }

    fun removeTestRecord(testRecord: TestRecord) {
        val testRecords = getSavedTestRecords().toMutableList()
        testRecords.remove(testRecord)
        saveTestRecords(testRecords)
        // Flow will be updated via shared preference listener
    }

    fun clearAllTestRecords() {
        saveTestRecords(emptyList())
        // Flow will be updated via shared preference listener
    }
}