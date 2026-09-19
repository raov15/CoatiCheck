package com.coati.checador.feature.employeeenrollment.domain.model

/**
 * Modelo de dominio para un empleado registrado en el sistema.
 */
data class Empleado(

    /**
     * Identificador local único generado en el dispositivo.
     */
    val idLocal: String,

    /**
     * Código asignado por la empresa.
     */
    val codigoEmpleado: String,

    /**
     * Nombre.
     */
    val nombre: String,

    /**
     * Apellido paterno.
     */
    val apellidoPaterno: String,

    /**
     * Apellido materno.
     */
    val apellidoMaterno: String,

    /**
     * Nombre completo.
     */
    val nombreCompleto: String,

    /**
     * RFC.
     */
    val rfc: String,

    /**
     * CURP.
     */
    val curp: String,

    /**
     * NSS.
     */
    val nss: String,

    /**
     * Departamento.
     */
    val departamento: String,

    /**
     * Hora de entrada configurada.
     */
    val horarioEntrada: String = "08:00",

    /**
     * Hora de salida configurada.
     */
    val horarioSalida: String = "17:00",

    /**
     * Minutos permitidos antes de considerar un retardo.
     */
    val toleranciaRetardo: Int = 10,

    /**
     * Número total de retardos.
     */
    val totalRetardos: Int = 0,

    /**
     * Número total de ausentismos.
     */
    val totalAusentismos: Int = 0,

    /**
     * Indica si el empleado está activo.
     */
    val activo: Boolean = true,

    /**
     * Fecha de creación.
     */
    val creadoEn: Long,

    /**
     * Estado de sincronización.
     */
    val estadoSync: String
)