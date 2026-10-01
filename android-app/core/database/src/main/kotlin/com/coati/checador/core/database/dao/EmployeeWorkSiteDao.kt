package com.coati.checador.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.coati.checador.core.database.entity.EmployeeWorkSiteEntity

@Dao
interface EmployeeWorkSiteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(
        assignments: List<EmployeeWorkSiteEntity>
    )

    @Query(
        """
        SELECT *
        FROM employee_work_sites
        WHERE employee_id = :employeeId
          AND weekday = :weekday
        LIMIT 1
        """
    )
    suspend fun getForDay(
        employeeId: String,
        weekday: Int
    ): EmployeeWorkSiteEntity?

    @Query(
        """
        SELECT *
        FROM employee_work_sites
        WHERE employee_id = :employeeId
        ORDER BY weekday
        """
    )
    suspend fun getForEmployee(
        employeeId: String
    ): List<EmployeeWorkSiteEntity>

    @Query(
        """
        DELETE FROM employee_work_sites
        WHERE employee_id = :employeeId
        """
    )
    suspend fun deleteForEmployee(
        employeeId: String
    )

    @Query("DELETE FROM employee_work_sites")
    suspend fun deleteAll()
}