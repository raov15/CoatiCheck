package com.coati.checador.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = true
)
abstract class CoatiDatabase : RoomDatabase() {

    abstract fun employeeDao(): EmployeeDao
    abstract fun employeeFaceProfileDao(): EmployeeFaceProfileDao
    abstract fun attendanceRecordDao(): AttendanceRecordDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun appSettingDao(): AppSettingDao
    abstract fun deviceDao(): DeviceDao
}
