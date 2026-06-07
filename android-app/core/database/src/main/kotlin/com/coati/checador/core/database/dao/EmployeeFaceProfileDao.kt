package com.coati.checador.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.coati.checador.core.database.entity.EmployeeFaceProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EmployeeFaceProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: EmployeeFaceProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(profiles: List<EmployeeFaceProfileEntity>)

    @Query("SELECT * FROM employee_face_profiles WHERE employee_id = :employeeId AND is_active = 1")
    suspend fun getActiveByEmployee(employeeId: String): List<EmployeeFaceProfileEntity>

    @Query("SELECT * FROM employee_face_profiles WHERE employee_id = :employeeId AND is_active = 1")
    fun observeActiveByEmployee(employeeId: String): Flow<List<EmployeeFaceProfileEntity>>

    @Query("""
        SELECT efp.* FROM employee_face_profiles efp
        INNER JOIN employees e ON e.id_local = efp.employee_id
        WHERE e.is_active = 1 AND efp.is_active = 1
    """)
    suspend fun getAllActiveForRecognition(): List<EmployeeFaceProfileEntity>

    @Query("SELECT COUNT(*) FROM employee_face_profiles WHERE employee_id = :employeeId AND is_active = 1")
    suspend fun countActiveByEmployee(employeeId: String): Int

    @Query("UPDATE employee_face_profiles SET is_active = 0 WHERE employee_id = :employeeId")
    suspend fun deactivateAllForEmployee(employeeId: String)

    @Query("UPDATE employee_face_profiles SET is_active = 0 WHERE id = :id")
    suspend fun deactivate(id: String)

    @Query("DELETE FROM employee_face_profiles WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM employee_face_profiles WHERE employee_id = :employeeId")
    suspend fun deleteAllForEmployee(employeeId: String)
}
