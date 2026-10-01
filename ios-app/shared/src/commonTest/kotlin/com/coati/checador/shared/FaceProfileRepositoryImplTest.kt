package com.coati.checador.shared

import com.coati.checador.shared.data.db.CoatiCheckDatabase
import com.coati.checador.shared.data.db.insertEmployee
import com.coati.checador.shared.data.repository.FaceProfileRepositoryImpl
import com.coati.checador.shared.domain.model.Employee
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FaceProfileRepositoryImplTest {

    private fun newDb(): CoatiCheckDatabase = CoatiCheckDatabase(createTestDriver())

    private fun employee(id: String, name: String, active: Boolean = true) = Employee(
        idLocal = id,
        employeeCode = "C-$id",
        firstName = name,
        lastNamePaternal = "Apellido",
        lastNameMaternal = "",
        fullName = "$name Apellido",
        rfc = "",
        curp = "",
        nss = "",
        department = "",
        createdAt = 1_000L,
        updatedAt = 1_000L,
        syncStatus = "SYNCED",
        isActive = active
    )

    private fun insertProfile(
        db: CoatiCheckDatabase,
        id: String,
        employeeId: String,
        blob: ByteArray = ByteArray(8) { 1 },
        active: Boolean = true
    ) {
        db.faceProfiles.insertOrReplace(
            id = id,
            employee_id = employeeId,
            embedding_blob = blob,
            model_version = "test_v1",
            quality_score = 0.8,
            created_at = 1_000L,
            is_active = active
        )
    }

    @Test
    fun `devuelve solo perfiles activos de empleados activos`() = runTest {
        val db = newDb()
        db.insertEmployee(employee("e1", "Ana"))
        db.insertEmployee(employee("e2", "Beto", active = false))
        insertProfile(db, "p1", "e1", active = true)
        insertProfile(db, "p2", "e1", active = false)   // perfil inactivo
        insertProfile(db, "p3", "e2", active = true)   // empleado inactivo

        val profiles = FaceProfileRepositoryImpl(db).getActiveProfilesWithEmployee()

        assertEquals(1, profiles.size)
        assertEquals("p1", profiles[0].id)
        assertEquals("e1", profiles[0].employeeId)
        assertEquals("Ana Apellido", profiles[0].employeeName)
        assertTrue(profiles[0].embeddingBlob.contentEquals(ByteArray(8) { 1 }))
        assertEquals("test_v1", profiles[0].modelVersion)
        assertEquals(0.8f, profiles[0].qualityScore)
    }

    @Test
    fun `sin perfiles devuelve lista vacia`() = runTest {
        val db = newDb()
        db.insertEmployee(employee("e1", "Ana"))
        assertTrue(FaceProfileRepositoryImpl(db).getActiveProfilesWithEmployee().isEmpty())
    }
}
