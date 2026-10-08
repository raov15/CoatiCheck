package com.coati.checador.shared.data.repository

import com.coati.checador.shared.common.Result
import com.coati.checador.shared.data.db.CoatiCheckDatabase
import com.coati.checador.shared.data.network.CoatiApi
import com.coati.checador.shared.data.network.dto.DeviceEnrollmentRequest
import com.coati.checador.shared.data.network.dto.DeviceRegisterRequest
import com.coati.checador.shared.domain.model.Device
import com.coati.checador.shared.domain.repository.DeviceAuthRepository
import app.cash.sqldelight.coroutines.asFlow
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Implementación del repositorio de dispositivos sobre SQLDelight + CoatiApi.
 *
 * El auth_token del backend NO se persiste en texto plano en la BD:
 * se guarda en el almacén cifrado de plataforma (Keychain /
 * EncryptedSharedPreferences) mediante [TokenStore]; en la tabla devices
 * solo queda la marca de registro (id_remote).
 */
class DeviceAuthRepositoryImpl(
    private val db: CoatiCheckDatabase,
    private val api: CoatiApi,
    private val tokenStore: com.coati.checador.shared.domain.sync.TokenStore
) : DeviceAuthRepository {

    override fun observeDevice(): Flow<Device?> =
        db.devices.observeCurrent().asFlow().map { query ->
            query.executeAsOneOrNull()?.toDomain(tokenStore.getBearer())
        }

    override suspend fun getCurrentDevice(): Device? =
        db.devices.getCurrent().executeAsOneOrNull()?.toDomain(tokenStore.getBearer())

    override suspend fun createLocalDevice(deviceName: String): Device {
        val localId = "dev-" + Random.nextLong(1_000_000_000L, 9_999_999_999L)
        val now = nowMillis()
        db.devices.insertOrReplace(
            id_local = localId,
            id_remote = null,
            device_name = deviceName,
            site_id = null,
            auth_token = null,
            registered_at = now
        )
        return Device(
            idLocal = localId,
            idRemote = null,
            deviceName = deviceName,
            siteId = null,
            authToken = null,
            registeredAt = now
        )
    }

    override suspend fun registerWithBackend(
        deviceName: String,
        deviceFingerprint: String,
        apiBaseUrl: String
    ): Result<Device> {
        val current = db.devices.getCurrent().executeAsOneOrNull()
            ?: return Result.Error(
                IllegalStateException("Dispositivo local no creado"),
                "El dispositivo local no existe; llama a createLocalDevice primero."
            )
        return try {
            val response = api.registerDevice(
                DeviceRegisterRequest(
                    deviceName = deviceName,
                    deviceFingerprint = deviceFingerprint,
                    localId = current.id_local
                )
            )
            persistRegistration(current.id_local, response.deviceId, response.authToken, response.siteId)
            Result.Success(currentDeviceOrThrow())
        } catch (e: Exception) {
            Result.Error(e, "No se pudo registrar el dispositivo: ${e.message}")
        }
    }

    override suspend fun enrollWithBackend(
        deviceName: String,
        deviceFingerprint: String,
        enrollmentCode: String,
        apiBaseUrl: String
    ): Result<Device> {
        val current = db.devices.getCurrent().executeAsOneOrNull()
            ?: return Result.Error(
                IllegalStateException("Dispositivo local no creado"),
                "El dispositivo local no existe; llama a createLocalDevice primero."
            )
        return try {
            val response = api.enrollDevice(
                DeviceEnrollmentRequest(
                    deviceName = deviceName,
                    deviceFingerprint = deviceFingerprint,
                    localId = current.id_local,
                    enrollmentCode = enrollmentCode
                )
            )
            persistRegistration(current.id_local, response.deviceId, response.authToken, response.siteId)
            Result.Success(currentDeviceOrThrow())
        } catch (e: Exception) {
            Result.Error(e, "No se pudo enrolar el dispositivo: ${e.message}")
        }
    }

    override suspend fun refreshToken(apiBaseUrl: String): Result<String> {
        val bearer = tokenStore.getBearer()
            ?: return Result.Error(IllegalStateException("Sin token"), "El dispositivo no está registrado.")
        return try {
            val response = api.refreshToken(bearer)
            tokenStore.setBearer("Bearer ${response.authToken}")
            Result.Success(response.authToken)
        } catch (e: Exception) {
            Result.Error(e, "No se pudo refrescar el token: ${e.message}")
        }
    }

    override suspend fun verifyToken(apiBaseUrl: String): Result<Boolean> {
        val bearer = tokenStore.getBearer()
            ?: return Result.Success(false)
        return try {
            val response = api.verifyToken(bearer)
            Result.Success(response.valid)
        } catch (e: Exception) {
            Result.Error(e, "No se pudo verificar el token: ${e.message}")
        }
    }

    /** Persiste id_remote + site y guarda el token cifrado en el TokenStore. */
    private suspend fun persistRegistration(
        idLocal: String,
        idRemote: String,
        authToken: String,
        siteId: String?
    ) {
        db.devices.updateAuth(
            id_remote = idRemote,
            auth_token = "SECURE_STORE", // el token real vive en TokenStore
            id_local = idLocal
        )
        if (siteId != null) {
            db.devices.updateSiteId(site_id = siteId, id_local = idLocal)
        }
        tokenStore.setBearer("Bearer $authToken")
    }

    private fun currentDeviceOrThrow(): Device =
        db.devices.getCurrent().executeAsOneOrNull()!!.toDomain(tokenStore.getBearer())

    private fun com.coati.checador.shared.data.db.Devices.toDomain(bearer: String?): Device =
        Device(
            idLocal = id_local,
            idRemote = id_remote,
            deviceName = device_name,
            siteId = site_id,
            authToken = bearer?.removePrefix("Bearer "),
            registeredAt = registered_at
        )

    private fun nowMillis(): Long =
        kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
}
