package com.coati.checador.shared.domain.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.coati.checador.shared.domain.repository.SyncScheduler
import com.coati.checador.shared.security.PlatformContext
import java.util.concurrent.TimeUnit

/**
 * Scheduler Android sobre WorkManager.
 *
 * - syncNow(): OneTimeWorkRequest con restricción de red CONNECTED y
 *   backoff exponencial de 30 s (mismo enfoque que los workers del
 *   Android original).
 * - El Worker ejecuta SyncOrchestrator.syncAll(); ante fallo devuelve
 *   retry() para que WorkManager aplique el backoff.
 */
actual fun createBackgroundSyncScheduler(
    platformContext: PlatformContext,
    orchestrator: SyncOrchestrator
): SyncScheduler = WorkManagerSyncScheduler(
    context = platformContext.context
        ?: error("PlatformContext sin Context registrado; llama a PlatformContext(appContext) al arrancar"),
    orchestrator = orchestrator
)

internal class WorkManagerSyncScheduler(
    private val context: Context,
    private val orchestrator: SyncOrchestrator
) : SyncScheduler {

    override fun syncNow() {
        // El orquestador se comparte con el Worker vía estático (WorkManager
        // construye el Worker por reflexión sin argumentos).
        SyncWorker.pendingOrchestrator = orchestrator

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(SyncWorker.WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}

/**
 * Worker que ejecuta el orquestador. Los errores se propagan para que
 * WorkManager aplique el backoff y reintente hasta su límite interno.
 */
internal class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val orchestrator = pendingOrchestrator ?: return Result.failure()
        return try {
            val report = orchestrator.syncAll()
            if (report.hasHardFailure) Result.retry() else Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "coaticheck_sync"

        /** Lo establece WorkManagerSyncScheduler.syncNow() antes de encolar. */
        @Volatile
        var pendingOrchestrator: SyncOrchestrator? = null
    }
}
