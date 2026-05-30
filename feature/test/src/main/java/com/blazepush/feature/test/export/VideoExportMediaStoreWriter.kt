// @IgnoreFormatCheck
package com.blazepush.feature.test.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.blazepush.feature.test.FileLogger
import java.io.File

/**
 * 导出 mp4 写入 MediaStore.Video 相册（round video-export-burned-overlay · Round B · Decision 7）。
 *
 * minSdk28 兼容分流：
 * - **API >= 29**：scoped storage —— insert(EXTERNAL_CONTENT_URI, IS_PENDING=1) 拿 content URI →
 *   `openFileDescriptor(uri, "w")` 给 `MediaMuxer(fd, format)`（API 26+ fd 构造）→ 完成 IS_PENDING=0。
 *   **0 运行时权限**。
 * - **API == 28**：legacy —— `WRITE_EXTERNAL_STORAGE` 权限（调用方点导出前请求）+ DATA 绝对路径
 *   （DIRECTORY_MOVIES/BlazePush），MediaMuxer 直接写文件路径，insert(DATA=...) 让相册可见。
 *
 * 半成品清理（spec 反例）：取消/失败 → [abort] delete pending URI（API29+）或删文件（API28）。
 *
 * @author CC
 * @description export mp4 to MediaStore Video (scoped storage 29+ / legacy 28)
 * @date 2026-05-31
 */
class VideoExportMediaStoreWriter(private val context: Context) {

    private val tag = "ExportStore"

    /** 导出目标句柄（muxer + 关联的 URI/file，供 finalize/abort）。 */
    class ExportTarget(
        val muxer: MediaMuxer,
        val uri: Uri?,        // API29+ content URI（API28 为 null）
        val legacyFile: File?, // API28 绝对路径文件（API29+ 为 null）
        internal val pfdCloser: (() -> Unit)?, // API29+ 关闭 ParcelFileDescriptor
    )

    /**
     * 准备导出目标：insert MediaStore 项 + 构造 MediaMuxer。
     *
     * @param displayName 文件名（不含路径，如 `BlazePush_<sid>_lap3_<ts>.mp4`）
     */
    fun prepare(displayName: String): ExportTarget {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            prepareScopedStorage(displayName)
        } else {
            prepareLegacy(displayName)
        }
    }

    private fun prepareScopedStorage(displayName: String): ExportTarget {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/BlazePush")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert returned null URI")
        val pfd = resolver.openFileDescriptor(uri, "w")
            ?: throw IllegalStateException("openFileDescriptor returned null for $uri")
        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        FileLogger.d(tag, "scoped insert uri=$uri name=$displayName IS_PENDING=1")
        return ExportTarget(
            muxer = muxer,
            uri = uri,
            legacyFile = null,
            pfdCloser = { runCatching { pfd.close() } },
        )
    }

    @Suppress("DEPRECATION")
    private fun prepareLegacy(displayName: String): ExportTarget {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val targetDir = File(moviesDir, "BlazePush").apply { mkdirs() }
        val file = File(targetDir, displayName)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        FileLogger.d(tag, "legacy(API28) path=${file.absolutePath}")
        return ExportTarget(muxer = muxer, uri = null, legacyFile = file, pfdCloser = null)
    }

    /** 导出完成：关闭 fd，置 IS_PENDING=0（API29+）/ insert DATA（API28）→ 相册可见。返回最终可分享 URI。 */
    fun finalizeTarget(target: ExportTarget): Uri? {
        target.pfdCloser?.invoke()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && target.uri != null) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(target.uri, values, null, null)
            FileLogger.d(tag, "scoped finalize IS_PENDING=0 uri=${target.uri}")
            target.uri
        } else {
            // API28：insert MediaStore DATA 让系统相册索引到（文件已由 MediaMuxer 写好）
            val file = target.legacyFile ?: return null
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                @Suppress("DEPRECATION")
                put(MediaStore.Video.Media.DATA, file.absolutePath)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values,
            )
            FileLogger.d(tag, "legacy finalize insert DATA=${file.absolutePath} uri=$uri")
            uri
        }
    }

    /** 取消/失败：清理半成品（delete pending URI / 删文件），不留 0 字节坏文件（spec 反例）。 */
    fun abort(target: ExportTarget) {
        target.pfdCloser?.invoke()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && target.uri != null) {
            val deleted = context.contentResolver.delete(target.uri, null, null)
            FileLogger.d(tag, "scoped abort delete uri=${target.uri} rows=$deleted")
        } else {
            val file = target.legacyFile
            val deleted = file?.delete() ?: false
            FileLogger.d(tag, "legacy abort delete file=${file?.absolutePath} ok=$deleted")
        }
    }
}
