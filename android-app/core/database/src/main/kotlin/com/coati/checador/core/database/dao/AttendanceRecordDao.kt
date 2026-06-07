package com.coati.checador.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.coati.checador.core.database.entity.AttendanceRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttendanceRecordDao {

    // Los registros son append-only: solo INSERT, nunca UPDATE del contenido
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: AttendanceRecordEntity): Long

    @Query("SELECT * FROM attendance_records WHERE id_local = :idLocal")
    suspend fun findById(idLocal: String): AttendanceRecordEntity?

    @Query("""
        SELECT * FROM attendance_records
        WHERE sync_status IN ('pending', 'error') AND sync_attempts < 10
        ORDER BY occurred_at ASC
        LIMIT :limit
    """)
    suspend fun getPendingBatch(limit: Int): List<AttendanceRecordEntity>

    @Query("""
        SELECT * FROM attendance_records
        WHERE sync_status IN ('pending', 'error')
        ORDER BY occurred_at ASC
    """)
    fun observePending(): Flow<List<AttendanceRecordEntity>>

    @Query("""
        SELECT * FROM attendance_records
        WHERE employee_id = :employeeId
        ORDER BY occurred_at DESC
    """)
    fun observeByEmployee(employeeId: String): Flow<List<AttendanceRecordEntity>>

    @Query("""
        SELECT * FROM attendance_records
        WHERE employee_id = :employeeId
        AND occurred_at BETWEEN :fromMs AND :toMs
        ORDER BY occurred_at DESC
    """)
    suspend fun getByEmployeeAndDateRange(
        employeeId: String,
        fromMs: Long,
        toMs: Long
    ): List<AttendanceRecordEntity>

    @Query("""
        SELECT * FROM attendance_records
        ORDER BY occurred_at DESC
        LIMIT :limit
    """)
    fun observeRecent(limit: Int): Flow<List<AttendanceRecordEntity>>

    @Query("""
        UPDATE attendance_records
        SET sync_status = :status, id_remote = :idRemote, last_error = NULL
        WHERE id_local = :idLocal
    """)
    suspend fun markSynced(idLocal: String, status: String, idRemote: String?)

    @Query("""
        UPDATE attendance_records
        SET sync_status = :status,
            sync_attempts = sync_attempts + 1,
            last_error = :error
        WHERE id_local = :idLocal
    """)
    suspend fun markSyncFailed(idLocal: String, status: String, error: String?)

    @Query("""
        UPDATE attendance_records
        SET sync_status = 'syncing'
        WHERE id_local IN (:ids)
    """)
    suspend fun markAsSyncing(ids: List<String>)

    @Query("SELECT COUNT(*) FROM attendance_records WHERE sync_status IN ('pending', 'error') AND sync_attempts < 10")
    suspend fun countPending(): Int

    @Query("SELECT COUNT(*) FROM attendance_records WHERE sync_status = 'error' AND sync_attempts >= 10")
    suspend fun countPermanentErrors(): Int

    @Query("""
        SELECT * FROM attendance_records
        WHERE employee_id = :employeeId
        ORDER BY occurred_at DESC
        LIMIT 1
    """)
    suspend fun getLastEventForEmployee(employeeId: String): AttendanceRecordEntity?
}
