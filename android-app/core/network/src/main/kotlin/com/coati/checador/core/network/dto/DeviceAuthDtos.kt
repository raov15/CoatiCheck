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
    @SerialName("site_id") val siteId: String? = null,
    @SerialName("company_id") val companyId: String? = null,
    val branding: DeviceBrandingResponse? = null
)

@Serializable
data class DeviceBrandingResponse(
    val id: String,
    val name: String,
    val slug: String,
    val logo_path: String? = null
)

@Serializable
data class DeviceEnrollmentRequest(
    val device_name: String,
    val device_fingerprint: String,
    val local_id: String,
    val enrollment_code: String
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
