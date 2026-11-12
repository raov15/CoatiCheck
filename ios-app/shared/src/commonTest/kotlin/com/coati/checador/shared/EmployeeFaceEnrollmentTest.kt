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

class EmployeeFaceEnrollmentTest {
    private class Engine(
        private val invalid: Boolean = false,
        override val versionModelo: String = "test_v1"
    ) : FaceRecognitionEngine {
        override val embeddingSize = 192
        override val isRealModelAvailable = true
        override fun generarEmbedding(faceImage: ByteArray): FloatArray = FloatArray(embeddingSize).also {
            if (!invalid) it[if (faceImage.first() == 1.toByte()) 0 else 1] = 1f
        }
    }

    private class Crypto : EmbeddingCrypto {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(blob: ByteArray) = blob.reversedArray()
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
        syncStatus = "PENDING"
    )

    @Test
    fun enrollmentStoresProfileAndRecognizesOnlyMatchingFace() = runTest {
        val db = CoatiCheckDatabase(createTestDriver())
        val engine = Engine()
        val crypto = Crypto()
        val repository = EmployeeRepositoryImpl(db, engine, crypto)
        assertTrue(repository.registerEmployee(employee(), byteArrayOf(1)))
        val recognize = RecognizeFaceUseCase(engine, crypto, FaceProfileRepositoryImpl(db))
        val match = recognize(byteArrayOf(1))
        assertIs<RecognizeFaceUseCase.MatchResult.Recognized>(match)
        assertEquals("employee-test", match.employeeId)
        assertIs<RecognizeFaceUseCase.MatchResult.NoMatch>(recognize(byteArrayOf(2)))
    }

    @Test
    fun profilesFromDifferentModelCannotBeRecognized() = runTest {
        val db = CoatiCheckDatabase(createTestDriver())
        val crypto = Crypto()
        assertTrue(EmployeeRepositoryImpl(db, Engine(), crypto).registerEmployee(employee(), byteArrayOf(1)))
        val recognize = RecognizeFaceUseCase(
            Engine(versionModelo = "other_v1"), crypto, FaceProfileRepositoryImpl(db)
        )
        assertIs<RecognizeFaceUseCase.MatchResult.Failure>(recognize(byteArrayOf(1)))
    }

    @Test
    fun invalidEmbeddingDoesNotCreateEmployee() = runTest {
        val db = CoatiCheckDatabase(createTestDriver())
        val repository = EmployeeRepositoryImpl(db, Engine(invalid = true), Crypto())
        assertFailsWith<IllegalArgumentException> {
            repository.registerEmployee(employee(), byteArrayOf(1))
        }
        assertEquals(0L, db.employees.countActive().executeAsOne())
    }
}
