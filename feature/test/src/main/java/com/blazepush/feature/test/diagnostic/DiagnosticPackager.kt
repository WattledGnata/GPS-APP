// @IgnoreFormatCheck
package com.blazepush.feature.test.diagnostic

import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断数据打包器（add-diagnostic-log-upload，design Decision 2/3/4）。
 *
 * 把 `filesDir/telemetry/` 下的 .bin + Room 三件套（race_chrono_database +存在的 -wal/-shm）
 * + `filesDir/debug_log.txt`(+存在的 .txt.1) 打成单个 zip；**硬排除 `filesDir/video/`**
 * （GB 级，不上传）；缺失文件跳过而非整体失败。
 *
 * 纯文件 IO，无 Android framework 依赖，可直接单测（不读 Context，路径由调用方传入）。
 */
object DiagnosticPackager {

    private const val ROOM_DB = "race_chrono_database"
    private val ROOM_FILES = listOf(ROOM_DB, "$ROOM_DB-wal", "$ROOM_DB-shm")
    private val LOG_FILES = listOf("debug_log.txt", "debug_log.txt.1")

    /**
     * 打包诊断数据为单个 zip。
     *
     * @param filesDir 应用 filesDir（含 telemetry/ video/ debug_log.txt）
     * @param databaseDir Room 数据库所在目录（含 race_chrono_database +wal/shm）
     * @param outDir zip 输出目录（典型 cacheDir）
     * @param nowMs 打包时间戳（zip 命名）
     * @return 生成的 zip File
     */
    fun pack(filesDir: File, databaseDir: File, outDir: File, nowMs: Long): File {
        if (!outDir.exists()) outDir.mkdirs()
        val zipFile = File(outDir, "diag_$nowMs.zip")
        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            // 1) telemetry/*.bin（全部，design Decision 4 全量）
            File(filesDir, "telemetry")
                .listFiles { f -> f.isFile && f.name.endsWith(".bin") }
                ?.sortedBy { it.name }
                ?.forEach { addFile(zos, it, "telemetry/${it.name}") }
            // 2) Room 三件套（design Decision 3：main+wal+shm，缺失跳过）
            for (name in ROOM_FILES) {
                val f = File(databaseDir, name)
                if (f.isFile) addFile(zos, f, "databases/$name")
            }
            // 3) debug_log.txt(+.1)（缺失跳过）
            for (name in LOG_FILES) {
                val f = File(filesDir, name)
                if (f.isFile) addFile(zos, f, name)
            }
            // 4) video/ 明确排除：从不遍历，自然不入包
        }
        return zipFile
    }

    private fun addFile(zos: ZipOutputStream, file: File, entryName: String) {
        zos.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { it.copyTo(zos) }
        zos.closeEntry()
    }
}
