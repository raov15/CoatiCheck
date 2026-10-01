package com.coati.checador.core.network

import com.coati.checador.core.network.dto.AttendanceSyncRequest
import com.coati.checador.core.network.dto.AttendanceSyncResponse
import com.coati.checador.core.network.dto.DeviceBrandingResponse
import com.coati.checador.core.network.dto.DeviceEnrollmentRequest
import com.coati.checador.core.network.dto.DeviceRegisterRequest
import com.coati.checador.core.network.dto.DeviceRegisterResponse
import com.coati.checador.core.network.dto.DeviceTokenRefreshResponse
import com.coati.checador.core.network.dto.DeviceTokenVerifyResponse
import com.coati.checador.core.network.dto.EmployeeSyncRequest
import com.coati.checador.core.network.dto.EmployeeSyncResponse
import com.coati.checador.core.network.dto.EmployeeWorkAssignmentsResponse
import com.coati.checador.core.network.dto.ForeignLocationSyncRequest
import com.coati.checador.core.network.dto.ForeignLocationSyncResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface CoatiApiService {

    // =========================================================
    // HEALTH
    // =========================================================

    @GET("health")
    suspend fun healthCheck():
        Map<String, String>

    // =========================================================
    // DISPOSITIVOS
    // =========================================================

    @POST("devices/register")
    suspend fun registerDevice(
        @Body request: DeviceRegisterRequest
    ): DeviceRegisterResponse

    @POST("devices/enroll")
    suspend fun enrollDevice(
        @Body request: DeviceEnrollmentRequest
    ): DeviceRegisterResponse

    @GET("devices/verify")
    suspend fun verifyToken(
        @Header("Authorization")
        bearerToken: String
    ): DeviceTokenVerifyResponse

    @POST("devices/refresh-token")
    suspend fun refreshToken(
        @Header("Authorization")
        bearerToken: String
    ): DeviceTokenRefreshResponse

    @GET("devices/branding")
    suspend fun getDeviceBranding(
        @Header("Authorization")
        bearerToken: String
    ): DeviceBrandingResponse

    // =========================================================
    // EMPLEADOS
    // =========================================================

    @POST("employees/sync")
    suspend fun syncEmployees(
        @Header("Authorization")
        bearerToken: String,

        @Body
        request: EmployeeSyncRequest
    ): EmployeeSyncResponse

    // =========================================================
    // CONFIGURACIÓN SITE / FOREIGN
    // =========================================================

    /**
     * Descarga la configuración laboral por día.
     *
     * SITE:
     * tiene un centro fijo.
     *
     * FOREIGN:
     * no tiene centro fijo y Android puede activar
     * el seguimiento de ubicación durante la jornada.
     */
    @GET("employees/work-assignments")
    suspend fun getEmployeeWorkAssignments(
        @Header("Authorization")
        bearerToken: String
    ): EmployeeWorkAssignmentsResponse

    // =========================================================
    // ASISTENCIAS
    // =========================================================

    @POST("attendance/sync")
    suspend fun syncAttendance(
        @Header("Authorization")
        bearerToken: String,

        @Body
        request: AttendanceSyncRequest
    ): AttendanceSyncResponse

    // =========================================================
    // RECORRIDO FORÁNEO
    // =========================================================

    /**
     * Sincroniza los puntos GPS capturados durante
     * la jornada de un trabajador Foráneo.
     */
    @POST("attendance/location/sync")
    suspend fun syncForeignLocations(
        @Header("Authorization")
        bearerToken: String,

        @Body
        request: ForeignLocationSyncRequest
    ): ForeignLocationSyncResponse
}