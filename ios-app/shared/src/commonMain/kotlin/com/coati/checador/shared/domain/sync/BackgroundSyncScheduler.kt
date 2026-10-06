package com.coati.checador.shared.domain.sync

import com.coati.checador.shared.domain.repository.SyncScheduler
import com.coati.checador.shared.security.PlatformContext

/**
 * Fábrica del scheduler de sincronización de cada plataforma.
 *
 * - Android: WorkManager (OneTimeWorkRequest con restricción de red).
 * - iOS: corrutina inmediata + BGTaskScheduler para oportunidades de
 *   segundo plano (requiere registrar los identificadores en Info.plist).
 */
expect fun createBackgroundSyncScheduler(
    platformContext: PlatformContext,
    orchestrator: SyncOrchestrator
): SyncScheduler
