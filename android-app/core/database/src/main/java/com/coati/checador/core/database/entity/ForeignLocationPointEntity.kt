package com.coati.checador.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "foreign_location_points",
    foreignKeys = [
        ForeignKey(
            entity = EmployeeEntity::class,
            parentColumns = ["id_local"],
            childColumns = ["employee_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["employee_id", "occurred_at"]),
        Index(value = ["sync_status", "sync_attempts"]),
        Index(value = ["work_date"])
    ]
)
data class ForeignLocationPointEntity(

    @PrimaryKey
    @ColumnInfo(name = "id_local")
    val idLocal: String,

    @ColumnInfo(name = "id_remote")
    val idRemote: String? = null,

    @ColumnInfo(name = "employee_id")
    val employeeId: String,

    @ColumnInfo(name = "work_date")
    val workDate: String,

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,

    @ColumnInfo(name = "latitude")
    val latitude: Double,

    @ColumnInfo(name = "longitude")
    val longitude: Double,

    @ColumnInfo(name = "accuracy_m")
    val accuracyM: Float? = null,

    @ColumnInfo(name = "altitude_m")
    val altitudeM: Double? = null,

    @ColumnInfo(name = "device_id")
    val deviceId: String? = null,

    @ColumnInfo(name = "sync_status")
    val syncStatus: String = "PENDING",

    @ColumnInfo(name = "sync_attempts")
    val syncAttempts: Int = 0,

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)