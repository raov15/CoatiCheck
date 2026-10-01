package com.coati.checador.core.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {

        const val IMMEDIATE_SYNC_WORK_NAME =
            "coati_immediate_sync_work"

        const val EMPLOYEE_PERIODIC_SYNC_WORK_NAME =
            "coati_employee_periodic_sync"

        const val WORK_ASSIGNMENT_PERIODIC_SYNC_WORK_NAME =
            "coati_work_assignment_periodic_sync"

        const val ATTENDANCE_PERIODIC_SYNC_WORK_NAME =
            "coati_attendance_periodic_sync"

        const val FOREIGN_LOCATION_PERIODIC_SYNC_WORK_NAME =
            "coati_foreign_location_periodic_sync"
    }

    /**
     * Sincronización inmediata.
     *
     * Orden:
     * 1. Subir empleados.
     * 2. Descargar configuración SITE / FOREIGN.
     * 3. Subir asistencias.
     * 4. Subir ubicaciones Foráneo.
     */
    fun syncNow() {

        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(
                    NetworkType.CONNECTED
                )
                .build()

        val employeeSync =
            OneTimeWorkRequestBuilder<EmployeeSyncWorker>()
                .setConstraints(constraints)
                .setExpedited(
                    OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
                )
                .build()

        val workAssignmentSync =
            OneTimeWorkRequestBuilder<EmployeeWorkAssignmentSyncWorker>()
                .setConstraints(constraints)
                .setExpedited(
                    OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
                )
                .build()

        val attendanceSync =
            OneTimeWorkRequestBuilder<AttendanceSyncWorker>()
                .setConstraints(constraints)
                .setExpedited(
                    OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
                )
                .build()

        val foreignLocationSync =
            OneTimeWorkRequestBuilder<ForeignLocationSyncWorker>()
                .setConstraints(constraints)
                .setExpedited(
                    OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
                )
                .build()

        WorkManager
            .getInstance(context)
            .beginUniqueWork(
                IMMEDIATE_SYNC_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                employeeSync
            )
            .then(workAssignmentSync)
            .then(attendanceSync)
            .then(foreignLocationSync)
            .enqueue()
    }

    /**
     * Sincronización periódica de respaldo.
     *
     * Cada proceso requiere conexión.
     *
     * IMPORTANTE:
     * Esto sincroniza información.
     * NO realiza la captura GPS cada 30 minutos.
     *
     * La captura GPS del trabajador Foráneo se realizará
     * mediante el servicio de ubicación.
     */

fun syncForeignLocationsNow() {

    val constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    val foreignLocationSync =
        OneTimeWorkRequestBuilder<ForeignLocationSyncWorker>()
            .setConstraints(constraints)
            .setExpedited(
                OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
            )
            .build()

    WorkManager
        .getInstance(context)
        .enqueueUniqueWork(
            "coati_foreign_location_immediate_sync",
            ExistingWorkPolicy.REPLACE,
            foreignLocationSync
        )
}









    fun schedulePeriodicSync() {

        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(
                    NetworkType.CONNECTED
                )
                .build()

        val employeeSyncRequest =
            PeriodicWorkRequestBuilder<EmployeeSyncWorker>(
                15L,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

        val workAssignmentSyncRequest =
            PeriodicWorkRequestBuilder<EmployeeWorkAssignmentSyncWorker>(
                15L,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

        val attendanceSyncRequest =
            PeriodicWorkRequestBuilder<AttendanceSyncWorker>(
                15L,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

        val foreignLocationSyncRequest =
            PeriodicWorkRequestBuilder<ForeignLocationSyncWorker>(
                15L,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(
                EMPLOYEE_PERIODIC_SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                employeeSyncRequest
            )

        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(
                WORK_ASSIGNMENT_PERIODIC_SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workAssignmentSyncRequest
            )

        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(
                ATTENDANCE_PERIODIC_SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                attendanceSyncRequest
            )

        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(
                FOREIGN_LOCATION_PERIODIC_SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                foreignLocationSyncRequest
            )
    }
}