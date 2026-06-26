package com.coati.checador.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceRegisterRequest(
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_fingerprint") val deviceFingerprint: String,
    @SerialName("local_id") val localId: String
)

@Serializable
data class DeviceRegisterResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("auth_token") val authToken: String,
    @SerialName("site_id") val siteId: String? = null
)

@Serializable
data class DeviceTokenVerifyResponse(
    @SerialName("valid") val valid: Boolean,
    @SerialName("device_id") val deviceId: String? = null
)

@Serializable
data class DeviceTokenRefreshResponse(
    @SerialName("auth_token") val authToken: String
)
