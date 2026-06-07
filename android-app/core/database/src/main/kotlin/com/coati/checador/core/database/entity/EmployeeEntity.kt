package com.coati.checador.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.coati.checador.core.database.model.SyncStatus

@Entity(
    tableName = "employees",
    indices = [Index(value = ["sync_status"])]
)
data class EmployeeEntity(
    @PrimaryKey
    @ColumnInfo(name = "id_local")
    val idLocal: String,                    // UUID generado en dispositivo

    @ColumnInfo(name = "id_remote")
    val idRemote: String? = null,           // ID del servidor (null hasta primera sync)

    @ColumnInfo(name = "employee_code")
    val employeeCode: String,

    @ColumnInfo(name = "full_name")
    val fullName: String,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,                    // epoch ms UTC

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SyncStatus.PENDING  // pending | synced | error
)
