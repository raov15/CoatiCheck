package com.coati.checador.shared.domain.model

/**
 * Resultado de la validación del rostro capturado por la cámara.
 */
sealed class FaceValidationResult {

    /** El rostro es válido y listo para generar embeddings */
    data class Valid(
        /** Puntuación de calidad de la imagen (0.0 – 1.0) */
        val qualityScore: Float
    ) : FaceValidationResult()

    /** No se detectó ningún rostro en la imagen */
    data object NoFace : FaceValidationResult()

    /** Se detectaron múltiples rostros; solo se permite uno */
    data class MultipleFaces(val count: Int) : FaceValidationResult()

    /** La iluminación es insuficiente para un reconocimiento confiable */
    data object InsufficientLighting : FaceValidationResult()

    /** El rostro está demasiado inclinado o fuera del encuadre */
    data object InvalidPosition : FaceValidationResult()

    /** Error genérico durante el procesamiento */
    data class Failure(val message: String) : FaceValidationResult()
}
