package com.coati.checador.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.coati.checador.core.database.entity.SyncQueueEntity

@Dao
interface SyncQueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SyncQueueEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SyncQueueEntity>)

    @Query("SELECT * FROM sync_queue WHERE id = :id")
    suspend fun findById(id: String): SyncQueueEntity?

    @Query("""
        SELECT * FROM sync_queue
        WHERE (next_retry_at IS NULL OR next_retry_at <= :currentTimeMs)
        AND attempts < 10
        ORDER BY created_at ASC
        LIMIT :limit
    """)
    suspend fun getNextBatch(limit: Int, currentTimeMs: Long): List<SyncQueueEntity>

    @Query("""
        UPDATE sync_queue
        SET attempts = attempts + 1,
            next_retry_at = :nextRetryAt,
            last_error = :error
        WHERE id = :id
    """)
    suspend fun recordAttemptFailure(id: String, nextRetryAt: Long, error: String?)

    @Query("DELETE FROM sync_queue WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM sync_queue WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<String>)

    @Query("SELECT COUNT(*) FROM sync_queue WHERE attempts < 10")
    suspend fun countPending(): Int

    @Query("DELETE FROM sync_queue WHERE entity_id = :entityId AND entity_type = :entityType")
    suspend fun deleteByEntity(entityId: String, entityType: String)
}
