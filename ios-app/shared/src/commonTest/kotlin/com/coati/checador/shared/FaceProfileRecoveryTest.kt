package com.coati.checador.shared

import com.coati.checador.shared.data.db.CoatiCheckDatabase
import com.coati.checador.shared.data.repository.EmployeeRepositoryImpl
import com.coati.checador.shared.data.repository.FaceProfileRepositoryImpl
import com.coati.checador.shared.domain.facerecognition.FaceRecognitionEngine
import com.coati.checador.shared.domain.model.Employee
import com.coati.checador.shared.domain.usecase.facerecognition.RecognizeFaceUseCase
import com.coati.checador.shared.security.EmbeddingCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FaceProfileRecoveryTest {
    private class Engine(private val invalid: Boolean = false) : FaceRecognitionEngine {
        override val versionModelo = "test_v1"
        override val embeddingSize = 192
        override val isRealModelAvailable = true
        override fun generarEmbedding(faceImage: ByteArray): FloatArray = FloatArray(embeddingSize).also {
            if (!invalid) it[0] = 1f
        }
    }

    private class Crypto(private val key: Byte) : EmbeddingCrypto {
        override fun encrypt(plain: ByteArray) = byteArrayOf(key) + plain
        override fun decrypt(blob: ByteArray): ByteArray {
            require(blob.first() == key)
            return blob.copyOfRange(1, blob.size)
        }
    }

    private fun employee() = Employee(
        idLocal = "employee-test",
        employeeCode = "TEST",
        firstName = "Prueba",
        lastNamePaternal = "Ejemplo",
        lastNameMaternal = "",
        fullName = "Prueba Ejemplo",
        rfc = "",
        curp = "",
        nss = "",
        department = "Programador",
        createdAt = 1L,
        updatedAt = 1L,
        syncStatus = "SYNCED"
    )

    @Test
    fun invalidProfileCanBeRecoveredWithoutReplacingEmployee() = runTest {
        val db = CoatiCheckDatabase(createTestDriver())
        val engine = Engine()
        assertTrue(EmployeeRepositoryImpl(db, engine, Crypto(1)).registerEmployee(employee(), byteArrayOf(1)))
        val crypto = Crypto(2)
        val repository = EmployeeRepositoryImpl(db, engine, crypto)
        val recognize = RecognizeFaceUseCase(engine, crypto, FaceProfileRepositoryImpl(db))
        assertIs<RecognizeFaceUseCase.MatchResult.Failure>(recognize(byteArrayOf(1)))

        repository.recoverInvalidFaceProfile("employee-test", byteArrayOf(1), 2L)

        val match = recognize(byteArrayOf(1))
        assertIs<RecognizeFaceUseCase.MatchResult.Recognized>(match)
        assertEquals("employee-test", match.employeeId)
        assertEquals(1L, db.employees.countActive().executeAsOne())
        assertEquals("SYNCED", db.employees.findById("employee-test").executeAsOne().sync_status)
        assertEquals(1L, db.faceProfiles.countActiveByEmployee("employee-test").executeAsOne())
    }

    @Test
    fun validProfileCannotBeReplaced() = runTest {
        val db = CoatiCheckDatabase(createTestDriver())
        val repository = EmployeeRepositoryImpl(db, Engine(), Crypto(1))
        assertTrue(repository.registerEmployee(employee(), byteArrayOf(1)))
        assertFailsWith<IllegalArgumentException> {
            repository.recoverInvalidFaceProfile("employee-test", byteArrayOf(1), 2L)
        }
        assertEquals(1L, db.faceProfiles.getActiveByEmployee("employee-test").executeAsOne().created_at)
    }

    @Test
    fun invalidNewEmbeddingDoesNotRemoveOldProfile() = runTest {
        val db = CoatiCheckDatabase(createTestDriver())
        assertTrue(EmployeeRepositoryImpl(db, Engine(), Crypto(1)).registerEmployee(employee(), byteArrayOf(1)))
        val repository = EmployeeRepositoryImpl(db, Engine(invalid = true), Crypto(2))
        assertFailsWith<IllegalArgumentException> {
            repository.recoverInvalidFaceProfile("employee-test", byteArrayOf(1), 2L)
        }
        assertEquals(1L, db.faceProfiles.getActiveByEmployee("employee-test").executeAsOne().created_at)
    }
}
