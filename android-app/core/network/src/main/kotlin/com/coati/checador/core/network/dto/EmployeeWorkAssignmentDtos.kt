package com.coati.checador.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class EmployeeWorkAssignmentDto(
    val employee_id_remote: String,
    val employee_id_local: String,
    val employee_code: String,
    val department: String? = null,
    val weekday: Int,
    val work_mode: String,
    val site_id: String? = null,
    val site_name: String? = null
)

@Serializable
data class EmployeeWorkAssignmentsResponse(
    val departments: Map<String, String> = emptyMap(),
    val exit_policies: List<ExitPolicyDto> = emptyList(),
    val assignments: List<EmployeeWorkAssignmentDto>
)
@Serializable
data class ExitPolicyDto(val employee_id_local:String,val weekday:Int,val start_time:String?=null,val end_time:String?=null,val overtime_allowed:Boolean=true)
