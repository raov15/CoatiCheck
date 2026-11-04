package com.coati.checador.shared.domain.sync

import com.coati.checador.shared.data.db.CoatiCheckDatabase
import com.coati.checador.shared.data.network.dto.AttendanceRecordDto
import com.coati.checador.shared.data.network.dto.AttendanceSyncRequest
import com.coati.checador.shared.data.network.dto.EmployeeDto
import com.coati.checador.shared.data.network.dto.EmployeeSyncRequest
import com.coati.checador.shared.data.network.dto.ForeignLocationPointDto
import com.coati.checador.shared.data.network.dto.ForeignLocationSyncRequest
import com.coati.checador.shared.data.network.isAuthError
import com.coati.checador.shared.domain.model.enums.SyncStatus
import kotlinx.datetime.Clock

/**
 * Orquestador de sincronización offline-first.
 *
 * Orden idéntico al de los workers de la app Android:
 * 1. Subir empleados pendientes.
 * 2. Descargar work-assignments (configuración SITE / FOREIGN).
 * 3. Subir asistencias.
 * 4. Subir ubicaciones foráneas.
 *
 * Ante 401/403 refresca el token una vez por paso y reintenta.
 * Los registros marcan ERROR con tope de 10 reintentos (en las tablas
 * attendance y foreign locations el tope ya lo aplica la query; aquí se
 * marca el estado).
 */
class SyncOrchestrator(
    private val api: SyncApi,
    private val db: CoatiCheckDatabase,
    private val tokenStore: TokenStore,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) {

    sealed class StepResult {
        data object Ok : StepResult()
        data class OkWithErrors(val count: Int, val lastError: String) : StepResult()
        data class Failed(val message: String) : StepResult()
        data class Skipped(val message: String) : StepResult()
    }

    class SyncReport(
        val employees: StepResult,
        val workAssignments: StepResult,
        val attendance: StepResult,
        val foreignLocations: StepResult
    ) {
        val hasHardFailure: Boolean
            get() = listOf(employees, workAssignments, attendance, foreignLocations)
                .any { it is StepResult.Failed }
    }

    suspend fun syncAll(): SyncReport {
        val employees = syncPendingEmployees()
        val assignments = downloadWorkAssignments()
        val attendance = syncPendingAttendance()
        val locations = syncPendingForeignLocations()
        return SyncReport(employees, assignments, attendance, locations)
    }

    // =========================================================
    // PASO 1: EMPLEADOS
    // =========================================================

    suspend fun syncPendingEmployees(): StepResult {
        val pending = db.employees.findBySyncStatus(SyncStatus.PENDING)
            .executeAsList()
        if (pending.isEmpty()) return StepResult.Skipped("Sin empleados pendientes")

        val bearer = tokenStore.getBearer()
            ?: return StepResult.Failed("Sin token de dispositivo")

        val request = EmployeeSyncRequest(
            employees = pending.map { row ->
                EmployeeDto(
                    idLocal = row.id_local,
                    employeeCode = row.employee_code,
                    firstName = row.first_name,
                    lastNamePaternal = row.last_name_paternal,
                    lastNameMaternal = row.last_name_maternal,
                    fullName = row.full_name,
                    rfc = row.rfc,
                    curp = row.curp,
                    nss = row.nss,
                    department = row.department,
                    isActive = row.is_active
                )
            }
        )

        return try {
            val response = withTokenRefresh(bearer) { token ->
                api.syncEmployees(token, request)
            }
            for (synced in response.synced) {
                db.employees.updateSyncResult(
                    sync_status = SyncStatus.SYNCED,
                    id_remote = synced.idRemote,
                    updated_at = nowMillis(),
                    id_local = synced.idLocal
                )
            }
            for (error in response.errors) {
                db.employees.updateSyncStatus(
                    sync_status = SyncStatus.ERROR,
                    id_local = error.idLocal
                )
            }
            if (response.errors.isEmpty()) {
                StepResult.Ok
            } else {
                StepResult.OkWithErrors(
                    response.errors.size,
                    response.errors.last().error
                )
            }
        } catch (e: Exception) {
            markEmployeesError(pending.map { it.id_local }, e.message ?: "Error de red")
            StepResult.Failed("employees/sync: ${e.message}")
        }
    }

    private fun markEmployeesError(ids: List<String>, message: String) {
        for (id in ids) {
            db.employees.updateSyncStatus(
                sync_status = SyncStatus.ERROR,
                id_local = id
            )
        }
    }

    // =========================================================
    // PASO 2: WORK ASSIGNMENTS
    // =========================================================

    suspend fun downloadWorkAssignments(): StepResult {
        val bearer = tokenStore.getBearer()
            ?: return StepResult.Failed("Sin token de dispositivo")

        return try {
            val response = withTokenRefresh(bearer) { token ->
                api.getEmployeeWorkAssignments(token)
            }
            var inserted = 0
            var ignored = 0
            db.transaction {
                db.workSites.deleteAll()
                for (assignment in response.assignments) {
                    // FK hacia employees: solo se insertan asignaciones de
                    // empleados que existen localmente.
                    val exists = db.employees.findById(assignment.employeeIdLocal)
                        .executeAsOneOrNull() != null
                    if (!exists) {
                        ignored++
                        continue
                    }
                    db.workSites.insertOrReplace(
                        employee_id = assignment.employeeIdLocal,
                        weekday = assignment.weekday.toLong(),
                        work_mode = assignment.workMode,
                        site_id = assignment.siteId,
                        site_name = assignment.siteName,
                        updated_at = nowMillis()
                    )
                    inserted++
                }
            }
            if (ignored == 0) {
                StepResult.Ok
            } else {
                StepResult.OkWithErrors(ignored, "$ignored asignaciones sin empleado local")
            }
        } catch (e: Exception) {
            StepResult.Failed("employees/work-assignments: ${e.message}")
        }
    }

    // =========================================================
    // PASO 3: ASISTENCIAS
    // =========================================================

    suspend fun syncPendingAttendance(): StepResult {
        val pending = db.attendance.getPendingForSync().executeAsList()
        if (pending.isEmpty()) return StepResult.Skipped("Sin asistencias pendientes")

        val bearer = tokenStore.getBearer()
            ?: return StepResult.Failed("Sin token de dispositivo")

        val request = AttendanceSyncRequest(
            records = pending.map { row ->
                AttendanceRecordDto(
                    idLocal = row.id_local,
                    employeeId = row.employee_id,
                    eventType = row.event_type,
                    occurredAt = row.occurred_at,
                    latitude = row.latitude,
                    longitude = row.longitude,
                    accuracyM = row.accuracy_m?.toFloat(),
                    altitudeM = row.altitude_m,
                    faceConfidence = row.face_confidence?.toFloat(),
                    siteId = row.site_id
                )
            }
        )

        return try {
            val response = withTokenRefresh(bearer) { token ->
                api.syncAttendance(token, request)
            }
            for (synced in response.synced) {
                db.attendance.updateSyncResult(
                    sync_status = SyncStatus.SYNCED,
                    id_remote = synced.idRemote,
                    id_local = synced.idLocal
                )
            }
            for (error in response.errors) {
                db.attendance.updateSyncError(
                    sync_status = SyncStatus.ERROR,
                    last_error = error.error,
                    id_local = error.idLocal
                )
            }
            stepResultFrom(response.errors.size, response.errors.lastOrNull()?.error)
        } catch (e: Exception) {
            StepResult.Failed("attendance/sync: ${e.message}")
        }
    }

    // =========================================================
    // PASO 4: UBICACIONES FORÁNEAS
    // =========================================================

    suspend fun syncPendingForeignLocations(): StepResult {
        val pending = db.foreignLocations.getPendingForSync().executeAsList()
        if (pending.isEmpty()) return StepResult.Skipped("Sin ubicaciones pendientes")

        val bearer = tokenStore.getBearer()
            ?: return StepResult.Failed("Sin token de dispositivo")

        val request = ForeignLocationSyncRequest(
            points = pending.map { row ->
                ForeignLocationPointDto(
                    idLocal = row.id_local,
                    employeeId = row.employee_id,
                    workDate = row.work_date,
                    occurredAt = row.occurred_at,
                    latitude = row.latitude,
                    longitude = row.longitude,
                    accuracyM = row.accuracy_m?.toFloat(),
                    altitudeM = row.altitude_m,
                    deviceId = row.device_id
                )
            }
        )

        return try {
            val response = withTokenRefresh(bearer) { token ->
                api.syncForeignLocations(token, request)
            }
            for (synced in response.synced) {
                db.foreignLocations.updateSyncResult(
                    sync_status = SyncStatus.SYNCED,
                    id_remote = synced.idRemote,
                    id_local = synced.idLocal
                )
            }
            for (error in response.errors) {
                db.foreignLocations.updateSyncError(
                    sync_status = SyncStatus.ERROR,
                    last_error = error.error,
                    id_local = error.idLocal
                )
            }
            stepResultFrom(response.errors.size, response.errors.lastOrNull()?.error)
        } catch (e: Exception) {
            StepResult.Failed("attendance/location/sync: ${e.message}")
        }
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private fun stepResultFrom(errorCount: Int, lastError: String?): StepResult =
        if (errorCount == 0) {
            StepResult.Ok
        } else {
            StepResult.OkWithErrors(errorCount, lastError ?: "Error desconocido")
        }

    /**
     * Ejecuta [block] con el token actual; si el servidor responde 401/403,
     * refresca el token una vez y reintenta.
     */
    private suspend fun <T> withTokenRefresh(
        bearer: String,
        block: suspend (String) -> T
    ): T {
        return try {
            block(bearer)
        } catch (e: Exception) {
            if (!e.isAuthError()) throw e
            val refreshed = api.refreshToken(bearer).authToken
            tokenStore.setBearer("Bearer $refreshed")
            block("Bearer $refreshed")
        }
    }
}
