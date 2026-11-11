package com.coati.checador.core.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.coati.checador.core.database.dao.AppSettingDao
import com.coati.checador.core.database.dao.AttendanceRecordDao
import com.coati.checador.core.database.entity.AppSettingEntity
import com.coati.checador.core.database.entity.AttendanceRecordEntity
import com.coati.checador.core.database.model.SyncStatus
import com.coati.checador.core.network.CoatiApiServiceFactory
import com.coati.checador.core.network.dto.AttendanceRecordDto
import com.coati.checador.core.network.dto.AttendanceSyncRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class AttendanceSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val attendanceRecordDao: AttendanceRecordDao,
    private val appSettingDao: AppSettingDao,
    private val apiServiceFactory: CoatiApiServiceFactory
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = uploadMutex.withLock { uploadPending() }

    private suspend fun uploadPending(): Result {
        // Bajo el mutex no hay otro envío de checadas activo en este proceso.
        // Recupera registros que quedaron SYNCING al cerrar o actualizar la app.
        attendanceRecordDao.recoverInterruptedUploads()
        val authToken = appSettingDao.getValue(AppSettingEntity.KEY_AUTH_TOKEN)
        if (authToken.isNullOrBlank()) {
            Timber.w("AttendanceSyncWorker: dispositivo no registrado, se omite sincronizacion")
            return Result.success()
        }

        val pending = attendanceRecordDao.getPendingBatch(BATCH_SIZE)
        if (pending.isEmpty()) {
            refreshBreakMetadata(authToken)
            return Result.success()
        }

        val idsLocal = pending.map { it.idLocal }
        attendanceRecordDao.markAsSyncing(idsLocal)

        return try {
            val apiBaseUrl = appSettingDao.getValue(AppSettingEntity.KEY_API_BASE_URL)
            val apiService = apiServiceFactory.create(apiBaseUrl)
            val response = apiService.syncAttendance(
                bearerToken = "Bearer $authToken",
                request = AttendanceSyncRequest(records = pending.map { it.toDto() })
            )

            response.synced.forEach { synced ->
                attendanceRecordDao.markSynced(synced.idLocal, SyncStatus.SYNCED, synced.idRemote)
                attendanceRecordDao.updateBreakMetadata(synced.idLocal, synced.breakKind, synced.breakNumber)
            }
            response.errors.forEach { error ->
                attendanceRecordDao.markSyncFailed(error.idLocal, SyncStatus.ERROR, error.error)
            }

            refreshBreakMetadata(authToken)
            // Una respuesta parcial no debe dejar registros invisibles en SYNCING.
            attendanceRecordDao.releaseUnconfirmedUploads(idsLocal)
            if (attendanceRecordDao.countPending() > 0) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Timber.e(e, "Error en sincronizacion de asistencia")
            idsLocal.forEach { idLocal ->
                if (attendanceRecordDao.findById(idLocal)?.syncStatus == SyncStatus.SYNCING) {
                    attendanceRecordDao.markSyncFailed(idLocal, SyncStatus.ERROR, e.message)
                }
            }
            if (runAttemptCount < 10) Result.retry() else Result.failure()
        } finally {
            withContext(NonCancellable) {
                attendanceRecordDao.releaseUnconfirmedUploads(idsLocal)
            }
        }
    }

    private suspend fun refreshBreakMetadata(token: String) {
        try {
            val recent = attendanceRecordDao.observeRecent(500).first()
            if (recent.isEmpty()) return
            val url = appSettingDao.getValue(AppSettingEntity.KEY_API_BASE_URL)
            val rows = apiServiceFactory.create(url).attendanceMetadata("Bearer " + token,
                mapOf("ids" to recent.map { it.idLocal }))
            rows.forEach { attendanceRecordDao.updateBreakMetadata(it.idLocal, it.breakKind, it.breakNumber) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Timber.w(error, "No se actualizaron los nombres de pausa") }
    }

    private fun AttendanceRecordEntity.toDto() = AttendanceRecordDto(
    idLocal = idLocal,
    employeeId = employeeId,
    eventType = eventType,
    occurredAt = occurredAt,
    latitude = latitude,
    longitude = longitude,
    accuracyM = accuracyM,
    altitudeM = altitudeM,
    faceConfidence = faceConfidence,
    siteId = siteId
)

    companion object {
        private val uploadMutex = Mutex()
        private const val BATCH_SIZE = 50
    }
}
