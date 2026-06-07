package com.coati.checador.feature.employeeenrollment.domain.model

/**
 * Modelo de dominio para un empleado registrado en el sistema.
 * Representa la información personal y laboral que se captura
 * durante el proceso de alta en la aplicación.
 */
data class Empleado(
    /** Identificador local único generado en dispositivo (UUID) */
    val idLocal: String,

    /** Código o número de empleado asignado por la empresa */
    val codigoEmpleado: String,

    /** Nombre completo del empleado */
    val nombreCompleto: String,

    /** Departamento al que pertenece */
    val departamento: String,

    /** Hora de entrada esperada en formato "HH:mm" */
    val horarioEntrada: String,

    /** Hora de salida esperada en formato "HH:mm" */
    val horarioSalida: String,

    /** Indica si el empleado está activo en el sistema */
    val activo: Boolean = true,

    /** Timestamp de creación (epoch ms UTC) */
    val creadoEn: Long,

    /** Estado de sincronización con el servidor */
    val estadoSync: String
)
