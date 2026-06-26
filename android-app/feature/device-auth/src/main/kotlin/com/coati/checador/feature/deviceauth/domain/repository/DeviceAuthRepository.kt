package com.coati.checador.feature.deviceauth.domain.repository

import com.coati.checador.core.common.Result
import com.coati.checador.feature.deviceauth.domain.model.Device
import kotlinx.coroutines.flow.Flow

interface DeviceAuthRepository {

    /** Devuelve el dispositivo local almacenado, o null si aun no fue creado. */
    fun observeDevice(): Flow<Device?>

    /** Obtiene el dispositivo actual de la BD local (suspend, unico). */
    suspend fun getCurrentDevice(): Device?

    /**
     * Crea el registro local del dispositivo con un UUID generado en el dispositivo.
     * Se llama la primera vez que la app arranca sin datos previos.
     */
    suspend fun createLocalDevice(deviceName: String): Device

    /**
     * Registra el dispositivo en el backend.
     * Persiste el id_remote y el auth_token en la BD local.
     * Retorna [Result.Success] con el [Device] actualizado, o [Result.Error].
     */
    suspend fun registerWithBackend(
        deviceName: String,
        deviceFingerprint: String,
        apiBaseUrl: String
    ): Result<Device>

    /**
     * Refresca el token del dispositivo contra el backend.
     */
    suspend fun refreshToken(apiBaseUrl: String): Result<String>

    /**
     * Verifica si el token actual sigue siendo valido (ping al backend).
     */
    suspend fun verifyToken(apiBaseUrl: String): Result<Boolean>
}
