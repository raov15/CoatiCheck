package com.coati.checador.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ForeignLocationPointDto(

    @SerialName("id_local")
    val idLocal: String,

    @SerialName("employee_id")
    val employeeId: String,

    @SerialName("work_date")
    val workDate: String,

    @SerialName("occurred_at")
    val occurredAt: Long,

    @SerialName("latitude")
    val latitude: Double,

    @SerialName("longitude")
    val longitude: Double,

    @SerialName("accuracy_m")
    val accuracyM: Float? = null,

    @SerialName("altitude_m")
    val altitudeM: Double? = null,

    @SerialName("device_id")
    val deviceId: String? = null
)

@Serializable
data class ForeignLocationSyncRequest(

    @SerialName("points")
    val points: List<ForeignLocationPointDto>
)

@Serializable
data class ForeignLocationSyncedItem(

    @SerialName("id_local")
    val idLocal: String,

    @SerialName("id_remote")
    val idRemote: String
)

@Serializable
data class ForeignLocationSyncErrorItem(

    @SerialName("id_local")
    val idLocal: String,

    @SerialName("error")
    val error: String
)

@Serializable
data class ForeignLocationSyncResponse(

    @SerialName("synced")
    val synced: List<ForeignLocationSyncedItem> = emptyList(),

    @SerialName("errors")
    val errors: List<ForeignLocationSyncErrorItem> = emptyList()
)