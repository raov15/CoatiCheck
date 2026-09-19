package com.coati.checador.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.coati.checador.core.database.dao.AppSettingDao
import com.coati.checador.core.database.dao.AttendanceRecordDao
import com.coati.checador.core.database.dao.DeviceDao
import com.coati.checador.core.database.dao.EmployeeDao
import com.coati.checador.core.database.dao.EmployeeFaceProfileDao
import com.coati.checador.core.database.dao.SyncQueueDao
import com.coati.checador.core.database.entity.AppSettingEntity
import com.coati.checador.core.database.entity.AttendanceRecordEntity
import com.coati.checador.core.database.entity.DeviceEntity
import com.coati.checador.core.database.entity.EmployeeEntity
import com.coati.checador.core.database.entity.EmployeeFaceProfileEntity
import com.coati.checador.core.database.entity.SyncQueueEntity

@Database(
    entities = [
        EmployeeEntity::class,
        EmployeeFaceProfileEntity::class,
        AttendanceRecordEntity::class,
        SyncQueueEntity::class,
        AppSettingEntity::class,
        DeviceEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class CoatiDatabase : RoomDatabase() {

    abstract fun employeeDao(): EmployeeDao

    abstract fun employeeFaceProfileDao(): EmployeeFaceProfileDao

    abstract fun attendanceRecordDao(): AttendanceRecordDao

    abstract fun syncQueueDao(): SyncQueueDao

    abstract fun appSettingDao(): AppSettingDao

    abstract fun deviceDao(): DeviceDao

    companion object {

        // =========================================================
        // MIGRACIÓN 1 -> 2
        // =========================================================

        val MIGRATION_1_2 =
            object : Migration(1, 2) {

                override fun migrate(
                    database: SupportSQLiteDatabase
                ) {

                    database.execSQL(
                        """
                        ALTER TABLE attendance_records
                        ADD COLUMN site_id TEXT
                        """.trimIndent()
                    )
                }
            }

        // =========================================================
        // MIGRACIÓN 2 -> 3
        // =========================================================

        val MIGRATION_2_3 =
            object : Migration(2, 3) {

                override fun migrate(
                    database: SupportSQLiteDatabase
                ) {

                    database.execSQL(
                        """
                        ALTER TABLE attendance_records
                        ADD COLUMN is_late INTEGER NOT NULL DEFAULT 0
                        """.trimIndent()
                    )

                    database.execSQL(
                        """
                        ALTER TABLE attendance_records
                        ADD COLUMN late_minutes INTEGER NOT NULL DEFAULT 0
                        """.trimIndent()
                    )

                    database.execSQL(
                        """
                        CREATE INDEX IF NOT EXISTS
                        index_attendance_records_employee_id_is_late
                        ON attendance_records(employee_id, is_late)
                        """.trimIndent()
                    )
                }
            }

        // =========================================================
        // MIGRACIÓN 3 -> 4
        // =========================================================

        val MIGRATION_3_4 =
            object : Migration(3, 4) {

                override fun migrate(
                    database: SupportSQLiteDatabase
                ) {

                    database.execSQL(
                        """
                        ALTER TABLE employees
                        ADD COLUMN work_start_time TEXT NOT NULL DEFAULT '08:00'
                        """.trimIndent()
                    )

                    database.execSQL(
                        """
                        ALTER TABLE employees
                        ADD COLUMN work_end_time TEXT NOT NULL DEFAULT '17:00'
                        """.trimIndent()
                    )

                    database.execSQL(
                        """
                        ALTER TABLE employees
                        ADD COLUMN late_tolerance_minutes INTEGER NOT NULL DEFAULT 10
                        """.trimIndent()
                    )

                    database.execSQL(
                        """
                        ALTER TABLE employees
                        ADD COLUMN late_count INTEGER NOT NULL DEFAULT 0
                        """.trimIndent()
                    )

                    database.execSQL(
                        """
                        ALTER TABLE employees
                        ADD COLUMN absence_count INTEGER NOT NULL DEFAULT 0
                        """.trimIndent()
                    )
                }
            }
    }
}