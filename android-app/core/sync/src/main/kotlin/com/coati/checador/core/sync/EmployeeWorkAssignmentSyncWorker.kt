package com.coati.checador.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.coati.checador.core.database.dao.AppSettingDao
import com.coati.checador.core.database.dao.EmployeeDao
import com.coati.checador.core.database.dao.EmployeeWorkSiteDao
import com.coati.checador.core.database.entity.AppSettingEntity
import com.coati.checador.core.database.entity.EmployeeWorkSiteEntity
import com.coati.checador.core.network.CoatiApiServiceFactory
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class EmployeeWorkAssignmentSyncWorker @AssistedInject constructor(

    @Assisted
    appContext: Context,

    @Assisted
    workerParams: WorkerParameters,

    private val employeeDao: EmployeeDao,

    private val employeeWorkSiteDao: EmployeeWorkSiteDao,

    private val appSettingDao: AppSettingDao,

    private val apiServiceFactory: CoatiApiServiceFactory

) : CoroutineWorker(
    appContext,
    workerParams
) {

    override suspend fun doWork(): Result {

        val authToken =
            appSettingDao.getValue(
                AppSettingEntity.KEY_AUTH_TOKEN
            )

        if (authToken.isNullOrBlank()) {

            Timber.w(
                "EmployeeWorkAssignmentSyncWorker: dispositivo no registrado"
            )

            return Result.success()
        }

        return try {

            val apiBaseUrl =
                appSettingDao.getValue(
                    AppSettingEntity.KEY_API_BASE_URL
                )

            val apiService =
                apiServiceFactory.create(
                    apiBaseUrl
                )

            val response =
                apiService.getEmployeeWorkAssignments(
                    bearerToken = "Bearer $authToken"
                )

            Timber.i(
                "EmployeeWorkAssignmentSyncWorker: " +
                    "${response.assignments.size} asignaciones recibidas"
            )

            val now =
                System.currentTimeMillis()

            val assignments =
                response.assignments.mapNotNull { remote ->

                    // =================================================
                    // 1. BUSCAR POR ID LOCAL
                    // =================================================

                    var employee =
                        if (remote.employee_id_local.isNotBlank()) {

                            employeeDao.findById(
                                remote.employee_id_local.trim()
                            )

                        } else {
                            null
                        }

                    // =================================================
                    // 2. SI NO COINCIDE, BUSCAR POR ID REMOTO
                    // =================================================

                    if (
                        employee == null &&
                        remote.employee_id_remote.isNotBlank()
                    ) {

                        employee =
                            employeeDao.findByRemoteId(
                                remote.employee_id_remote.trim()
                            )
                    }

                    // =================================================
                    // 3. SI TAMPOCO COINCIDE, BUSCAR POR CÓDIGO
                    // =================================================

                    if (
                        employee == null &&
                        remote.employee_code.isNotBlank()
                    ) {

                        employee =
                            employeeDao.findByCode(
                                remote.employee_code.trim()
                            )
                    }

                    if (employee == null) {

                        Timber.w(
                            "EmployeeWorkAssignmentSyncWorker: " +
                                "no se encontró empleado para asignación. " +
                                "local=${remote.employee_id_local}, " +
                                "remote=${remote.employee_id_remote}, " +
                                "code=${remote.employee_code}, " +
                                "weekday=${remote.weekday}, " +
                                "mode=${remote.work_mode}"
                        )

                        null

                    } else {

                        val normalizedMode =
                            remote.work_mode
                                .trim()
                                .uppercase()

                        if (
                            normalizedMode != "SITE" &&
                            normalizedMode != "FOREIGN"
                        ) {

                            Timber.e(
                                "EmployeeWorkAssignmentSyncWorker: " +
                                    "work_mode inválido=${remote.work_mode}"
                            )

                            null

                        } else if (
                            remote.weekday !in 1..7
                        ) {

                            Timber.e(
                                "EmployeeWorkAssignmentSyncWorker: " +
                                    "weekday inválido=${remote.weekday}"
                            )

                            null

                        } else {

                            Timber.i(
                                "EmployeeWorkAssignmentSyncWorker: " +
                                    "ASIGNACIÓN OK -> " +
                                    "empleado=${employee.fullName}, " +
                                    "idLocal=${employee.idLocal}, " +
                                    "weekday=${remote.weekday}, " +
                                    "mode=$normalizedMode"
                            )

                            EmployeeWorkSiteEntity(
                                employeeId = employee.idLocal,

                                weekday = remote.weekday,

                                workMode = normalizedMode,

                                siteId =
                                    if (normalizedMode == "FOREIGN") {
                                        null
                                    } else {
                                        remote.site_id
                                    },

                                siteName =
                                    if (normalizedMode == "FOREIGN") {
                                        null
                                    } else {
                                        remote.site_name
                                    },

                                updatedAt = now
                            )
                        }
                    }
                }

            // =====================================================
            // NO BORRAR CONFIGURACIÓN SI EL SERVIDOR DEVUELVE 0
            // =====================================================

            if (response.assignments.isEmpty()) {

                Timber.w(
                    "EmployeeWorkAssignmentSyncWorker: " +
                        "servidor devolvió 0 asignaciones. " +
                        "Se conserva Room."
                )

                return Result.success()
            }

            // =====================================================
            // REEMPLAZAR CONFIGURACIÓN LOCAL
            // =====================================================

            employeeWorkSiteDao.deleteAll()

            if (assignments.isNotEmpty()) {

                employeeWorkSiteDao.insertAll(
                    assignments
                )
            }

            Timber.i(
                "EmployeeWorkAssignmentSyncWorker: " +
                    "${assignments.size} asignaciones guardadas en Room"
            )

            assignments.forEach { assignment ->

                Timber.i(
                    "EmployeeWorkAssignmentSyncWorker: " +
                        "ROOM -> " +
                        "employeeId=${assignment.employeeId}, " +
                        "weekday=${assignment.weekday}, " +
                        "mode=${assignment.workMode}, " +
                        "siteId=${assignment.siteId}"
                )
            }

            Result.success()

        } catch (e: Exception) {

            Timber.e(
                e,
                "EmployeeWorkAssignmentSyncWorker: " +
                    "error descargando configuración laboral"
            )

            if (runAttemptCount < 10) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}