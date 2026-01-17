package com.race.gps.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.race.gps.data.model.TestRecord

class TestRecordRepository(private val context: Context) {
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("test_records", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val recordListType = object : TypeToken<List<TestRecord>>() {}.type

    fun saveTestRecords(testRecords: List<TestRecord>) {
        val testRecordsJson = gson.toJson(testRecords)
        sharedPreferences.edit().putString("saved_test_records", testRecordsJson).apply()
    }

    fun getSavedTestRecords(): List<TestRecord> {
        val testRecordsJson = sharedPreferences.getString("saved_test_records", "[]")
        return gson.fromJson(testRecordsJson, recordListType)
    }

    fun addTestRecord(testRecord: TestRecord) {
        val testRecords = getSavedTestRecords().toMutableList()
        testRecords.add(0, testRecord) // Add to beginning of list for chronological order
        saveTestRecords(testRecords)
    }

    fun removeTestRecord(testRecord: TestRecord) {
        val testRecords = getSavedTestRecords().toMutableList()
        testRecords.remove(testRecord)
        saveTestRecords(testRecords)
    }

    fun clearAllTestRecords() {
        saveTestRecords(emptyList())
    }
}