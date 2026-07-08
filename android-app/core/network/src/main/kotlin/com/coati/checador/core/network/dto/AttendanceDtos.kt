package com.coati.checador.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AttendanceRecordDto(
    @SerialName("id_local") val idLocal: String,
    @SerialName("employee_id") val employeeId: String,
    @SerialName("event_type") val eventType: String,
    @SerialName("occurred_at") val occurredAt: Long,
    @SerialName("latitude") val latitude: Double? = null,
    @SerialName("longitude") val longitude: Double? = null,
    @SerialName("accuracy_m") val accuracyM: Float? = null,
    @SerialName("altitude_m") val altitudeM: Double? = null,
    @SerialName("face_confidence") val faceConfidence: Float? = null
)

@Serializable
data class AttendanceSyncRequest(
    @SerialName("records") val records: List<AttendanceRecordDto>
)

@Serializable
data class AttendanceSyncedItem(
    @SerialName("id_local") val idLocal: String,
    @SerialName("id_remote") val idRemote: String
)

@Serializable
data class AttendanceSyncErrorItem(
    @SerialName("id_local") val idLocal: String,
    @SerialName("error") val error: String
)

@Serializable
data class AttendanceSyncResponse(
    @SerialName("synced") val synced: List<AttendanceSyncedItem> = emptyList(),
    @SerialName("errors") val errors: List<AttendanceSyncErrorItem> = emptyList()
)
