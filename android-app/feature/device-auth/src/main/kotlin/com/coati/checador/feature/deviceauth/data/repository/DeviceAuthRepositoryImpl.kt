package com.coati.checador.feature.deviceauth.data.repository

import com.coati.checador.core.common.Result
import com.coati.checador.core.database.dao.AppSettingDao
import com.coati.checador.core.database.dao.DeviceDao
import com.coati.checador.core.database.entity.AppSettingEntity
import com.coati.checador.core.database.entity.DeviceEntity
import com.coati.checador.core.network.CoatiApiServiceFactory
import com.coati.checador.core.network.dto.DeviceRegisterRequest
import com.coati.checador.core.network.dto.DeviceEnrollmentRequest
import com.coati.checador.feature.deviceauth.domain.model.Device
import com.coati.checador.feature.deviceauth.domain.repository.DeviceAuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

class DeviceAuthRepositoryImpl @Inject constructor(
    private val deviceDao: DeviceDao,
    private val appSettingDao: AppSettingDao,
    private val apiServiceFactory: CoatiApiServiceFactory
) : DeviceAuthRepository {

    override fun observeDevice(): Flow<Device?> =
        deviceDao.observeCurrent().map { it?.toDomain() }

    override suspend fun getCurrentDevice(): Device? =
        deviceDao.getCurrent()?.toDomain()

    override suspend fun createLocalDevice(deviceName: String): Device {
        val idLocal = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val entity = DeviceEntity(
            idLocal = idLocal,
            deviceName = deviceName,
            registeredAt = now
        )
        deviceDao.insertOrReplace(entity)
        appSettingDao.upsert(
            AppSettingEntity(
                key = AppSettingEntity.KEY_DEVICE_ID,
                value = idLocal,
                updatedAt = now
            )
        )
        return entity.toDomain()
    }

    override suspend fun registerWithBackend(
        deviceName: String,
        deviceFingerprint: String,
        apiBaseUrl: String
    ): Result<Device> = runCatching {
        val apiService = apiServiceFactory.create(apiBaseUrl)
        val current = deviceDao.getCurrent()
            ?: throw IllegalStateException("No hay dispositivo local creado")

        val response = apiService.registerDevice(
            DeviceRegisterRequest(
                deviceName = deviceName,
                deviceFingerprint = deviceFingerprint,
                localId = current.idLocal
            )
        )

        deviceDao.updateRegistration(
            idLocal = current.idLocal,
            idRemote = response.deviceId,
            authToken = response.authToken
        )
        val siteId = response.siteId
        if (siteId != null) {
            deviceDao.updateSiteId(current.idLocal, siteId)
        }

        // Persistir token en DataStore tambien
        appSettingDao.upsert(
            AppSettingEntity(
                key = AppSettingEntity.KEY_AUTH_TOKEN,
                value = response.authToken,
                updatedAt = System.currentTimeMillis()
            )
        )

        runCatching {
            val branding = apiService.getDeviceBranding("Bearer ${response.authToken}")
            appSettingDao.upsert(
                AppSettingEntity(
                    key = AppSettingEntity.KEY_COMPANY_ID,
                    value = branding.id,
                    updatedAt = System.currentTimeMillis()
                )
            )
            appSettingDao.upsert(
                AppSettingEntity(
                    key = AppSettingEntity.KEY_COMPANY_NAME,
                    value = branding.name,
                    updatedAt = System.currentTimeMillis()
                )
            )
            appSettingDao.upsert(
                AppSettingEntity(
                    key = AppSettingEntity.KEY_COMPANY_LOGO_URL,
                    value = branding.logo_path?.let { logoPath ->
                        if (logoPath.startsWith("http")) logoPath
                        else "${apiBaseUrl.trimEnd('/')}/${logoPath.trimStart('/')}"
                    },
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

        deviceDao.getCurrent()!!.toDomain()
    }.fold(
        onSuccess = { Result.Success(it) },
        onFailure = { Result.Error(it, it.message) }
    )

    override suspend fun enrollWithBackend(
        deviceName: String,
        deviceFingerprint: String,
        enrollmentCode: String,
        apiBaseUrl: String
    ): Result<Device> = runCatching {
        val apiService = apiServiceFactory.create(apiBaseUrl)
        val current = deviceDao.getCurrent()
            ?: throw IllegalStateException("No hay dispositivo local creado")
        val response = apiService.enrollDevice(
            DeviceEnrollmentRequest(
                device_name = deviceName,
                device_fingerprint = deviceFingerprint,
                local_id = current.idLocal,
                enrollment_code = enrollmentCode
            )
        )
        deviceDao.updateRegistration(
            idLocal = current.idLocal,
            idRemote = response.deviceId,
            authToken = response.authToken
        )
        response.siteId?.let { deviceDao.updateSiteId(current.idLocal, it) }
        val now = System.currentTimeMillis()
        appSettingDao.upsert(AppSettingEntity(AppSettingEntity.KEY_AUTH_TOKEN, response.authToken, now))
        response.branding?.let {
            appSettingDao.upsert(AppSettingEntity(AppSettingEntity.KEY_COMPANY_ID, it.id, now))
            appSettingDao.upsert(AppSettingEntity(AppSettingEntity.KEY_COMPANY_NAME, it.name, now))
            appSettingDao.upsert(AppSettingEntity(AppSettingEntity.KEY_COMPANY_LOGO_URL, it.logo_path, now))
        }
        deviceDao.getCurrent()!!.toDomain()
    }.fold(
        onSuccess = { Result.Success(it) },
        onFailure = { Result.Error(it, it.message) }
    )

    override suspend fun refreshToken(apiBaseUrl: String): Result<String> = runCatching {
        val apiService = apiServiceFactory.create(apiBaseUrl)
        val token = appSettingDao.getValue(AppSettingEntity.KEY_AUTH_TOKEN)
            ?: throw IllegalStateException("No hay token almacenado")
        val response = apiService.refreshToken("Bearer $token")
        val device = deviceDao.getCurrent()
            ?: throw IllegalStateException("No hay dispositivo local")
        deviceDao.updateAuthToken(device.idLocal, response.authToken)
        appSettingDao.upsert(
            AppSettingEntity(
                key = AppSettingEntity.KEY_AUTH_TOKEN,
                value = response.authToken,
                updatedAt = System.currentTimeMillis()
            )
        )
        response.authToken
    }.fold(
        onSuccess = { Result.Success(it) },
        onFailure = { Result.Error(it, it.message) }
    )

    override suspend fun verifyToken(apiBaseUrl: String): Result<Boolean> = runCatching {
        val apiService = apiServiceFactory.create(apiBaseUrl)
        val token = appSettingDao.getValue(AppSettingEntity.KEY_AUTH_TOKEN)
            ?: return Result.Success(false)
        val response = apiService.verifyToken("Bearer $token")
        response.valid
    }.fold(
        onSuccess = { Result.Success(it) },
        onFailure = { Result.Error(it, it.message) }
    )

    // ── Mapper ────────────────────────────────────────────────────────────────

    private fun DeviceEntity.toDomain() = Device(
        idLocal = idLocal,
        idRemote = idRemote,
        deviceName = deviceName,
        siteId = siteId,
        authToken = authToken,
        registeredAt = registeredAt
    )

}
