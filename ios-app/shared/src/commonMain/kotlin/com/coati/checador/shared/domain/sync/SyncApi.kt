package com.coati.checador.shared.domain.sync

import com.coati.checador.shared.data.network.dto.AttendanceSyncRequest
import com.coati.checador.shared.data.network.dto.AttendanceSyncResponse
import com.coati.checador.shared.data.network.dto.DeviceTokenRefreshResponse
import com.coati.checador.shared.data.network.dto.EmployeeSyncRequest
import com.coati.checador.shared.data.network.dto.EmployeeSyncResponse
import com.coati.checador.shared.data.network.dto.EmployeeWorkAssignmentsResponse
import com.coati.checador.shared.data.network.dto.ForeignLocationSyncRequest
import com.coati.checador.shared.data.network.dto.ForeignLocationSyncResponse

/**
 * Puertos de red que necesita el orquestador de sincronización.
 * [com.coati.checador.shared.data.network.CoatiApi] los implementa;
 * en tests se usan fakes.
 */
interface SyncApi {
    suspend fun syncEmployees(
        bearerToken: String,
        request: EmployeeSyncRequest
    ): EmployeeSyncResponse

    suspend fun getEmployeeWorkAssignments(
        bearerToken: String
    ): EmployeeWorkAssignmentsResponse

    suspend fun syncAttendance(
        bearerToken: String,
        request: AttendanceSyncRequest
    ): AttendanceSyncResponse

    suspend fun syncForeignLocations(
        bearerToken: String,
        request: ForeignLocationSyncRequest
    ): ForeignLocationSyncResponse

    suspend fun refreshToken(bearerToken: String): DeviceTokenRefreshResponse
}

/**
 * Almacenamiento del token del dispositivo (header Authorization Bearer).
 * La implementación real lo guarda cifrado (Keychain/EncryptedSharedPreferences).
 */
interface TokenStore {
    fun getBearer(): String?
    fun setBearer(rawToken: String)
}
