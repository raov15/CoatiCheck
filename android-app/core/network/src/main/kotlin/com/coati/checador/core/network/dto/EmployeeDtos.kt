package com.coati.checador.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Datos del empleado que se sincronizan con el servidor.
 *
 * IMPORTANTE:
 *
 * Aquí solamente se mandan los datos propios del empleado.
 *
 * NO se manda:
 * - horario de entrada
 * - horario de salida
 * - centro de trabajo
 *
 * El horario y el centro de trabajo se asignan posteriormente
 * desde la configuración administrativa.
 */
@Serializable
data class EmployeeDto(

    @SerialName("id_local")
    val idLocal: String,

    @SerialName("employee_code")
    val employeeCode: String,

    // =========================================================
    // NOMBRE SEPARADO
    // =========================================================

    @SerialName("first_name")
    val firstName: String,

    @SerialName("last_name_paternal")
    val lastNamePaternal: String,

    @SerialName("last_name_maternal")
    val lastNameMaternal: String,

    /**
     * Se conserva para compatibilidad con el servidor,
     * pero los nombres también viajan separados.
     */
    @SerialName("full_name")
    val fullName: String,

    // =========================================================
    // DATOS PERSONALES
    // =========================================================

    @SerialName("rfc")
    val rfc: String,

    @SerialName("curp")
    val curp: String,

    @SerialName("nss")
    val nss: String,

    // =========================================================
    // CARGO
    // =========================================================

    /**
     * Cargo o departamento.
     *
     * Ejemplo:
     * Desarrollador web
     *
     * Esto NO es el centro de trabajo.
     */
    @SerialName("department")
    val department: String,

    // =========================================================
    // ESTADO
    // =========================================================

    @SerialName("is_active")
    val isActive: Boolean
)

/**
 * Petición utilizada para sincronizar uno o varios empleados.
 */
@Serializable
data class EmployeeSyncRequest(

    @SerialName("employees")
    val employees: List<EmployeeDto>
)

/**
 * Empleado sincronizado correctamente.
 */
@Serializable
data class EmployeeSyncedDto(

    @SerialName("id_local")
    val idLocal: String,

    @SerialName("id_remote")
    val idRemote: String
)

/**
 * Error individual durante la sincronización.
 */
@Serializable
data class EmployeeSyncErrorDto(

    @SerialName("id_local")
    val idLocal: String,

    @SerialName("error")
    val error: String
)

/**
 * Respuesta del servidor al sincronizar empleados.
 */
@Serializable
data class EmployeeSyncResponse(

    @SerialName("synced")
    val synced: List<EmployeeSyncedDto> =
        emptyList(),

    @SerialName("errors")
    val errors: List<EmployeeSyncErrorDto> =
        emptyList()
)