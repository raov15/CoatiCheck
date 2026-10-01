package com.coati.checador.shared.data.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EmployeeWorkAssignmentDto(
    @SerialName("employee_id_remote") val employeeIdRemote: String,
    @SerialName("employee_id_local") val employeeIdLocal: String,
    @SerialName("employee_code") val employeeCode: String,
    @SerialName("weekday") val weekday: Int,
    @SerialName("work_mode") val workMode: String,
    @SerialName("site_id") val siteId: String? = null,
    @SerialName("site_name") val siteName: String? = null
)

@Serializable
data class EmployeeWorkAssignmentsResponse(
    @SerialName("assignments") val assignments: List<EmployeeWorkAssignmentDto>
)
