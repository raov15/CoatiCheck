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

        const val ATTENDANCE_PERIODIC_SYNC_WORK_NAME =
            "coati_attendance_periodic_sync"
    }

    /**
     * Sincronización inmediata.
     *
     * 1. Empleados.
     * 2. Asistencias.
     *
     * APPEND_OR_REPLACE evita cancelar una sincronización
     * que ya esté ejecutándose.
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

        val attendanceSync =
            OneTimeWorkRequestBuilder<AttendanceSyncWorker>()
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
            .then(attendanceSync)
            .enqueue()
    }

    /**
     * Sincronización periódica de respaldo.
     *
     * Se programan empleados Y asistencias.
     * Así, si algo falla en la sincronización inmediata,
     * vuelve a intentarse automáticamente.
     */
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

        val attendanceSyncRequest =
            PeriodicWorkRequestBuilder<AttendanceSyncWorker>(
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
                ATTENDANCE_PERIODIC_SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                attendanceSyncRequest
            )
    }
}