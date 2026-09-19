package com.coati.checador.feature.deviceauth.data.repository

import com.coati.checador.core.common.Result
import com.coati.checador.core.database.dao.AppSettingDao
import com.coati.checador.core.database.dao.DeviceDao
import com.coati.checador.core.database.entity.AppSettingEntity
import com.coati.checador.core.database.entity.DeviceEntity
import com.coati.checador.core.network.CoatiApiServiceFactory
import com.coati.checador.core.network.dto.DeviceEnrollmentRequest
import com.coati.checador.core.network.dto.DeviceRegisterRequest
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

    override suspend fun createLocalDevice(
        deviceName: String
    ): Device {

        val idLocal =
            UUID.randomUUID().toString()

        val now =
            System.currentTimeMillis()

        val entity =
            DeviceEntity(
                idLocal = idLocal,
                deviceName = deviceName,
                registeredAt = now
            )

        deviceDao.insertOrReplace(
            entity
        )

        appSettingDao.upsert(
            AppSettingEntity(
                key =
                    AppSettingEntity.KEY_DEVICE_ID,

                value =
                    idLocal,

                updatedAt =
                    now
            )
        )

        return entity.toDomain()
    }

    override suspend fun registerWithBackend(
        deviceName: String,
        deviceFingerprint: String,
        apiBaseUrl: String
    ): Result<Device> =
        runCatching {

            val apiService =
                apiServiceFactory.create(
                    apiBaseUrl
                )

            val current =
                deviceDao.getCurrent()
                    ?: throw IllegalStateException(
                        "No hay dispositivo local creado"
                    )

            val response =
                apiService.registerDevice(
                    DeviceRegisterRequest(
                        deviceName =
                            deviceName,

                        deviceFingerprint =
                            deviceFingerprint,

                        localId =
                            current.idLocal
                    )
                )

            val now =
                System.currentTimeMillis()

            /*
             * Guardar URL de API.
             * Esta URL será utilizada después por
             * EmployeeSyncWorker y AttendanceSyncWorker.
             */
            appSettingDao.upsert(
                AppSettingEntity(
                    key =
                        AppSettingEntity.KEY_API_BASE_URL,

                    value =
                        apiBaseUrl,

                    updatedAt =
                        now
                )
            )

            /*
             * Guardar datos de registro del dispositivo.
             */
            deviceDao.updateRegistration(
                idLocal =
                    current.idLocal,

                idRemote =
                    response.deviceId,

                authToken =
                    response.authToken
            )

            /*
             * Guardar sitio si fue asignado por el servidor.
             */
            response.siteId?.let { siteId ->

                deviceDao.updateSiteId(
                    idLocal =
                        current.idLocal,

                    siteId =
                        siteId
                )
            }

            /*
             * Guardar token también en app_settings.
             * Los Workers de sincronización lo leen desde aquí.
             */
            appSettingDao.upsert(
                AppSettingEntity(
                    key =
                        AppSettingEntity.KEY_AUTH_TOKEN,

                    value =
                        response.authToken,

                    updatedAt =
                        now
                )
            )

            /*
             * Obtener empresa / branding.
             */
            runCatching {

                val branding =
                    apiService.getDeviceBranding(
                        "Bearer ${response.authToken}"
                    )

                appSettingDao.upsert(
                    AppSettingEntity(
                        key =
                            AppSettingEntity.KEY_COMPANY_ID,

                        value =
                            branding.id,

                        updatedAt =
                            now
                    )
                )

                appSettingDao.upsert(
                    AppSettingEntity(
                        key =
                            AppSettingEntity.KEY_COMPANY_NAME,

                        value =
                            branding.name,

                        updatedAt =
                            now
                    )
                )

                appSettingDao.upsert(
                    AppSettingEntity(
                        key =
                            AppSettingEntity.KEY_COMPANY_LOGO_URL,

                        value =
                            branding.logo_path?.let { logoPath ->

                                if (
                                    logoPath.startsWith(
                                        "http"
                                    )
                                ) {

                                    logoPath

                                } else {

                                    "${apiBaseUrl.trimEnd('/')}/" +
                                        logoPath.trimStart('/')
                                }
                            },

                        updatedAt =
                            now
                    )
                )
            }

            deviceDao
                .getCurrent()!!
                .toDomain()

        }.fold(
            onSuccess = {
                Result.Success(it)
            },

            onFailure = {
                Result.Error(
                    it,
                    it.message
                )
            }
        )

    override suspend fun enrollWithBackend(
        deviceName: String,
        deviceFingerprint: String,
        enrollmentCode: String,
        apiBaseUrl: String
    ): Result<Device> =
        runCatching {

            val apiService =
                apiServiceFactory.create(
                    apiBaseUrl
                )

            val current =
                deviceDao.getCurrent()
                    ?: throw IllegalStateException(
                        "No hay dispositivo local creado"
                    )

            val response =
                apiService.enrollDevice(
                    DeviceEnrollmentRequest(
                        device_name =
                            deviceName,

                        device_fingerprint =
                            deviceFingerprint,

                        local_id =
                            current.idLocal,

                        enrollment_code =
                            enrollmentCode
                    )
                )

            val now =
                System.currentTimeMillis()

            /*
             * Guardar URL de API.
             */
            appSettingDao.upsert(
                AppSettingEntity(
                    key =
                        AppSettingEntity.KEY_API_BASE_URL,

                    value =
                        apiBaseUrl,

                    updatedAt =
                        now
                )
            )

            /*
             * Guardar registro remoto y token.
             */
            deviceDao.updateRegistration(
                idLocal =
                    current.idLocal,

                idRemote =
                    response.deviceId,

                authToken =
                    response.authToken
            )

            /*
             * Guardar sitio asignado durante el enrolamiento.
             */
            response.siteId?.let { siteId ->

                deviceDao.updateSiteId(
                    idLocal =
                        current.idLocal,

                    siteId =
                        siteId
                )
            }

            /*
             * Guardar token para los Workers.
             */
            appSettingDao.upsert(
                AppSettingEntity(
                    key =
                        AppSettingEntity.KEY_AUTH_TOKEN,

                    value =
                        response.authToken,

                    updatedAt =
                        now
                )
            )

            /*
             * Guardar empresa y branding enviados
             * directamente en la respuesta de enrolamiento.
             */
            response.branding?.let { branding ->

                appSettingDao.upsert(
                    AppSettingEntity(
                        key =
                            AppSettingEntity.KEY_COMPANY_ID,

                        value =
                            branding.id,

                        updatedAt =
                            now
                    )
                )

                appSettingDao.upsert(
                    AppSettingEntity(
                        key =
                            AppSettingEntity.KEY_COMPANY_NAME,

                        value =
                            branding.name,

                        updatedAt =
                            now
                    )
                )

                appSettingDao.upsert(
                    AppSettingEntity(
                        key =
                            AppSettingEntity.KEY_COMPANY_LOGO_URL,

                        value =
                            branding.logo_path?.let { logoPath ->

                                if (
                                    logoPath.startsWith(
                                        "http"
                                    )
                                ) {

                                    logoPath

                                } else {

                                    "${apiBaseUrl.trimEnd('/')}/" +
                                        logoPath.trimStart('/')
                                }
                            },

                        updatedAt =
                            now
                    )
                )
            }

            deviceDao
                .getCurrent()!!
                .toDomain()

        }.fold(
            onSuccess = {
                Result.Success(it)
            },

            onFailure = {
                Result.Error(
                    it,
                    it.message
                )
            }
        )

    override suspend fun refreshToken(
        apiBaseUrl: String
    ): Result<String> =
        runCatching {

            val apiService =
                apiServiceFactory.create(
                    apiBaseUrl
                )

            val token =
                appSettingDao.getValue(
                    AppSettingEntity.KEY_AUTH_TOKEN
                )
                    ?: throw IllegalStateException(
                        "No hay token almacenado"
                    )

            val response =
                apiService.refreshToken(
                    "Bearer $token"
                )

            val device =
                deviceDao.getCurrent()
                    ?: throw IllegalStateException(
                        "No hay dispositivo local"
                    )

            deviceDao.updateAuthToken(
                idLocal =
                    device.idLocal,

                token =
                    response.authToken
            )

            val now =
                System.currentTimeMillis()

            /*
             * Actualizar URL por si cambió.
             */
            appSettingDao.upsert(
                AppSettingEntity(
                    key =
                        AppSettingEntity.KEY_API_BASE_URL,

                    value =
                        apiBaseUrl,

                    updatedAt =
                        now
                )
            )

            /*
             * Actualizar token.
             */
            appSettingDao.upsert(
                AppSettingEntity(
                    key =
                        AppSettingEntity.KEY_AUTH_TOKEN,

                    value =
                        response.authToken,

                    updatedAt =
                        now
                )
            )

            response.authToken

        }.fold(
            onSuccess = {
                Result.Success(it)
            },

            onFailure = {
                Result.Error(
                    it,
                    it.message
                )
            }
        )

    override suspend fun verifyToken(
        apiBaseUrl: String
    ): Result<Boolean> =
        runCatching {

            val apiService =
                apiServiceFactory.create(
                    apiBaseUrl
                )

            val token =
                appSettingDao.getValue(
                    AppSettingEntity.KEY_AUTH_TOKEN
                )
                    ?: return Result.Success(
                        false
                    )

            val response =
                apiService.verifyToken(
                    "Bearer $token"
                )

            response.valid

        }.fold(
            onSuccess = {
                Result.Success(it)
            },

            onFailure = {
                Result.Error(
                    it,
                    it.message
                )
            }
        )

    // =========================================================
    // MAPPER
    // =========================================================

    private fun DeviceEntity.toDomain() =
        Device(
            idLocal =
                idLocal,

            idRemote =
                idRemote,

            deviceName =
                deviceName,

            siteId =
                siteId,

            authToken =
                authToken,

            registeredAt =
                registeredAt
        )
}