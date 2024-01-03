package com.guopian.app.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface RootFolderDao {
    @Query("SELECT * FROM root_folder ORDER BY id DESC LIMIT 1")
    fun observeCurrent(): Flow<RootFolderEntity?>

    @Query("SELECT * FROM root_folder ORDER BY id DESC LIMIT 1")
    suspend fun getCurrent(): RootFolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: RootFolderEntity): Long

    /** Update scan metadata only — do NOT REPLACE (REPLACE deletes row → CASCADE wipes videos). */
    @Query(
        """
        UPDATE root_folder
        SET last_scanned_at = :lastScannedAt, video_count = :videoCount
        WHERE id = :id
        """,
    )
    suspend fun updateScanStats(id: Long, lastScannedAt: Long, videoCount: Int)

    @Query("DELETE FROM root_folder")
    suspend fun clearAll()
}

@Dao
interface VideoDao {
    @Query(
        """
        SELECT * FROM video
        WHERE root_id = :rootId AND is_missing = 0
        ORDER BY date_modified DESC
        """,
    )
    fun pagingByRoot(rootId: Long): PagingSource<Int, VideoEntity>

    @RawQuery(observedEntities = [VideoEntity::class])
    fun pagingByQuery(query: SupportSQLiteQuery): PagingSource<Int, VideoEntity>

    @RawQuery(observedEntities = [VideoEntity::class])
    suspend fun queryIds(query: SupportSQLiteQuery): List<Long>

    @RawQuery(observedEntities = [VideoEntity::class])
    suspend fun countByQuery(query: SupportSQLiteQuery): Int

    @Query(
        """
        SELECT COUNT(*) FROM video
        WHERE root_id = :rootId AND is_missing = 0
        """,
    )
    fun observeCount(rootId: Long): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM video
        WHERE root_id = :rootId AND is_missing = 0
        """,
    )
    suspend fun countVisibleByRoot(rootId: Long): Int

    @Query("SELECT * FROM video WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): VideoEntity?

    @Query("SELECT * FROM video WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<VideoEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(videos: List<VideoEntity>)

    @Update
    suspend fun update(video: VideoEntity)

    @Query("UPDATE video SET mark_status = :status WHERE id IN (:ids)")
    suspend fun updateMarkStatus(ids: List<Long>, status: String)

    @Query(
        """
        UPDATE video SET width = :width, height = :height, rotation = :rotation
        WHERE id = :id AND (width <= 0 OR height <= 0)
        """,
    )
    suspend fun updateDisplaySizeIfEmpty(id: Long, width: Int, height: Int, rotation: Int)

    @Query(
        """
        UPDATE video SET duration_ms = :durationMs
        WHERE id = :id AND (duration_ms IS NULL OR duration_ms <= 0)
        """,
    )
    suspend fun updateDurationIfEmpty(id: Long, durationMs: Long)

    @Query(
        """
        UPDATE video SET
          mark_status = COALESCE(:status, mark_status),
          star = COALESCE(:star, star),
          color = CASE WHEN :clearColor = 1 THEN NULL ELSE COALESCE(:color, color) END,
          note = CASE WHEN :clearNote = 1 THEN NULL ELSE COALESCE(:note, note) END
        WHERE id IN (:ids)
        """,
    )
    suspend fun updateMarkPatch(
        ids: List<Long>,
        status: String?,
        star: Int?,
        color: String?,
        clearColor: Int,
        note: String?,
        clearNote: Int,
    )

    @Query(
        """
        SELECT mark_status AS markStatus, COUNT(*) AS cnt
        FROM video
        WHERE root_id = :rootId AND is_missing = 0
        GROUP BY mark_status
        """,
    )
    fun observeStatusCounts(rootId: Long): Flow<List<StatusCountRow>>

    @Query(
        """
        SELECT LOWER(ext) AS ext, COUNT(*) AS cnt
        FROM video
        WHERE root_id = :rootId AND is_missing = 0 AND TRIM(ext) != ''
        GROUP BY LOWER(ext)
        ORDER BY cnt DESC, ext ASC
        """,
    )
    fun observeExtCounts(rootId: Long): Flow<List<ExtCountRow>>

    @Query(
        """
        SELECT parent_sub_path AS parentSubPath, COUNT(*) AS cnt
        FROM video
        WHERE root_id = :rootId AND is_missing = 0
        GROUP BY parent_sub_path
        ORDER BY cnt DESC, parent_sub_path ASC
        LIMIT 40
        """,
    )
    fun observeFolderCounts(rootId: Long): Flow<List<FolderCountRow>>

    @Query("DELETE FROM video WHERE root_id = :rootId")
    suspend fun deleteByRoot(rootId: Long)

    @Query(
        """
        SELECT relative_path AS relativePath, mark_status AS markStatus, star AS star
        FROM video
        WHERE root_id = :rootId
          AND mark_status IN ('KEEP', 'PENDING')
        """,
    )
    suspend fun listMarkedPaths(rootId: Long): List<MarkedPathRow>

    @Query(
        """
        SELECT id FROM video
        WHERE root_id = :rootId AND is_missing = 0 AND relative_path = :relativePath
        LIMIT 1
        """,
    )
    suspend fun findIdByRelativePath(rootId: Long, relativePath: String): Long?

    @Query(
        """
        UPDATE video SET mark_status = :status, star = :star
        WHERE root_id = :rootId AND relative_path = :relativePath
        """,
    )
    suspend fun updateMarkByRelativePath(
        rootId: Long,
        relativePath: String,
        status: String,
        star: Int,
    )

    @Query(
        """
        UPDATE video SET
          doc_uri = :docUri,
          document_id = :documentId,
          relative_path = :relativePath,
          parent_sub_path = :parentSubPath,
          file_name = :fileName,
          mark_status = :markStatus,
          last_scanned_at = :lastScannedAt
        WHERE id = :id
        """,
    )
    suspend fun rebindAfterMove(
        id: Long,
        docUri: String,
        documentId: String?,
        relativePath: String,
        parentSubPath: String,
        fileName: String,
        markStatus: String,
        lastScannedAt: Long,
    )
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tag ORDER BY name ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query(
        """
        SELECT t.id AS id, t.name AS name, t.color AS color, t.created_at AS createdAt,
               (
                 SELECT COUNT(*) FROM video_tag vt
                 INNER JOIN video v ON v.id = vt.video_id
                 WHERE vt.tag_id = t.id AND v.is_missing = 0
               ) AS videoCount
        FROM tag t
        ORDER BY t.name ASC
        """,
    )
    fun observeAllWithCount(): Flow<List<TagWithCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: TagEntity): Long

    @Query("DELETE FROM tag WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(crossRef: VideoTagCrossRef)

    @Query("DELETE FROM video_tag WHERE video_id = :videoId AND tag_id = :tagId")
    suspend fun unlink(videoId: Long, tagId: Long)

    @Query("DELETE FROM video_tag WHERE tag_id = :tagId")
    suspend fun unlinkAllForTag(tagId: Long)

    @Query(
        """
        SELECT t.* FROM tag t
        INNER JOIN video_tag vt ON vt.tag_id = t.id
        WHERE vt.video_id = :videoId
        ORDER BY t.name ASC
        """,
    )
    fun observeTagsForVideo(videoId: Long): Flow<List<TagEntity>>
}

@Dao
interface SmartFilterDao {
    @Query("SELECT * FROM smart_filter ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<SmartFilterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(filter: SmartFilterEntity): Long

    @Query("DELETE FROM smart_filter WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PlaybackProgressDao {
    @Query("SELECT * FROM playback_progress WHERE video_id = :videoId LIMIT 1")
    suspend fun get(videoId: Long): PlaybackProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: PlaybackProgressEntity)

    @Query("DELETE FROM playback_progress WHERE video_id = :videoId")
    suspend fun delete(videoId: Long)
}
