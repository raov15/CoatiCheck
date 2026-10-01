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
import com.coati.checador.core.database.dao.EmployeeWorkSiteDao
import com.coati.checador.core.database.dao.ForeignLocationPointDao
import com.coati.checador.core.database.dao.SyncQueueDao
import com.coati.checador.core.database.entity.AppSettingEntity
import com.coati.checador.core.database.entity.AttendanceRecordEntity
import com.coati.checador.core.database.entity.DeviceEntity
import com.coati.checador.core.database.entity.EmployeeEntity
import com.coati.checador.core.database.entity.EmployeeFaceProfileEntity
import com.coati.checador.core.database.entity.EmployeeWorkSiteEntity
import com.coati.checador.core.database.entity.ForeignLocationPointEntity
import com.coati.checador.core.database.entity.SyncQueueEntity

@Database(
    entities = [
        EmployeeEntity::class,
        EmployeeFaceProfileEntity::class,
        AttendanceRecordEntity::class,
        SyncQueueEntity::class,
        AppSettingEntity::class,
        DeviceEntity::class,
        ForeignLocationPointEntity::class,
        EmployeeWorkSiteEntity::class
    ],
    version = 6,
    exportSchema = true
)
abstract class CoatiDatabase : RoomDatabase() {

    abstract fun employeeDao(): EmployeeDao

    abstract fun employeeFaceProfileDao(): EmployeeFaceProfileDao

    abstract fun attendanceRecordDao(): AttendanceRecordDao

    abstract fun syncQueueDao(): SyncQueueDao

    abstract fun appSettingDao(): AppSettingDao

    abstract fun deviceDao(): DeviceDao

    abstract fun foreignLocationPointDao(): ForeignLocationPointDao

    abstract fun employeeWorkSiteDao(): EmployeeWorkSiteDao

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

        // =========================================================
        // MIGRACIÓN 4 -> 5
        // RECORRIDOS DE TRABAJADORES FORÁNEOS
        // =========================================================

        val MIGRATION_4_5 =
            object : Migration(4, 5) {

                override fun migrate(
                    database: SupportSQLiteDatabase
                ) {

                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS foreign_location_points (
                            id_local TEXT NOT NULL PRIMARY KEY,
                            id_remote TEXT,
                            employee_id TEXT NOT NULL,
                            work_date TEXT NOT NULL,
                            occurred_at INTEGER NOT NULL,
                            latitude REAL NOT NULL,
                            longitude REAL NOT NULL,
                            accuracy_m REAL,
                            altitude_m REAL,
                            device_id TEXT,
                            sync_status TEXT NOT NULL DEFAULT 'PENDING',
                            sync_attempts INTEGER NOT NULL DEFAULT 0,
                            last_error TEXT,
                            created_at INTEGER NOT NULL,
                            FOREIGN KEY(employee_id)
                                REFERENCES employees(id_local)
                                ON UPDATE NO ACTION
                                ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )

                    database.execSQL(
                        """
                        CREATE INDEX IF NOT EXISTS
                        index_foreign_location_points_employee_id_occurred_at
                        ON foreign_location_points(
                            employee_id,
                            occurred_at
                        )
                        """.trimIndent()
                    )

                    database.execSQL(
                        """
                        CREATE INDEX IF NOT EXISTS
                        index_foreign_location_points_sync_status_sync_attempts
                        ON foreign_location_points(
                            sync_status,
                            sync_attempts
                        )
                        """.trimIndent()
                    )

                    database.execSQL(
                        """
                        CREATE INDEX IF NOT EXISTS
                        index_foreign_location_points_work_date
                        ON foreign_location_points(
                            work_date
                        )
                        """.trimIndent()
                    )
                }
            }

        // =========================================================
        // MIGRACIÓN 5 -> 6
        // CONFIGURACIÓN SITE / FOREIGN POR DÍA
        // =========================================================

        val MIGRATION_5_6 =
            object : Migration(5, 6) {

                override fun migrate(
                    database: SupportSQLiteDatabase
                ) {

                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS employee_work_sites (
                            employee_id TEXT NOT NULL,
                            weekday INTEGER NOT NULL,
                            work_mode TEXT NOT NULL,
                            site_id TEXT,
                            site_name TEXT,
                            updated_at INTEGER NOT NULL,
                            PRIMARY KEY(employee_id, weekday),
                            FOREIGN KEY(employee_id)
                                REFERENCES employees(id_local)
                                ON UPDATE NO ACTION
                                ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )

                    database.execSQL(
                        """
                        CREATE INDEX IF NOT EXISTS
                        index_employee_work_sites_employee_id
                        ON employee_work_sites(employee_id)
                        """.trimIndent()
                    )

                    database.execSQL(
                        """
                        CREATE INDEX IF NOT EXISTS
                        index_employee_work_sites_work_mode
                        ON employee_work_sites(work_mode)
                        """.trimIndent()
                    )
                }
            }
    }
}