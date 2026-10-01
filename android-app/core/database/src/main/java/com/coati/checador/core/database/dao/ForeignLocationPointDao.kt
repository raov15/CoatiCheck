package com.coati.checador.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.coati.checador.core.database.entity.ForeignLocationPointEntity

@Dao
interface ForeignLocationPointDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(
        point: ForeignLocationPointEntity
    )

    @Query(
        """
        SELECT *
        FROM foreign_location_points
        WHERE sync_status IN ('PENDING', 'ERROR')
        ORDER BY occurred_at ASC
        LIMIT :limit
        """
    )
    suspend fun getPendingBatch(
        limit: Int
    ): List<ForeignLocationPointEntity>

    @Query(
        """
        UPDATE foreign_location_points
        SET sync_status = 'SYNCING'
        WHERE id_local IN (:ids)
        """
    )
    suspend fun markAsSyncing(
        ids: List<String>
    )

    @Query(
        """
        UPDATE foreign_location_points
        SET
            sync_status = :status,
            id_remote = :idRemote,
            last_error = NULL
        WHERE id_local = :idLocal
        """
    )
    suspend fun markSynced(
        idLocal: String,
        status: String,
        idRemote: String?
    )

    @Query(
        """
        UPDATE foreign_location_points
        SET
            sync_status = :status,
            sync_attempts = sync_attempts + 1,
            last_error = :error
        WHERE id_local = :idLocal
        """
    )
    suspend fun markSyncFailed(
        idLocal: String,
        status: String,
        error: String?
    )

    @Query(
        """
        SELECT COUNT(*)
        FROM foreign_location_points
        WHERE sync_status IN ('PENDING', 'ERROR')
        """
    )
    suspend fun countPending(): Int

    @Query(
        """
        SELECT *
        FROM foreign_location_points
        WHERE employee_id = :employeeId
          AND work_date = :workDate
        ORDER BY occurred_at ASC
        """
    )
    suspend fun getRouteForDay(
        employeeId: String,
        workDate: String
    ): List<ForeignLocationPointEntity>
}