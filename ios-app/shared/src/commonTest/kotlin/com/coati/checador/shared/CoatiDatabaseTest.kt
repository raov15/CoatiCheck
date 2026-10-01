package com.coati.checador.shared

import com.coati.checador.shared.data.db.CoatiCheckDatabase
import com.coati.checador.shared.data.db.insertAttendance
import com.coati.checador.shared.data.db.insertEmployee
import com.coati.checador.shared.data.db.toDomain
import com.coati.checador.shared.domain.model.AttendanceRecord
import com.coati.checador.shared.domain.model.Employee
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoatiDatabaseTest {

    private fun newDb(): CoatiCheckDatabase = CoatiCheckDatabase(createTestDriver())

    private fun employee(
        id: String = "e1",
        code: String = "AUTO",
        status: String = "PENDING"
    ) = Employee(
        idLocal = id,
        employeeCode = code,
        firstName = "Juan",
        lastNamePaternal = "Pérez",
        lastNameMaternal = "López",
        fullName = "Juan Pérez López",
        rfc = "PELJ800101XXX",
        curp = "PELJ800101HDFXXX01",
        nss = "12345678901",
        department = "Programador",
        createdAt = 1_000L,
        updatedAt = 1_000L,
        syncStatus = status
    )

    private fun attendance(
        id: String = "a1",
        employeeId: String = "e1",
        status: String = "PENDING",
        attempts: Int = 0,
        occurredAt: Long = 1_700_000_000_000
    ) = AttendanceRecord(
        idLocal = id,
        employeeId = employeeId,
        eventType = "CLOCK_IN",
        occurredAt = occurredAt,
        latitude = 19.4326,
        longitude = -99.1332,
        accuracyM = 12.5f,
        faceConfidence = 0.92f,
        syncStatus = status,
        syncAttempts = attempts,
        createdAt = 1_700_000_000_000
    )

    // =========================================================
    // EMPLEADOS
    // =========================================================

    @Test
    fun `inserta y encuentra empleado por id`() {
        val db = newDb()
        db.insertEmployee(employee())

        val found = db.employees.findById("e1").executeAsOneOrNull()

        assertTrue(found != null)
        assertEquals("Juan Pérez López", found!!.toDomain().fullName)
        assertEquals("PENDING", found.sync_status)
    }

    @Test
    fun `countPending cuenta solo PENDING en mayusculas`() {
        val db = newDb()
        db.insertEmployee(employee(id = "e1", status = "PENDING"))
        db.insertEmployee(employee(id = "e2", status = "SYNCED"))
        db.insertEmployee(employee(id = "e3", status = "ERROR"))

        assertEquals(1L, db.employees.countPending().executeAsOne())
    }

    @Test
    fun `updateSyncResult persiste id_remote y estado`() {
        val db = newDb()
        db.insertEmployee(employee())

        db.employees.updateSyncResult("SYNCED", "RE-1", 2_000L, "e1")

        val row = db.employees.findById("e1").executeAsOne()
        assertEquals("SYNCED", row.sync_status)
        assertEquals("RE-1", row.id_remote)
        assertEquals(2_000L, row.updated_at)
    }

    @Test
    fun `findByCode devuelve empleado activo`() {
        val db = newDb()
        db.insertEmployee(employee(id = "e1", code = "EMP001"))

        val found = db.employees.findByCode("EMP001").executeAsOneOrNull()

        assertTrue(found != null)
        assertEquals("e1", found!!.id_local)
    }

    @Test
    fun `getAllActive excluye inactivos`() {
        val db = newDb()
        db.insertEmployee(employee(id = "e1"))
        db.insertEmployee(employee(id = "e2").copy(isActive = false))

        assertEquals(1, db.employees.getAllActive().executeAsList().size)
    }

    // =========================================================
    // ASISTENCIAS
    // =========================================================

    @Test
    fun `getPendingForSync devuelve pendientes y con error bajo el tope`() {
        val db = newDb()
        db.insertEmployee(employee())
        db.insertAttendance(attendance(id = "a1", status = "PENDING", occurredAt = 1L))
        db.insertAttendance(attendance(id = "a2", status = "ERROR", attempts = 9, occurredAt = 2L))
        db.insertAttendance(attendance(id = "a3", status = "ERROR", attempts = 10, occurredAt = 3L))
        db.insertAttendance(attendance(id = "a4", status = "SYNCED", occurredAt = 4L))

        val ids = db.attendance.getPendingForSync().executeAsList().map { it.id_local }
        assertEquals(listOf("a1", "a2"), ids)
    }

    @Test
    fun `updateSyncError incrementa intentos y guarda mensaje`() {
        val db = newDb()
        db.insertEmployee(employee())
        db.insertAttendance(attendance())

        db.attendance.updateSyncError("ERROR", "HTTP 500: fallo interno", "a1")

        val row = db.attendance.findById("a1").executeAsOne()
        assertEquals("ERROR", row.sync_status)
        assertEquals(1L, row.sync_attempts)
        assertEquals("HTTP 500: fallo interno", row.last_error)
    }

    @Test
    fun `updateSyncResult limpia last_error`() {
        val db = newDb()
        db.insertEmployee(employee())
        db.insertAttendance(attendance())
        db.attendance.updateSyncError("ERROR", "boom", "a1")

        db.attendance.updateSyncResult("SYNCED", "R-1", "a1")

        val row = db.attendance.findById("a1").executeAsOne()
        assertEquals("SYNCED", row.sync_status)
        assertEquals("R-1", row.id_remote)
        assertEquals(null, row.last_error)
    }

    @Test
    fun `mapping attendance redondea floats y nullable`() = runTest {
        val db = newDb()
        db.insertEmployee(employee())
        db.insertAttendance(attendance())

        val domain = db.attendance.findById("a1").executeAsOne().toDomain()

        assertEquals(12.5f, domain.accuracyM)
        assertEquals(0.92f, domain.faceConfidence)
        assertEquals(19.4326, domain.latitude)
    }

    // =========================================================
    // FOREIGN KEYS
    // =========================================================

    @Test
    fun `cascade elimina perfiles faciales al eliminar empleado`() {
        val db = newDb()
        db.insertEmployee(employee())
        db.faceProfiles.insertOrReplace(
            id = "f1",
            employee_id = "e1",
            embedding_blob = ByteArray(128) { it.toByte() },
            model_version = "mobilefacenet_v1",
            quality_score = 0.95,
            created_at = 1_000L,
            is_active = true
        )

        db.employees.deleteById("e1")

        assertEquals(0, db.faceProfiles.countActiveByEmployee("e1").executeAsOne())
    }

    @Test
    fun `work_sites persiste por dia con upsert`() {
        val db = newDb()
        db.insertEmployee(employee())

        db.workSites.insertOrReplace("e1", 1, "FOREIGN", null, null, 1_000L)
        db.workSites.insertOrReplace("e1", 1, "SITE", "S-9", "Centro Norte", 2_000L)

        val row = db.workSites.getForEmployeeAndWeekday("e1", 1).executeAsOne()
        assertEquals("SITE", row.work_mode)
        assertEquals("S-9", row.site_id)
        assertEquals(2_000L, row.updated_at)
    }

    // =========================================================
    // DISPOSITIVOS Y AJUSTES
    // =========================================================

    @Test
    fun `devices getCurrent devuelve el unico registro`() {
        val db = newDb()
        db.devices.insertOrReplace("local-1", null, "Tablet-01", null, null, 1_000L)

        val current = db.devices.getCurrent().executeAsOneOrNull()

        assertTrue(current != null)
        assertEquals("local-1", current!!.id_local)
    }

    @Test
    fun `settings upsert y observeValue`() = runTest {
        val db = newDb()

        db.settings.upsert("api_base_url", "https://api.coati.mx/api/", 1_000L)
        db.settings.upsert("api_base_url", "https://nuevo.coati.mx/api/", 2_000L)

        val value = db.settings.getValue("api_base_url").executeAsOneOrNull()?.value_
        assertEquals("https://nuevo.coati.mx/api/", value)
    }

    // =========================================================
    // SYNC QUEUE
    // =========================================================

    @Test
    fun `syncQueue getDue respeta tope de intentos y next_retry_at`() {
        val db = newDb()
        db.syncQueue.insertOrReplace("q1", "employee", "e1", "INSERT", null, 9L, null, null, 1_000L)
        db.syncQueue.insertOrReplace("q2", "employee", "e2", "INSERT", null, 10L, null, null, 1_000L)
        db.syncQueue.insertOrReplace("q3", "employee", "e3", "INSERT", null, 0L, 5_000L, null, 1_000L)

        val due = db.syncQueue.getDue(4_000L).executeAsList().map { it.id }
        assertEquals(listOf("q1"), due)
    }
}
