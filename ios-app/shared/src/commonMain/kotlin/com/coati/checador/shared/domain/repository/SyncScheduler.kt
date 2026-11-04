package com.coati.checador.shared.domain.repository

/**
 * Abstracción para solicitar sincronización sin depender de la
 * plataforma (WorkManager en Android, BGTaskScheduler en iOS).
 */
interface SyncScheduler {
    /**
     * Solicita una sincronización inmediata.
     * Orden esperado:
     * 1. Subir empleados pendientes.
     * 2. Descargar configuración SITE / FOREIGN.
     * 3. Subir asistencias.
     * 4. Subir ubicaciones foráneas.
     */
    fun syncNow()
}
