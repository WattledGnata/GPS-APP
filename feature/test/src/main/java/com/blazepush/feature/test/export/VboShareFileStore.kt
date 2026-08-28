package com.blazepush.feature.test.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter

/** Writes share-only VBO files under a narrowly exposed cache directory. */
object VboShareFileStore {
    internal const val DIRECTORY_NAME = "shared_vbo"
    internal const val MIME_TYPE = "application/octet-stream"
    private const val RETENTION_MS = 24L * 60L * 60L * 1000L

    data class SharedFile(val file: File, val uri: Uri)

    fun writeText(context: Context, fileName: String, text: String): SharedFile =
        write(context, fileName) { writer -> writer.write(text) }

    fun write(context: Context, fileName: String, block: (BufferedWriter) -> Unit): SharedFile {
        val directory = File(context.cacheDir, DIRECTORY_NAME).apply { mkdirs() }
        require(directory.isDirectory) { "Unable to create VBO share cache" }
        cleanupExpired(directory, System.currentTimeMillis())
        val file = File(directory, safeFileName(fileName))
        try {
            OutputStreamWriter(file.outputStream(), Charsets.UTF_8).buffered().use(block)
            require(file.isFile && file.length() > 0L) { "VBO share file is empty" }
        } catch (error: Throwable) {
            runCatching { file.delete() }
            throw error
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return SharedFile(file, uri)
    }

    fun createSendIntent(context: Context, sharedFile: SharedFile): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, sharedFile.uri)
            putExtra(Intent.EXTRA_TITLE, sharedFile.file.name)
            clipData = ClipData.newUri(context.contentResolver, sharedFile.file.name, sharedFile.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    internal fun cleanupExpired(directory: File, nowMs: Long) {
        directory.listFiles()
            .orEmpty()
            .filter { file -> file.isFile && isExpired(file.lastModified(), nowMs) }
            .forEach { file -> runCatching { file.delete() } }
    }

    internal fun isExpired(lastModifiedMs: Long, nowMs: Long): Boolean =
        lastModifiedMs <= 0L || nowMs - lastModifiedMs > RETENTION_MS

    internal fun safeFileName(raw: String): String {
        val normalized = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]+"), "-")
            .trim('.', ' ')
            .ifBlank { "lap-session.vbo" }
        return if (normalized.endsWith(".vbo", ignoreCase = true)) normalized else "$normalized.vbo"
    }
}
