package com.coati.checador.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class EmployeeWorkAssignmentDto(
    val employee_id_remote: String,
    val employee_id_local: String,
    val employee_code: String,
    val weekday: Int,
    val work_mode: String,
    val site_id: String? = null,
    val site_name: String? = null
)

@Serializable
data class EmployeeWorkAssignmentsResponse(
    val assignments: List<EmployeeWorkAssignmentDto>
)