package com.coati.checador.shared.domain.usecase.facerecognition

import com.coati.checador.shared.domain.facerecognition.FaceMatcher
import com.coati.checador.shared.domain.facerecognition.FaceRecognitionEngine
import com.coati.checador.shared.domain.model.EnrolledFaceProfile
import com.coati.checador.shared.domain.repository.FaceProfileRepository
import com.coati.checador.shared.security.EmbeddingCrypto

/**
 * Reconocimiento facial 1:N real contra los perfiles enrolados.
 *
 * A diferencia de la app Android original (donde `recognizeFace()` ignoraba
 * el rostro capturado y marcaba al primer empleado activo), aquí:
 *
 * 1. Se genera el embedding del rostro capturado con el motor del modelo.
 * 2. Se descifran los embeddings almacenados localmente.
 * 3. Se calcula la distancia coseno contra cada perfil activo.
 * 4. Se acepta la coincidencia con menor distancia solo si está dentro del
 *    umbral configurado; si no, se devuelve [MatchResult.NoMatch].
 */
class RecognizeFaceUseCase(
    private val engine: FaceRecognitionEngine,
    private val crypto: EmbeddingCrypto,
    private val repository: FaceProfileRepository,
    private val umbralSimilitud: Float = FaceMatcher.UMBRAL_SIMILITUD_DEFAULT
) {

    sealed class MatchResult {
        data class Recognized(
            val employeeId: String,
            val employeeName: String,
            /** Distancia coseno del mejor match (menor = más similar). */
            val distance: Float,
            /** 1 - distancia; mayor = más confiable. */
            val confidence: Float
        ) : MatchResult()

        /** Ningún perfil dentro del umbral (o no hay perfiles). */
        data object NoMatch : MatchResult()

        data class Failure(val message: String) : MatchResult()
    }

    suspend operator fun invoke(faceImage: ByteArray): MatchResult {
        // 1. Embedding del rostro capturado.
        val embedding = try {
            require(faceImage.isNotEmpty()) { "Imagen del rostro vacía" }
            engine.generarEmbedding(faceImage)
        } catch (e: Exception) {
            return MatchResult.Failure(
                e.message ?: "No se pudo procesar el rostro capturado."
            )
        }

        // 2. Perfiles activos con embedding cifrado.
        val profiles = try {
            repository.getActiveProfilesWithEmployee()
        } catch (e: Exception) {
            return MatchResult.Failure(
                "No se pudieron leer los perfiles faciales: ${e.message}"
            )
        }
        if (profiles.isEmpty()) return MatchResult.NoMatch

        // 3. Comparación 1:N con descifrado y filtrado por dimensión.
        var bestDistance = Float.MAX_VALUE
        var best: EnrolledFaceProfile? = null
        for (profile in profiles) {
            val storedEmbedding = try {
                FaceMatcher.bytesToFloats(crypto.decrypt(profile.embeddingBlob))
            } catch (_: Exception) {
                continue // perfil corrupto o con formato inválido: se ignora
            }
            if (storedEmbedding.size != embedding.size) continue

            val distance = FaceMatcher.distanciaCoseno(embedding, storedEmbedding)
            if (distance < bestDistance) {
                bestDistance = distance
                best = profile
            }
        }

        // 4. Decisión por umbral.
        val candidate = best ?: return MatchResult.Failure(
            "Ningún perfil facial almacenado es válido."
        )
        return if (bestDistance <= umbralSimilitud) {
            MatchResult.Recognized(
                employeeId = candidate.employeeId,
                employeeName = candidate.employeeName,
                distance = bestDistance,
                confidence = 1f - bestDistance
            )
        } else {
            MatchResult.NoMatch
        }
    }
}
