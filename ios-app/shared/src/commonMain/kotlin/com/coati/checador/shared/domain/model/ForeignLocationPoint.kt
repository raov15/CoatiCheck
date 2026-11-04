package com.coati.checador.shared.domain.model

/**
 * Punto GPS capturado durante la jornada de un trabajador foráneo.
 */
data class ForeignLocationPoint(
    val idLocal: String,
    val idRemote: String? = null,
    val employeeId: String,
    val workDate: String,
    val occurredAt: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double? = null,
    val altitudeM: Double? = null,
    val deviceId: String? = null,
    val syncStatus: String,
    val syncAttempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long
)
