package com.coati.checador.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.coati.checador.core.database.dao.AppSettingDao
import com.coati.checador.core.database.dao.ForeignLocationPointDao
import com.coati.checador.core.database.entity.AppSettingEntity
import com.coati.checador.core.database.entity.ForeignLocationPointEntity
import com.coati.checador.core.database.model.SyncStatus
import com.coati.checador.core.network.CoatiApiServiceFactory
import com.coati.checador.core.network.dto.ForeignLocationPointDto
import com.coati.checador.core.network.dto.ForeignLocationSyncRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class ForeignLocationSyncWorker @AssistedInject constructor(

    @Assisted
    appContext: Context,

    @Assisted
    workerParams: WorkerParameters,

    private val foreignLocationPointDao: ForeignLocationPointDao,

    private val appSettingDao: AppSettingDao,

    private val apiServiceFactory: CoatiApiServiceFactory

) : CoroutineWorker(
    appContext,
    workerParams
) {

    override suspend fun doWork(): Result {

        var currentBatch =
            emptyList<ForeignLocationPointEntity>()

        return try {

            // =====================================================
            // TOKEN
            // =====================================================

            val authToken =
                appSettingDao.getValue(
                    AppSettingEntity.KEY_AUTH_TOKEN
                )

            if (authToken.isNullOrBlank()) {

                Timber.w(
                    "ForeignLocationSyncWorker: token no configurado"
                )

                return Result.success()
            }

            // =====================================================
            // URL API
            // =====================================================

            val baseUrl =
                appSettingDao.getValue(
                    AppSettingEntity.KEY_API_BASE_URL
                )

            if (baseUrl.isNullOrBlank()) {

                Timber.e(
                    "ForeignLocationSyncWorker: API base URL no configurada"
                )

                return Result.retry()
            }

            // =====================================================
            // PUNTOS PENDIENTES
            // =====================================================

            currentBatch =
                foreignLocationPointDao.getPendingBatch(
                    limit = 50
                )

            if (currentBatch.isEmpty()) {

                Timber.d(
                    "ForeignLocationSyncWorker: no hay puntos GPS pendientes"
                )

                return Result.success()
            }

            Timber.i(
                "ForeignLocationSyncWorker: sincronizando " +
                    "${currentBatch.size} puntos GPS"
            )

            // =====================================================
            // MARCAR COMO SINCRONIZANDO
            // =====================================================

            foreignLocationPointDao.markAsSyncing(
                currentBatch.map {
                    it.idLocal
                }
            )

            // =====================================================
            // CREAR API
            // =====================================================

            val apiService =
                apiServiceFactory.create(
                    baseUrl
                )

            // =====================================================
            // CREAR REQUEST
            // =====================================================

            val request =
                ForeignLocationSyncRequest(

                    points =
                        currentBatch.map { point ->

                            ForeignLocationPointDto(

                                idLocal =
                                    point.idLocal,

                                employeeId =
                                    point.employeeId,

                                workDate =
                                    point.workDate,

                                occurredAt =
                                    point.occurredAt,

                                latitude =
                                    point.latitude,

                                longitude =
                                    point.longitude,

                                accuracyM =
                                    point.accuracyM,

                                altitudeM =
                                    point.altitudeM,

                                deviceId =
                                    point.deviceId
                            )
                        }
                )

            // =====================================================
            // ENVIAR AL SERVIDOR
            // =====================================================

            val response =
                apiService.syncForeignLocations(

                    bearerToken =
                        "Bearer $authToken",

                    request =
                        request
                )

            // =====================================================
            // IDS QUE RESPONDIÓ EL SERVIDOR
            // =====================================================

            val syncedIds =
                response.synced
                    .map {
                        it.idLocal
                    }
                    .toSet()

            val errorIds =
                response.errors
                    .map {
                        it.idLocal
                    }
                    .toSet()

            // =====================================================
            // MARCAR SINCRONIZADOS
            // =====================================================

            response.synced.forEach { synced ->

                foreignLocationPointDao.markSynced(

                    idLocal =
                        synced.idLocal,

                    status =
                        SyncStatus.SYNCED,

                    idRemote =
                        synced.idRemote
                )

                Timber.i(
                    "ForeignLocationSyncWorker: punto sincronizado " +
                        synced.idLocal
                )
            }

            // =====================================================
            // ERRORES DEVUELTOS POR SERVIDOR
            // =====================================================

            response.errors.forEach { error ->

                foreignLocationPointDao.markSyncFailed(

                    idLocal =
                        error.idLocal,

                    status =
                        SyncStatus.ERROR,

                    error =
                        error.error
                )

                Timber.e(
                    "ForeignLocationSyncWorker: error en " +
                        "${error.idLocal}: ${error.error}"
                )
            }

            // =====================================================
            // PUNTOS SIN RESPUESTA
            // =====================================================

            currentBatch.forEach { point ->

                if (
                    point.idLocal !in syncedIds &&
                    point.idLocal !in errorIds
                ) {

                    foreignLocationPointDao.markSyncFailed(

                        idLocal =
                            point.idLocal,

                        status =
                            SyncStatus.ERROR,

                        error =
                            "El servidor no devolvió resultado para este punto"
                    )

                    Timber.w(
                        "ForeignLocationSyncWorker: punto " +
                            "${point.idLocal} regresado a ERROR"
                    )
                }
            }

            // =====================================================
            // COMPROBAR SI QUEDAN PENDIENTES
            // =====================================================

            val remaining =
                foreignLocationPointDao.countPending()

            Timber.i(
                "ForeignLocationSyncWorker: pendientes restantes = $remaining"
            )

            if (remaining > 0) {

                Result.retry()

            } else {

                Result.success()
            }

        } catch (exception: Exception) {

            Timber.e(
                exception,
                "ForeignLocationSyncWorker: error general " +
                    "sincronizando ubicaciones"
            )

            // =====================================================
            // SI FALLÓ INTERNET / API / RETROFIT,
            // DEVOLVER LOS PUNTOS A ERROR PARA REINTENTAR
            // =====================================================

            currentBatch.forEach { point ->

                try {

                    foreignLocationPointDao.markSyncFailed(

                        idLocal =
                            point.idLocal,

                        status =
                            SyncStatus.ERROR,

                        error =
                            exception.message
                                ?: "Error de sincronización"
                    )

                } catch (daoException: Exception) {

                    Timber.e(
                        daoException,
                        "ForeignLocationSyncWorker: no se pudo recuperar " +
                            point.idLocal
                    )
                }
            }

            if (runAttemptCount < 10) {

                Result.retry()

            } else {

                Result.failure()
            }
        }
    }
}