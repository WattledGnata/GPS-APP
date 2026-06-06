// @IgnoreFormatCheck
package com.blazepush.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.blazepush.core.data.local.entity.VideoSegmentEntity

/**
 * 视频段 DAO（video-segment-schema round ②a）。
 * append 写入由 [com.blazepush.core.data.repository.TelemetryRepository.attachVideoToSession] 调用；
 * 全段 cascade 由 deleteSession / deleteSessionVideo 调用（行删除 FK CASCADE 兜底，文件显式删）。
 *
 * @author CC
 * @description video segment one-to-many DAO
 * @date 2026-06-07
 */
@Dao
interface VideoSegmentDao {

    @Insert
    suspend fun insert(entity: VideoSegmentEntity): Long

    /** 按段序升序返回该 session 全部段（②c 按 wallClock 选段的数据源）。 */
    @Query("SELECT * FROM video_segments WHERE sessionId = :sessionId ORDER BY segmentIndex ASC")
    suspend fun queryBySessionId(sessionId: String): List<VideoSegmentEntity>

    /** 现有最大段序（无行返回 null）；append 时新段 index = (max ?: -1) + 1。 */
    @Query("SELECT MAX(segmentIndex) FROM video_segments WHERE sessionId = :sessionId")
    suspend fun maxSegmentIndex(sessionId: String): Int?

    /** 显式删该 session 全部段行（deleteSessionVideo 用；deleteSession 走 FK CASCADE 兜底也可达）。 */
    @Query("DELETE FROM video_segments WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)
}
