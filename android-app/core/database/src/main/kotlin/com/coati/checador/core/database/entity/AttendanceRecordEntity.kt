package com.coati.checador.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.coati.checador.core.database.model.SyncStatus

@Entity(
    tableName = "attendance_records",
    foreignKeys = [
        ForeignKey(
            entity = EmployeeEntity::class,
            parentColumns = ["id_local"],
            childColumns = ["employee_id"],
            onDelete = ForeignKey.RESTRICT   // No borrar empleados con registros
        )
    ],
    indices = [
        Index(value = ["sync_status", "sync_attempts"]),
        Index(value = ["employee_id", "occurred_at"])
    ]
)
data class AttendanceRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "id_local")
    val idLocal: String,                    // UUID v4 — funciona como idempotency_key

    @ColumnInfo(name = "id_remote")
    val idRemote: String? = null,           // asignado por servidor tras sync exitosa

    @ColumnInfo(name = "employee_id")
    val employeeId: String,                 // FK → employees.id_local

    @ColumnInfo(name = "event_type")
    val eventType: String,                  // CLOCK_IN | CLOCK_OUT | MEAL_START | MEAL_END

    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,                   // epoch ms UTC (hora local del dispositivo)

    @ColumnInfo(name = "latitude")
    val latitude: Double? = null,

    @ColumnInfo(name = "longitude")
    val longitude: Double? = null,

    @ColumnInfo(name = "accuracy_m")
    val accuracyM: Float? = null,           // precisión GPS en metros

    @ColumnInfo(name = "altitude_m")
    val altitudeM: Double? = null,

    @ColumnInfo(name = "face_confidence")
    val faceConfidence: Float? = null,      // distancia coseno del matching (0.0 = perfecto)

    @ColumnInfo(name = "device_id")
    val deviceId: String? = null,

    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SyncStatus.PENDING,   // pending | syncing | synced | error

    @ColumnInfo(name = "sync_attempts")
    val syncAttempts: Int = 0,

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long                     // epoch ms UTC
)
