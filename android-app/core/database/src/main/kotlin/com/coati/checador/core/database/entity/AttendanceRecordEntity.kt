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
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["sync_status", "sync_attempts"]),
        Index(value = ["employee_id", "occurred_at"]),
        Index(value = ["employee_id", "is_late"])
    ]
)
data class AttendanceRecordEntity(

    @PrimaryKey
    @ColumnInfo(name = "id_local")
    val idLocal: String,

    @ColumnInfo(name = "id_remote")
    val idRemote: String? = null,

    @ColumnInfo(name = "employee_id")
    val employeeId: String,

    /**
     * CLOCK_IN
     * CLOCK_OUT
     * MEAL_START
     * MEAL_END
     */
    @ColumnInfo(name = "event_type")
    val eventType: String,

    /**
     * Momento real en que se realizó el registro.
     * Epoch milliseconds.
     */
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Long,

    // =========================================================
    // RETARDOS
    // =========================================================

    /**
     * true solamente cuando:
     *
     * eventType == CLOCK_IN
     *
     * y la persona registra su entrada después
     * de los minutos de tolerancia configurados.
     *
     * Ejemplo:
     *
     * entrada:    08:00
     * tolerancia: 10 minutos
     *
     * 08:10 -> false
     * 08:11 -> true
     */
    @ColumnInfo(name = "is_late")
    val isLate: Boolean = false,

    /**
     * Minutos transcurridos después de la hora
     * oficial de entrada.
     *
     * Ejemplo:
     *
     * horario: 08:00
     * marca:   08:11
     *
     * lateMinutes = 11
     *
     * Si llegó a tiempo:
     *
     * lateMinutes = 0
     */
    @ColumnInfo(name = "late_minutes")
    val lateMinutes: Int = 0,

    // =========================================================
    // UBICACIÓN
    // =========================================================

    @ColumnInfo(name = "latitude")
    val latitude: Double? = null,

    @ColumnInfo(name = "longitude")
    val longitude: Double? = null,

    @ColumnInfo(name = "accuracy_m")
    val accuracyM: Float? = null,

    @ColumnInfo(name = "altitude_m")
    val altitudeM: Double? = null,

    // =========================================================
    // RECONOCIMIENTO FACIAL
    // =========================================================

    @ColumnInfo(name = "face_confidence")
    val faceConfidence: Float? = null,

    // =========================================================
    // DISPOSITIVO / CENTRO DE TRABAJO
    // =========================================================

    @ColumnInfo(name = "device_id")
    val deviceId: String? = null,

    @ColumnInfo(name = "site_id")
    val siteId: String? = null,

    // =========================================================
    // SINCRONIZACIÓN
    // =========================================================

    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SyncStatus.PENDING,

    @ColumnInfo(name = "sync_attempts")
    val syncAttempts: Int = 0,

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)