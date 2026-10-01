package com.coati.checador.shared.domain.model

/**
 * Registro de asistencia (entrada, salida, inicio/fin de comida).
 */
data class AttendanceRecord(
    val idLocal: String,
    val idRemote: String? = null,
    val employeeId: String,
    val eventType: String,
    val occurredAt: Long,
    val isLate: Boolean = false,
    val lateMinutes: Int = 0,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyM: Float? = null,
    val altitudeM: Double? = null,
    val faceConfidence: Float? = null,
    val deviceId: String? = null,
    val siteId: String? = null,
    val syncStatus: String,
    val syncAttempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long
)
