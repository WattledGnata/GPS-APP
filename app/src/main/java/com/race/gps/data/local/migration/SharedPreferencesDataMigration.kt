package com.race.gps.data.local.migration

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.race.gps.data.local.AppDatabase
import com.race.gps.data.local.mapper.toEntity
import com.race.gps.data.model.CarModel
import com.race.gps.data.model.BluetoothDeviceModel
import com.race.gps.data.model.TestRecord

/**
 * 数据迁移工具类
 * 负责将SharedPreferences中的数据迁移到Room数据库
 */
class SharedPreferencesDataMigration(
    private val context: Context,
    private val database: AppDatabase
) {
    companion object {
        private const val TAG = "DataMigration"
        private const val MIGRATION_PREFS = "migration_status"
        private const val KEY_MIGRATED = "migrated"
    }

    private val prefs = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * 检查并执行数据迁移
     */
    suspend fun migrateIfNeeded() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) {
            Log.d(TAG, "Data already migrated, skipping")
            return
        }

        Log.d(TAG, "Starting data migration from SharedPreferences to Room")

        try {
            // 迁移TestRecords
            migrateTestRecords()
            // 迁移CarModels
            migrateCarModels()
            // 迁移BluetoothDevices
            migrateBluetoothDevices()

            // 标记已迁移
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            Log.d(TAG, "Data migration completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during data migration", e)
            // 不标记为已迁移，下次启动时重试
        }
    }

    private suspend fun migrateTestRecords() {
        try {
            val testRecordsPrefs = context.getSharedPreferences("test_records", Context.MODE_PRIVATE)
            val testRecordsJson = testRecordsPrefs.getString("saved_test_records", "[]")

            if (testRecordsJson.isNullOrEmpty() || testRecordsJson == "[]") {
                Log.d(TAG, "No test records to migrate")
                return
            }

            val recordListType = object : TypeToken<List<TestRecord>>() {}.type
            val testRecords: List<TestRecord> = gson.fromJson(testRecordsJson, recordListType)

            Log.d(TAG, "Migrating ${testRecords.size} test records")

            testRecords.forEach { testRecord ->
                val (entity, dataPoints) = testRecord.toEntity()
                database.testRecordDao().insertTestRecord(entity)
                if (dataPoints.isNotEmpty()) {
                    database.testRecordDao().insertDataPoints(dataPoints)
                }
            }

            Log.d(TAG, "Test records migrated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error migrating test records", e)
            throw e
        }
    }

    private suspend fun migrateCarModels() {
        try {
            val carModelsPrefs = context.getSharedPreferences("car_models", Context.MODE_PRIVATE)
            val carModelsJson = carModelsPrefs.getString("saved_car_models", "[]")

            if (carModelsJson.isNullOrEmpty() || carModelsJson == "[]") {
                Log.d(TAG, "No car models to migrate")
                return
            }

            val carModelListType = object : TypeToken<List<CarModel>>() {}.type
            val carModels: List<CarModel> = gson.fromJson(carModelsJson, carModelListType)

            Log.d(TAG, "Migrating ${carModels.size} car models")

            carModels.forEach { carModel ->
                database.carModelDao().insertCarModel(carModel.toEntity())
            }

            Log.d(TAG, "Car models migrated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error migrating car models", e)
            throw e
        }
    }

    private suspend fun migrateBluetoothDevices() {
        try {
            val devicesPrefs = context.getSharedPreferences("bluetooth_devices", Context.MODE_PRIVATE)
            val devicesJson = devicesPrefs.getString("saved_devices", "[]")

            if (devicesJson.isNullOrEmpty() || devicesJson == "[]") {
                Log.d(TAG, "No bluetooth devices to migrate")
                return
            }

            val deviceListType = object : TypeToken<List<BluetoothDeviceModel>>() {}.type
            val devices: List<BluetoothDeviceModel> = gson.fromJson(devicesJson, deviceListType)

            Log.d(TAG, "Migrating ${devices.size} bluetooth devices")

            devices.forEach { device ->
                database.bluetoothDeviceDao().insertDevice(device.toEntity())
            }

            Log.d(TAG, "Bluetooth devices migrated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error migrating bluetooth devices", e)
            throw e
        }
    }
}
