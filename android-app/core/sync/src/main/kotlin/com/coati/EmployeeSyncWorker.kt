package com.coati.checador.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.coati.checador.core.database.dao.AppSettingDao
import com.coati.checador.core.database.dao.EmployeeDao
import com.coati.checador.core.database.entity.AppSettingEntity
import com.coati.checador.core.database.entity.EmployeeEntity
import com.coati.checador.core.database.model.SyncStatus
import com.coati.checador.core.network.CoatiApiServiceFactory
import com.coati.checador.core.network.dto.EmployeeDto
import com.coati.checador.core.network.dto.EmployeeSyncRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class EmployeeSyncWorker @AssistedInject constructor(

    @Assisted
    appContext: Context,

    @Assisted
    workerParams: WorkerParameters,

    private val employeeDao: EmployeeDao,

    private val appSettingDao: AppSettingDao,

    private val apiServiceFactory: CoatiApiServiceFactory

) : CoroutineWorker(
    appContext,
    workerParams
) {

    override suspend fun doWork(): Result {

        // =====================================================
        // TOKEN
        // =====================================================

        val authToken =
            appSettingDao.getValue(
                AppSettingEntity.KEY_AUTH_TOKEN
            )

        if (authToken.isNullOrBlank()) {

            Timber.w(
                "EmployeeSyncWorker: dispositivo no registrado"
            )

            return Result.success()
        }

        // =====================================================
        // EMPLEADOS PENDIENTES
        // =====================================================

        val pending =
            employeeDao.findBySyncStatus(
                SyncStatus.PENDING
            )

        if (pending.isEmpty()) {

            Timber.d(
                "EmployeeSyncWorker: no hay empleados pendientes"
            )

            return Result.success()
        }

        Timber.i(
            "EmployeeSyncWorker: ${pending.size} empleados pendientes"
        )

        // =====================================================
        // MARCAR COMO SINCRONIZANDO
        // =====================================================

        pending.forEach { employee ->

            employeeDao.updateSyncStatus(
                idLocal =
                    employee.idLocal,

                status =
                    SyncStatus.SYNCING
            )
        }

        // =====================================================
        // SINCRONIZAR
        // =====================================================

        return try {

            val apiBaseUrl =
                appSettingDao.getValue(
                    AppSettingEntity.KEY_API_BASE_URL
                )

            val apiService =
                apiServiceFactory.create(
                    apiBaseUrl
                )

            // =================================================
            // CONSTRUIR REQUEST
            // =================================================

            val employeeDtos =
                pending.map { employee ->

                    employee.toDto()
                }

            val request =
                EmployeeSyncRequest(
                    employees =
                        employeeDtos
                )

            Timber.i(
                "EmployeeSyncWorker: enviando ${employeeDtos.size} empleados al servidor"
            )

            // =================================================
            // LLAMADA AL SERVIDOR
            // =================================================

            val response =
                apiService.syncEmployees(
                    bearerToken =
                        "Bearer $authToken",

                    request =
                        request
                )

            // =================================================
            // EMPLEADOS SINCRONIZADOS
            // =================================================

            for (synced in response.synced) {

                employeeDao.updateSyncResult(
                    idLocal =
                        synced.idLocal,

                    status =
                        SyncStatus.SYNCED,

                    idRemote =
                        synced.idRemote,

                    updatedAt =
                        System.currentTimeMillis()
                )

                Timber.i(
                    "EmployeeSyncWorker: empleado sincronizado " +
                        "local=${synced.idLocal}, " +
                        "remote=${synced.idRemote}"
                )
            }

            // =================================================
            // ERRORES INDIVIDUALES
            // =================================================

            for (error in response.errors) {

                employeeDao.updateSyncStatus(
                    idLocal =
                        error.idLocal,

                    status =
                        SyncStatus.ERROR
                )

                Timber.e(
                    "EmployeeSyncWorker: error sincronizando " +
                        "${error.idLocal}: ${error.error}"
                )
            }

            // =================================================
            // REVISAR SI QUEDARON PENDIENTES
            // =================================================

            if (
                employeeDao.countPending() > 0
            ) {

                Result.retry()

            } else {

                Result.success()
            }

        } catch (e: Exception) {

            Timber.e(
                e,
                "EmployeeSyncWorker: error general de sincronización"
            )

            // =================================================
            // REGRESAR A PENDING SI FALLÓ LA RED/API
            // =================================================

            pending.forEach { employee ->

                employeeDao.updateSyncStatus(
                    idLocal =
                        employee.idLocal,

                    status =
                        SyncStatus.PENDING
                )
            }

            if (runAttemptCount < 10) {

                Result.retry()

            } else {

                Result.failure()
            }
        }
    }

    // =========================================================
    // EMPLOYEE ENTITY -> DTO
    // =========================================================

    /**
     * Convierte el empleado local al formato enviado al servidor.
     *
     * IMPORTANTE:
     *
     * Ya NO usamos:
     *
     * employeeCode.split("|")
     *
     * porque ahora:
     *
     * employeeCode = código
     * department = cargo
     * firstName = nombre
     * lastNamePaternal = apellido paterno
     * lastNameMaternal = apellido materno
     *
     * Cada dato vive en su propia columna.
     */
    private fun EmployeeEntity.toDto(): EmployeeDto {

        val codigoLimpio =
            employeeCode.trim()

        val nombreLimpio =
            firstName.trim()

        val apellidoPaternoLimpio =
            lastNamePaternal.trim()

        val apellidoMaternoLimpio =
            lastNameMaternal.trim()

        val nombreCompletoLimpio =
            fullName.trim()

        val rfcLimpio =
            rfc.trim().uppercase()

        val curpLimpia =
            curp.trim().uppercase()

        val nssLimpio =
            nss
                .filter { it.isDigit() }
                .trim()

        val cargoLimpio =
            department.trim()

        Timber.d(
            "EmployeeSyncWorker DTO -> " +
                "codigo=$codigoLimpio, " +
                "nombre=$nombreLimpio, " +
                "apellidoPaterno=$apellidoPaternoLimpio, " +
                "apellidoMaterno=$apellidoMaternoLimpio, " +
                "rfc=$rfcLimpio, " +
                "curp=$curpLimpia, " +
                "nss=$nssLimpio, " +
                "cargo=$cargoLimpio"
        )

        return EmployeeDto(

            idLocal =
                idLocal,

            employeeCode =
                codigoLimpio,

            firstName =
                nombreLimpio,

            lastNamePaternal =
                apellidoPaternoLimpio,

            lastNameMaternal =
                apellidoMaternoLimpio,

            fullName =
                nombreCompletoLimpio,

            rfc =
                rfcLimpio,

            curp =
                curpLimpia,

            nss =
                nssLimpio,

            department =
                cargoLimpio,

            isActive =
                isActive
        )
    }
}