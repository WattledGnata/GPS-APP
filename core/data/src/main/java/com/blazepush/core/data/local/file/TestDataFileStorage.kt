package com.blazepush.core.data.local.file

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.blazepush.core.domain.model.GpsDataPoint
import java.io.File

/**
 * 测试原始数据文件存储
 * 将GPS数据点序列保存为JSON文件，避免数据库存储大量数据点
 */
class TestDataFileStorage(private val context: Context) {

    companion object {
        private const val TAG = "TestDataFileStorage"
        private const val DIR_NAME = "test_data"
    }

    private val gson = Gson()
    private val storageDir: File
        get() = File(context.filesDir, DIR_NAME).also { it.mkdirs() }

    /**
     * 保存测试数据点到文件
     * @return 文件路径
     */
    fun saveDataPoints(testId: String, dataPoints: List<GpsDataPoint>): String {
        val file = File(storageDir, "$testId.json")
        return try {
            file.writeText(gson.toJson(dataPoints))
            Log.d(TAG, "Saved ${dataPoints.size} data points to ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save data points", e)
            ""
        }
    }

    /**
     * 从文件读取测试数据点
     */
    fun loadDataPoints(filePath: String): List<GpsDataPoint> {
        return try {
            val file = File(filePath)
            if (!file.exists()) return emptyList()
            val json = file.readText()
            gson.fromJson(json, Array<GpsDataPoint>::class.java).toList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load data points from $filePath", e)
            emptyList()
        }
    }

    /**
     * 删除测试数据文件
     */
    fun deleteDataFile(filePath: String) {
        try {
            File(filePath).delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete data file $filePath", e)
        }
    }
}
