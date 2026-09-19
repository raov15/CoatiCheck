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

    /**
     * Sincroniza los empleados registrados localmente.
     *
     * EmployeeSyncRequest ya debe enviar:
     *
     * - employee_code
     * - first_name
     * - last_name_paternal
     * - last_name_maternal
     * - full_name
     * - rfc
     * - curp
     * - nss
     * - department
     * - is_active
     *
     * El centro de trabajo NO se envía aquí.
     * Se asigna posteriormente desde la configuración web.
     */
    @POST("employees/sync")
    suspend fun syncEmployees(
        @Header("Authorization")
        bearerToken: String,

        @Body
        request: EmployeeSyncRequest
    ): EmployeeSyncResponse

    // =========================================================
    // ASISTENCIAS
    // =========================================================

    /**
     * Sincroniza entradas, salidas, comidas y demás
     * registros de asistencia.
     */
    @POST("attendance/sync")
    suspend fun syncAttendance(
        @Header("Authorization")
        bearerToken: String,

        @Body
        request: AttendanceSyncRequest
    ): AttendanceSyncResponse
}