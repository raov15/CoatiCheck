package com.coati.checador.shared

import com.coati.checador.shared.domain.facerecognition.FaceMatcher
import com.coati.checador.shared.domain.facerecognition.FaceRecognitionEngine
import com.coati.checador.shared.domain.model.EnrolledFaceProfile
import com.coati.checador.shared.domain.repository.FaceProfileRepository
import com.coati.checador.shared.domain.usecase.facerecognition.RecognizeFaceUseCase
import com.coati.checador.shared.domain.usecase.facerecognition.RecognizeFaceUseCase.MatchResult
import com.coati.checador.shared.security.EmbeddingCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RecognizeFaceUseCaseTest {

    private val size = 192

    /** Engine determinista: el embedding depende del contenido de la imagen. */
    private class FakeEngine(private val failing: Boolean = false) : FaceRecognitionEngine {
        override val versionModelo = "fake_v1"
        override val embeddingSize = 192
        override val isRealModelAvailable = true
        override fun generarEmbedding(faceImage: ByteArray): FloatArray {
            if (failing) throw IllegalArgumentException("imagen inválida")
            var state = (faceImage.contentHashCode().toLong() and 0xffffffffL) or 1L
            val raw = FloatArray(embeddingSize) {
                state = state xor (state shl 13); state = state xor (state ushr 7); state = state xor (state shl 17)
                ((state and 0xffffffL).toDouble() / 0xffffffL.toDouble()).toFloat() * 2f - 1f
            }
            return FaceMatcher.normalizarL2(raw)
        }
    }

    /** Cifrado reversible trivial para tests. */
    private class FakeCrypto : EmbeddingCrypto {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(blob: ByteArray) = blob.reversedArray()
    }

    private class FakeRepository(
        val profiles: List<EnrolledFaceProfile> = emptyList(),
        val failing: Boolean = false
    ) : FaceProfileRepository {
        override suspend fun getActiveProfilesWithEmployee(): List<EnrolledFaceProfile> {
            if (failing) throw IllegalStateException("BD no disponible")
            return profiles
        }
    }

    private fun profile(engine: FakeEngine, id: String, name: String, image: ByteArray): EnrolledFaceProfile {
        val embeddingBytes = FaceMatcher.floatsToBytes(engine.generarEmbedding(image))
        return EnrolledFaceProfile(
            id = "profile-$id",
            employeeId = id,
            employeeName = name,
            embeddingBlob = FakeCrypto().encrypt(embeddingBytes),
            modelVersion = "fake_v1",
            qualityScore = 0.9f
        )
    }

    @Test
    fun `rostro enrolado es reconocido con su nombre y alta confianza`() = runTest {
        val engine = FakeEngine()
        val photoAna = ByteArray(256) { 11 }
        val photoBeto = ByteArray(256) { 22 }
        val repo = FakeRepository(
            listOf(
                profile(engine, "emp-ana", "Ana López", photoAna),
                profile(engine, "emp-beto", "Beto García", photoBeto)
            )
        )
        val useCase = RecognizeFaceUseCase(engine, FakeCrypto(), repo)

        val result = useCase(photoAna)
        assertIs<MatchResult.Recognized>(result)
        assertEquals("emp-ana", result.employeeId)
        assertEquals("Ana López", result.employeeName)
        assertTrue(result.confidence > 0.95f, "confianza=${result.confidence}")
    }

    @Test
    fun `rostro no enrolado devuelve NoMatch`() = runTest {
        val engine = FakeEngine()
        val repo = FakeRepository(
            listOf(profile(engine, "emp-ana", "Ana López", ByteArray(256) { 11 }))
        )
        val useCase = RecognizeFaceUseCase(engine, FakeCrypto(), repo)

        val unknownPhoto = ByteArray(256) { -37 }
        assertIs<MatchResult.NoMatch>(useCase(unknownPhoto))
    }

    @Test
    fun `sin perfiles enrolados devuelve NoMatch`() = runTest {
        val useCase = RecognizeFaceUseCase(FakeEngine(), FakeCrypto(), FakeRepository())
        assertIs<MatchResult.NoMatch>(useCase(ByteArray(256) { 11 }))
    }

    @Test
    fun `elige al perfil mas cercano no al primero de la lista`() = runTest {
        val engine = FakeEngine()
        val photoAna = ByteArray(256) { 11 }
        val photoBeto = ByteArray(256) { 22 }
        // Ana primero en la lista, pero la foto es de Beto.
        val repo = FakeRepository(
            listOf(
                profile(engine, "emp-ana", "Ana López", photoAna),
                profile(engine, "emp-beto", "Beto García", photoBeto)
            )
        )
        val useCase = RecognizeFaceUseCase(engine, FakeCrypto(), repo)

        val result = useCase(photoBeto)
        assertIs<MatchResult.Recognized>(result)
        assertEquals("emp-beto", result.employeeId)
    }

    @Test
    fun `perfil con blob corrupto se ignora`() = runTest {
        val engine = FakeEngine()
        val photoAna = ByteArray(256) { 11 }
        val corrupto = EnrolledFaceProfile(
            id = "profile-x",
            employeeId = "emp-x",
            employeeName = "Desconocido",
            embeddingBlob = ByteArray(7) { 0 }, // no múltiplo de 4: descifrado/parseo falla
            modelVersion = "fake_v1",
            qualityScore = null
        )
        val repo = FakeRepository(
            listOf(corrupto, profile(engine, "emp-ana", "Ana López", photoAna))
        )
        val useCase = RecognizeFaceUseCase(engine, FakeCrypto(), repo)

        val result = useCase(photoAna)
        assertIs<MatchResult.Recognized>(result)
        assertEquals("emp-ana", result.employeeId)
    }

    @Test
    fun `fallo del engine devuelve Failure`() = runTest {
        val useCase = RecognizeFaceUseCase(
            FakeEngine(failing = true), FakeCrypto(), FakeRepository()
        )
        assertIs<MatchResult.Failure>(useCase(ByteArray(256) { 11 }))
    }

    @Test
    fun `imagen vacia devuelve Failure`() = runTest {
        val useCase = RecognizeFaceUseCase(FakeEngine(), FakeCrypto(), FakeRepository())
        assertIs<MatchResult.Failure>(useCase(ByteArray(0)))
    }

    @Test
    fun `fallo del repositorio devuelve Failure`() = runTest {
        val useCase = RecognizeFaceUseCase(
            FakeEngine(), FakeCrypto(), FakeRepository(failing = true)
        )
        assertIs<MatchResult.Failure>(useCase(ByteArray(256) { 11 }))
    }

    @Test
    fun `umbral configurable acepta coincidencias mas lejanas`() = runTest {
        val engine = FakeEngine()
        val repo = FakeRepository(
            listOf(profile(engine, "emp-ana", "Ana López", ByteArray(256) { 11 }))
        )
        val unknownPhoto = ByteArray(256) { -37 }

        // Con umbral estricto: NoMatch; con umbral laxo (1.1 cubre cualquier
        // distancia de vectores unitarios distintos): Recognized.
        val strict = RecognizeFaceUseCase(engine, FakeCrypto(), repo, umbralSimilitud = 0.1f)
        val lax = RecognizeFaceUseCase(engine, FakeCrypto(), repo, umbralSimilitud = 1.1f)

        assertIs<MatchResult.NoMatch>(strict(unknownPhoto))
        val relaxed = lax(unknownPhoto)
        assertIs<MatchResult.Recognized>(relaxed)
        assertEquals("emp-ana", relaxed.employeeId)
    }
}
