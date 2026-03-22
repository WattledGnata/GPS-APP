package com.race.gps

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件日志工具 - 用于调试关键流程
 */
object FileLogger {
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: Context) {
        logFile = File(context.filesDir, "debug_log.txt")
        // 清空旧日志
        logFile?.writeText("")
        d("FileLogger", "===== 日志开始 =====")
    }

    fun d(tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logLine = "$timestamp D/$tag: $message\n"
        logFile?.let {
            FileWriter(it, true).use { writer ->
                writer.append(logLine)
            }
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = dateFormat.format(Date())
        val logLine = "$timestamp E/$tag: $message${throwable?.let { " - ${it.message}" } ?: ""}\n"
        logFile?.let {
            FileWriter(it, true).use { writer ->
                writer.append(logLine)
            }
        }
    }
}
