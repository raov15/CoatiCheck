package com.coati.checador.shared.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Datos del empleado que se sincronizan con el servidor.
 *
 * Solo viajan los datos propios del empleado; el horario y el centro
 * de trabajo se asignan posteriormente desde la configuración administrativa.
 */
@Serializable
data class EmployeeDto(
    @SerialName("id_local") val idLocal: String,
    @SerialName("employee_code") val employeeCode: String,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name_paternal") val lastNamePaternal: String,
    @SerialName("last_name_maternal") val lastNameMaternal: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("rfc") val rfc: String,
    @SerialName("curp") val curp: String,
    @SerialName("nss") val nss: String,
    @SerialName("department") val department: String,
    @SerialName("is_active") val isActive: Boolean
)

@Serializable
data class EmployeeSyncRequest(
    @SerialName("employees") val employees: List<EmployeeDto>
)

@Serializable
data class EmployeeSyncedDto(
    @SerialName("id_local") val idLocal: String,
    @SerialName("id_remote") val idRemote: String
)

@Serializable
data class EmployeeSyncErrorDto(
    @SerialName("id_local") val idLocal: String,
    @SerialName("error") val error: String
)

@Serializable
data class EmployeeSyncResponse(
    @SerialName("synced") val synced: List<EmployeeSyncedDto> = emptyList(),
    @SerialName("errors") val errors: List<EmployeeSyncErrorDto> = emptyList()
)
