package com.coati.checador.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.coati.checador.core.database.entity.EmployeeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EmployeeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(employee: EmployeeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceAll(employees: List<EmployeeEntity>)

    @Update
    suspend fun update(employee: EmployeeEntity)

    @Query("SELECT * FROM employees WHERE id_local = :idLocal")
    suspend fun findById(idLocal: String): EmployeeEntity?

    @Query("SELECT * FROM employees WHERE id_remote = :idRemote LIMIT 1")
    suspend fun findByRemoteId(idRemote: String): EmployeeEntity?

    @Query("SELECT * FROM employees WHERE employee_code = :code LIMIT 1")
    suspend fun findByCode(code: String): EmployeeEntity?

    @Query("SELECT * FROM employees ORDER BY full_name ASC")
    fun observeAll(): Flow<List<EmployeeEntity>>

    @Query("SELECT * FROM employees WHERE is_active = 1 ORDER BY full_name ASC")
    fun observeAllActive(): Flow<List<EmployeeEntity>>

    @Query("SELECT * FROM employees WHERE is_active = 1 ORDER BY full_name ASC")
    suspend fun getAllActive(): List<EmployeeEntity>

    @Query("SELECT * FROM employees WHERE sync_status = :status")
    suspend fun findBySyncStatus(status: String): List<EmployeeEntity>

    @Query("""
        UPDATE employees
        SET sync_status = :status, id_remote = :idRemote, updated_at = :updatedAt
        WHERE id_local = :idLocal
    """)
    suspend fun updateSyncResult(
        idLocal: String,
        status: String,
        idRemote: String?,
        updatedAt: Long
    )

    @Query("UPDATE employees SET sync_status = :status WHERE id_local = :idLocal")
    suspend fun updateSyncStatus(idLocal: String, status: String)

    @Query("SELECT COUNT(*) FROM employees WHERE sync_status = 'pending'")
    suspend fun countPending(): Int

    @Query("SELECT COUNT(*) FROM employees WHERE is_active = 1")
    suspend fun countActive(): Int
}
