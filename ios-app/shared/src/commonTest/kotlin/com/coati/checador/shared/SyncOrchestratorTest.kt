package com.coati.checador.shared

import com.coati.checador.shared.data.db.CoatiCheckDatabase
import com.coati.checador.shared.data.db.insertEmployee
import com.coati.checador.shared.data.network.CoatiApiException
import com.coati.checador.shared.data.network.dto.AttendanceSyncRequest
import com.coati.checador.shared.data.network.dto.AttendanceSyncResponse
import com.coati.checador.shared.data.network.dto.AttendanceSyncedItem
import com.coati.checador.shared.data.network.dto.DeviceTokenRefreshResponse
import com.coati.checador.shared.data.network.dto.EmployeeDto
import com.coati.checador.shared.data.network.dto.EmployeeSyncRequest
import com.coati.checador.shared.data.network.dto.EmployeeSyncResponse
import com.coati.checador.shared.data.network.dto.EmployeeSyncedDto
import com.coati.checador.shared.data.network.dto.EmployeeWorkAssignmentDto
import com.coati.checador.shared.data.network.dto.EmployeeWorkAssignmentsResponse
import com.coati.checador.shared.data.network.dto.ForeignLocationSyncRequest
import com.coati.checador.shared.data.network.dto.ForeignLocationSyncResponse
import com.coati.checador.shared.data.network.dto.ForeignLocationSyncedItem
import com.coati.checador.shared.domain.model.Employee
import com.coati.checador.shared.domain.model.enums.SyncStatus
import com.coati.checador.shared.domain.sync.SyncApi
import com.coati.checador.shared.domain.sync.SyncOrchestrator
import com.coati.checador.shared.domain.sync.TokenStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SyncOrchestratorTest {

    // =========================================================
    // FAKES
    // =========================================================

    private class FakeTokenStore(var initial: String? = "Bearer token-viejo") : TokenStore {
        var current: String? = initial
            private set
        var refreshCount = 0

        override fun getBearer(): String? = current

        override fun setBearer(rawToken: String) {
            refreshCount++
            current = rawToken
        }
    }

    /**
     * API falsa: por defecto todo OK; se pueden inyectar respuestas y
     * fallos (incluido 401 una sola vez para probar el refresh).
     */
    private class FakeSyncApi : SyncApi {
        var employeeResponse = EmployeeSyncResponse()
        var assignmentsResponse = EmployeeWorkAssignmentsResponse(emptyList())
        var attendanceResponse = AttendanceSyncResponse()
        var foreignLocationResponse = ForeignLocationSyncResponse()

        var failEmployeesOnce: Exception? = null
        var failAttendanceOnce: Exception? = null

        var lastEmployeeRequest: EmployeeSyncRequest? = null
        var lastAttendanceRequest: AttendanceSyncRequest? = null
        var lastLocationRequest: ForeignLocationSyncRequest? = null
        var receivedBearers = mutableListOf<String>()

        private var employeesFailed = false
        private var attendanceFailed = false

        override suspend fun syncEmployees(
            bearerToken: String,
            request: EmployeeSyncRequest
        ): EmployeeSyncResponse {
            receivedBearers.add(bearerToken)
            if (!employeesFailed && failEmployeesOnce != null) {
                employeesFailed = true
                throw failEmployeesOnce!!
            }
            lastEmployeeRequest = request
            return employeeResponse
        }

        override suspend fun getEmployeeWorkAssignments(
            bearerToken: String
        ): EmployeeWorkAssignmentsResponse {
            receivedBearers.add(bearerToken)
            return assignmentsResponse
        }

        override suspend fun syncAttendance(
            bearerToken: String,
            request: AttendanceSyncRequest
        ): AttendanceSyncResponse {
            receivedBearers.add(bearerToken)
            if (!attendanceFailed && failAttendanceOnce != null) {
                attendanceFailed = true
                throw failAttendanceOnce!!
            }
            lastAttendanceRequest = request
            return attendanceResponse
        }

        override suspend fun syncForeignLocations(
            bearerToken: String,
            request: ForeignLocationSyncRequest
        ): ForeignLocationSyncResponse {
            receivedBearers.add(bearerToken)
            lastLocationRequest = request
            return foreignLocationResponse
        }

        override suspend fun refreshToken(bearerToken: String): DeviceTokenRefreshResponse {
            receivedBearers.add(bearerToken)
            return DeviceTokenRefreshResponse(authToken = "token-nuevo")
        }
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private fun newDb() = CoatiCheckDatabase(createTestDriver())

    private fun employee(id: String, syncStatus: String = SyncStatus.PENDING) = Employee(
        idLocal = id,
        employeeCode = "C-$id",
        firstName = "Nombre",
        lastNamePaternal = "Apellido",
        lastNameMaternal = "",
        fullName = "Nombre Apellido",
        rfc = "",
        curp = "",
        nss = "",
        department = "",
        createdAt = 1_000L,
        updatedAt = 1_000L,
        syncStatus = syncStatus,
        isActive = true
    )

    private fun insertAttendance(
        db: CoatiCheckDatabase,
        id: String,
        employeeId: String,
        occurredAt: Long = 2_000L
    ) {
        db.attendance.insertOrReplace(
            id_local = id,
            id_remote = null,
            employee_id = employeeId,
            event_type = "CLOCK_IN",
            occurred_at = occurredAt,
            is_late = false,
            late_minutes = 0L,
            latitude = 20.6,
            longitude = -103.3,
            accuracy_m = 10.0,
            altitude_m = 1_560.0,
            face_confidence = 0.97,
            device_id = "dev-1",
            site_id = null,
            sync_status = SyncStatus.PENDING,
            sync_attempts = 0L,
            last_error = null,
            created_at = 2_500L
        )
    }

    private fun insertForeignLocation(
        db: CoatiCheckDatabase,
        id: String,
        employeeId: String
    ) {
        db.foreignLocations.insertOrReplace(
            id_local = id,
            id_remote = null,
            employee_id = employeeId,
            work_date = "2026-10-05",
            occurred_at = 3_000L,
            latitude = 20.6,
            longitude = -103.3,
            accuracy_m = 10.0,
            altitude_m = 1_560.0,
            device_id = "dev-1",
            sync_status = SyncStatus.PENDING,
            sync_attempts = 0L,
            last_error = null,
            created_at = 3_500L
        )
    }

    private fun attendanceStatus(db: CoatiCheckDatabase, id: String): String? =
        db.attendance.findById(id).executeAsOneOrNull()?.sync_status

    private fun foreignLocationStatus(db: CoatiCheckDatabase, id: String): String? =
        db.foreignLocations.findById(id).executeAsOneOrNull()?.sync_status

    // =========================================================
    // PASO 1: EMPLEADOS
    // =========================================================

    @Test
    fun `sube empleados pendientes y marca SYNCED con id remoto`() = runTest {
        val db = newDb()
        db.insertEmployee(employee("e1"))
        db.insertEmployee(employee("e2"))
        val api = FakeSyncApi().apply {
            employeeResponse = EmployeeSyncResponse(
                synced = listOf(
                    EmployeeSyncedDto("e1", "remote-1"),
                    EmployeeSyncedDto("e2", "remote-2")
                )
            )
        }
        val tokens = FakeTokenStore()
        val orchestrator = SyncOrchestrator(api, db, tokens)

        val result = orchestrator.syncPendingEmployees()

        assertIs<SyncOrchestrator.StepResult.Ok>(result)
        assertEquals(2, api.lastEmployeeRequest?.employees?.size)
        val row = db.employees.findById("e1").executeAsOneOrNull()
        assertEquals(SyncStatus.SYNCED, row?.sync_status)
        assertEquals("remote-1", row?.id_remote)
    }

    @Test
    fun `sin empleados pendientes devuelve Skipped`() = runTest {
        val db = newDb()
        db.insertEmployee(employee("e1", syncStatus = SyncStatus.SYNCED))
        val orchestrator = SyncOrchestrator(FakeSyncApi(), db, FakeTokenStore())

        assertIs<SyncOrchestrator.StepResult.Skipped>(orchestrator.syncPendingEmployees())
    }

    @Test
    fun `errores del servidor marcan empleados como ERROR`() = runTest {
        val db = newDb()
        db.insertEmployee(employee("e1"))
        val api = FakeSyncApi().apply {
            employeeResponse = EmployeeSyncResponse(
                errors = listOf(
                    com.coati.checador.shared.data.network.dto.EmployeeSyncErrorDto("e1", "duplicado")
                )
            )
        }
        val orchestrator = SyncOrchestrator(api, db, FakeTokenStore())

        val result = orchestrator.syncPendingEmployees()

        assertIs<SyncOrchestrator.StepResult.OkWithErrors>(result)
        assertEquals(1, result.count)
        assertEquals(SyncStatus.ERROR, db.employees.findById("e1").executeAsOneOrNull()?.sync_status)
    }

    @Test
    fun `sin token devuelve Failed`() = runTest {
        val db = newDb()
        db.insertEmployee(employee("e1"))
        val orchestrator = SyncOrchestrator(FakeSyncApi(), db, FakeTokenStore(initial = null))

        assertIs<SyncOrchestrator.StepResult.Failed>(orchestrator.syncPendingEmployees())
    }

    @Test
    fun `fallo de red marca empleados ERROR y devuelve Failed`() = runTest {
        val db = newDb()
        db.insertEmployee(employee("e1"))
        val api = FakeSyncApi().apply {
            failEmployeesOnce = RuntimeException("sin conexión")
        }
        val orchestrator = SyncOrchestrator(api, db, FakeTokenStore())

        val result = orchestrator.syncPendingEmployees()

        assertIs<SyncOrchestrator.StepResult.Failed>(result)
        assertEquals(SyncStatus.ERROR, db.employees.findById("e1").executeAsOneOrNull()?.sync_status)
    }

    // =========================================================
    // REFRESH DE TOKEN 401/403
    // =========================================================

    @Test
    fun `ante 401 refresca el token y reintenta con exito`() = runTest {
        val db = newDb()
        db.insertEmployee(employee("e1"))
        val api = FakeSyncApi().apply {
            failEmployeesOnce = CoatiApiException(401, "token expirado")
            employeeResponse = EmployeeSyncResponse(
                synced = listOf(EmployeeSyncedDto("e1", "remote-1"))
            )
        }
        val tokens = FakeTokenStore()
        val orchestrator = SyncOrchestrator(api, db, tokens)

        val result = orchestrator.syncPendingEmployees()

        assertIs<SyncOrchestrator.StepResult.Ok>(result)
        assertEquals("Bearer token-nuevo", tokens.current)
        assertEquals(1, tokens.refreshCount)
        // La petición exitosa usó el token nuevo.
        assertEquals("Bearer token-nuevo", api.receivedBearers.last())
        assertEquals(SyncStatus.SYNCED, db.employees.findById("e1").executeAsOneOrNull()?.sync_status)
    }

    @Test
    fun `ante 403 tambien refresca el token`() = runTest {
        val db = newDb()
        db.insertEmployee(employee("e1"))
        val api = FakeSyncApi().apply {
            failEmployeesOnce = CoatiApiException(403, "prohibido")
        }
        val tokens = FakeTokenStore()
        val orchestrator = SyncOrchestrator(api, db, tokens)

        orchestrator.syncPendingEmployees()

        assertEquals("Bearer token-nuevo", tokens.current)
    }

    @Test
    fun `errores no auth no disparan refresh`() = runTest {
        val db = newDb()
        db.insertEmployee(employee("e1"))
        val api = FakeSyncApi().apply {
            failEmployeesOnce = CoatiApiException(500, "error interno")
        }
        val tokens = FakeTokenStore()
        val orchestrator = SyncOrchestrator(api, db, tokens)

        assertIs<SyncOrchestrator.StepResult.Failed>(orchestrator.syncPendingEmployees())
        assertEquals(0, tokens.refreshCount)
        assertEquals("Bearer token-viejo", tokens.current)
    }

    // =========================================================
    // PASO 2: WORK ASSIGNMENTS
    // =========================================================

    @Test
    fun `descarga asignaciones e inserta solo empleados locales`() = runTest {
        val db = newDb()
        db.insertEmployee(employee("e1"))
        val api = FakeSyncApi().apply {
            assignmentsResponse = EmployeeWorkAssignmentsResponse(
                listOf(
                    EmployeeWorkAssignmentDto(
                        employeeIdRemote = "remote-1",
                        employeeIdLocal = "e1",
                        employeeCode = "C-e1",
                        weekday = 1,
                        workMode = "SITE",
                        siteId = "site-1",
                        siteName = "Planta Norte"
                    ),
                    EmployeeWorkAssignmentDto(
                        employeeIdRemote = "remote-x",
                        employeeIdLocal = "e-x", // no existe local
                        employeeCode = "C-ex",
                        weekday = 1,
                        workMode = "FOREIGN"
                    )
                )
            )
        }
        val orchestrator = SyncOrchestrator(api, db, FakeTokenStore())

        val result = orchestrator.downloadWorkAssignments()

        assertIs<SyncOrchestrator.StepResult.OkWithErrors>(result)
        assertEquals(1, result.count) // una asignación ignorada
        val saved = db.workSites.getForEmployee("e1").executeAsList()
        assertEquals(1, saved.size)
        assertEquals("SITE", saved[0].work_mode)
        assertEquals("Planta Norte", saved[0].site_name)
    }

    @Test
    fun `descarga limpia asignaciones anteriores`() = runTest {
        val db = newDb()
        db.insertEmployee(employee("e1"))
        db.workSites.insertOrReplace(
            employee_id = "e1",
            weekday = 1L,
            work_mode = "FOREIGN",
            site_id = null,
            site_name = null,
            updated_at = 1_000L
        )
        val api = FakeSyncApi() // sin asignaciones
        val orchestrator = SyncOrchestrator(api, db, FakeTokenStore())

        assertIs<SyncOrchestrator.StepResult.Ok>(orchestrator.downloadWorkAssignments())
        assertTrue(db.workSites.getForEmployee("e1").executeAsList().isEmpty())
    }

    // =========================================================
    // PASO 3: ASISTENCIAS
    // =========================================================

    @Test
    fun `sube asistencias pendientes y marca SYNCED`() = runTest {
        val db = newDb()
        db.insertEmployee(employee("e1"))
        insertAttendance(db, "a1", "e1")
        val api = FakeSyncApi().apply {
            attendanceResponse = AttendanceSyncResponse(
                synced = listOf(AttendanceSyncedItem("a1", "remote-a1"))
            )
        }
        val orchestrator = SyncOrchestrator(api, db, FakeTokenStore())

        val result = orchestrator.syncPendingAttendance()

        assertIs<SyncOrchestrator.StepResult.Ok>(result)
        assertEquals(1, api.lastAttendanceRequest?.records?.size)
        assertEquals(SyncStatus.SYNCED, attendanceStatus(db, "a1"))
    }

    @Test
    fun `ante 401 en asistencias refresca y reintenta`() = runTest {
        val db = newDb()
        db.insertEmployee(employee("e1"))
        insertAttendance(db, "a1", "e1")
        val api = FakeSyncApi().apply {
            failAttendanceOnce = CoatiApiException(401, "token expirado")
            attendanceResponse = AttendanceSyncResponse(
                synced = listOf(AttendanceSyncedItem("a1", "remote-a1"))
            )
        }
        val tokens = FakeTokenStore()
        val orchestrator = SyncOrchestrator(api, db, tokens)

        val result = orchestrator.syncPendingAttendance()

        assertIs<SyncOrchestrator.StepResult.Ok>(result)
        assertEquals(1, tokens.refreshCount)
        assertEquals(SyncStatus.SYNCED, attendanceStatus(db, "a1"))
    }

    @Test
    fun `error por registro marca ERROR e incrementa intentos`() = runTest {
        val db = newDb()
        db.insertEmployee(employee("e1"))
        insertAttendance(db, "a1", "e1")
        val api = FakeSyncApi().apply {
            attendanceResponse = AttendanceSyncResponse(
                errors = listOf(
                    com.coati.checador.shared.data.network.dto.AttendanceSyncErrorItem("a1", "datos inválidos")
                )
            )
        }
        val orchestrator = SyncOrchestrator(api, db, FakeTokenStore())

        val result = orchestrator.syncPendingAttendance()

        assertIs<SyncOrchestrator.StepResult.OkWithErrors>(result)
        val row = db.attendance.findById("a1").executeAsOneOrNull()
        assertEquals(SyncStatus.ERROR, row?.sync_status)
        assertEquals(1L, row?.sync_attempts)
        assertEquals("datos inválidos", row?.last_error)
    }

    // =========================================================
    // PASO 4: UBICACIONES FORÁNEAS
    // =========================================================

    @Test
    fun `sube ubicaciones foraneas pendientes y marca SYNCED`() = runTest {
        val db = newDb()
        db.insertEmployee(employee("e1"))
        insertForeignLocation(db, "f1", "e1")
        val api = FakeSyncApi().apply {
            foreignLocationResponse = ForeignLocationSyncResponse(
                synced = listOf(ForeignLocationSyncedItem("f1", "remote-f1"))
            )
        }
        val orchestrator = SyncOrchestrator(api, db, FakeTokenStore())

        val result = orchestrator.syncPendingForeignLocations()

        assertIs<SyncOrchestrator.StepResult.Ok>(result)
        assertEquals(1, api.lastLocationRequest?.points?.size)
        assertEquals(SyncStatus.SYNCED, foreignLocationStatus(db, "f1"))
    }

    // =========================================================
    // ORQUESTACIÓN COMPLETA
    // =========================================================

    @Test
    fun `syncAll con todo vacio devuelve Skipped en los cuatro pasos`() = runTest {
        val db = newDb()
        val orchestrator = SyncOrchestrator(FakeSyncApi(), db, FakeTokenStore())

        val report = orchestrator.syncAll()

        assertIs<SyncOrchestrator.StepResult.Skipped>(report.employees)
        // assignments no tiene pendientes: se ejecuta pero sin datos
        assertIs<SyncOrchestrator.StepResult.Ok>(report.workAssignments)
        assertIs<SyncOrchestrator.StepResult.Skipped>(report.attendance)
        assertIs<SyncOrchestrator.StepResult.Skipped>(report.foreignLocations)
        assertTrue(!report.hasHardFailure)
    }

    @Test
    fun `syncAll con fallo duro de red reporta hasHardFailure`() = runTest {
        val db = newDb()
        db.insertEmployee(employee("e1"))
        val api = FakeSyncApi().apply {
            failEmployeesOnce = RuntimeException("sin conexión")
        }
        val orchestrator = SyncOrchestrator(api, db, FakeTokenStore())

        val report = orchestrator.syncAll()

        assertIs<SyncOrchestrator.StepResult.Failed>(report.employees)
        assertTrue(report.hasHardFailure)
    }
}
