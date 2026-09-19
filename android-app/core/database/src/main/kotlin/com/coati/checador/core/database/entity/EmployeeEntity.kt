package com.coati.checador.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.coati.checador.core.database.model.SyncStatus

@Entity(
    tableName = "employees",
    indices = [
        Index(value = ["sync_status"])
    ]
)
data class EmployeeEntity(

    @PrimaryKey
    @ColumnInfo(name = "id_local")
    val idLocal: String,

    @ColumnInfo(name = "id_remote")
    val idRemote: String? = null,

    @ColumnInfo(name = "employee_code")
    val employeeCode: String,

    @ColumnInfo(name = "first_name")
    val firstName: String,

    @ColumnInfo(name = "last_name_paternal")
    val lastNamePaternal: String,

    @ColumnInfo(name = "last_name_maternal")
    val lastNameMaternal: String,

    @ColumnInfo(name = "full_name")
    val fullName: String,

    @ColumnInfo(name = "rfc")
    val rfc: String,

    @ColumnInfo(name = "curp")
    val curp: String,

    @ColumnInfo(name = "nss")
    val nss: String,

    @ColumnInfo(name = "department")
    val department: String,

    // =========================================================
    // HORARIO
    // =========================================================

    @ColumnInfo(name = "work_start_time")
    val workStartTime: String = "08:00",

    @ColumnInfo(name = "work_end_time")
    val workEndTime: String = "17:00",

    @ColumnInfo(name = "late_tolerance_minutes")
    val lateToleranceMinutes: Int = 10,

    // =========================================================
    // RETARDOS Y AUSENTISMOS
    // =========================================================

    @ColumnInfo(name = "late_count")
    val lateCount: Int = 0,

    @ColumnInfo(name = "absence_count")
    val absenceCount: Int = 0,

    // =========================================================
    // ESTADO
    // =========================================================

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SyncStatus.PENDING
)