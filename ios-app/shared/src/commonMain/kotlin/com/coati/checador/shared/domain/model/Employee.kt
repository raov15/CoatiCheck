package com.coati.checador.shared.domain.model

/**
 * Modelo de dominio para un empleado registrado en el dispositivo.
 */
data class Employee(
    val idLocal: String,
    val idRemote: String? = null,
    val employeeCode: String,
    val firstName: String,
    val lastNamePaternal: String,
    val lastNameMaternal: String,
    val fullName: String,
    val rfc: String,
    val curp: String,
    val nss: String,
    val department: String,
    val workStartTime: String = "08:00",
    val workEndTime: String = "17:00",
    val lateToleranceMinutes: Int = 10,
    val lateCount: Int = 0,
    val absenceCount: Int = 0,
    val isActive: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val syncStatus: String
)
