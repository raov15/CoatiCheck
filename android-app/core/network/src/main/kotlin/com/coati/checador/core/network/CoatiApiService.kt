package com.coati.checador.core.network

import com.coati.checador.core.network.dto.AttendanceSyncRequest
import com.coati.checador.core.network.dto.AttendanceSyncResponse
import com.coati.checador.core.network.dto.DeviceRegisterRequest
import com.coati.checador.core.network.dto.DeviceRegisterResponse
import com.coati.checador.core.network.dto.DeviceTokenRefreshResponse
import com.coati.checador.core.network.dto.DeviceTokenVerifyResponse
import com.coati.checador.core.network.dto.DeviceBrandingResponse
import com.coati.checador.core.network.dto.DeviceEnrollmentRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface CoatiApiService {

    @GET("health")
    suspend fun healthCheck(): Map<String, String>

    // ── Device Auth ──────────────────────────────────────────────────────────

    /** Registra el dispositivo y obtiene su auth_token de larga duracion. */
    @POST("devices/register")
    suspend fun registerDevice(
        @Body request: DeviceRegisterRequest
    ): DeviceRegisterResponse

    @POST("devices/enroll")
    suspend fun enrollDevice(
        @Body request: DeviceEnrollmentRequest
    ): DeviceRegisterResponse

    /** Verifica si el token sigue siendo valido. */
    @GET("devices/verify")
    suspend fun verifyToken(
        @Header("Authorization") bearerToken: String
    ): DeviceTokenVerifyResponse

    /** Refresca el token del dispositivo. */
    @POST("devices/refresh-token")
    suspend fun refreshToken(
        @Header("Authorization") bearerToken: String
    ): DeviceTokenRefreshResponse

    @GET("devices/branding")
    suspend fun getDeviceBranding(
        @Header("Authorization") bearerToken: String
    ): DeviceBrandingResponse

    // ── Attendance Sync ──────────────────────────────────────────────────────

    /** Sincroniza un lote de registros de asistencia pendientes. */
    @POST("attendance/sync")
    suspend fun syncAttendance(
        @Header("Authorization") bearerToken: String,
        @Body request: AttendanceSyncRequest
    ): AttendanceSyncResponse
}
