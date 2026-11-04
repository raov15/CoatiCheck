package com.coati.checador.shared.domain.sync

import com.coati.checador.shared.domain.repository.SyncScheduler
import com.coati.checador.shared.security.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Scheduler iOS.
 *
 * - syncNow(): ejecuta el orquestador inmediatamente en una corrutina de
 *   IO (para uso cuando la app está activa o recibe una oportunidad).
 * - Para segundo plano, la app iOS debe registrar los identificadores
 *   "com.coati.checador.ios.sync" (BGAppRefreshTask / BGProcessingTask)
 *   en Info.plist y en BGTaskScheduler al arranque; cuando el sistema
 *   lanza la tarea, el AppDelegate llama a [runSyncBlocking] o
 *   [syncNow] y expira el task con el resultado.
 *
 * iOS no garantiza frecuencia: el sistema decide cuándo ejecutar las
 * tareas, por lo que el diseño tolera retrasos (sin backoff propio).
 */
actual fun createBackgroundSyncScheduler(
    platformContext: PlatformContext,
    orchestrator: SyncOrchestrator
): SyncScheduler = IosSyncScheduler(orchestrator)

internal class IosSyncScheduler(
    private val orchestrator: SyncOrchestrator
) : SyncScheduler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun syncNow() {
        scope.launch {
            runCatching { orchestrator.syncAll() }
        }
    }

    /** Ejecuta el sync de forma bloqueante (para BGTask del AppDelegate). */
    fun runSyncBlocking() {
        kotlinx.coroutines.runBlocking {
            runCatching { orchestrator.syncAll() }
        }
    }
}
