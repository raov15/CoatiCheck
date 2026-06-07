package com.coati.checador.feature.employeeenrollment.domain.model

/**
 * Resultado de la validación del rostro capturado con CameraX + ML Kit.
 */
sealed class ResultadoValidacionRostro {

    /** El rostro es válido y listo para generar embeddings */
    data class Valido(
        /** Puntuación de calidad de la imagen (0.0 – 1.0) */
        val puntuacionCalidad: Float
    ) : ResultadoValidacionRostro()

    /** No se detectó ningún rostro en la imagen */
    object SinRostro : ResultadoValidacionRostro()

    /** Se detectaron múltiples rostros; solo se permite uno */
    data class MultiplesRostros(val cantidad: Int) : ResultadoValidacionRostro()

    /** La iluminación es insuficiente para un reconocimiento confiable */
    object IluminacionInsuficiente : ResultadoValidacionRostro()

    /** El rostro está demasiado inclinado o fuera del encuadre */
    object PosicionInvalida : ResultadoValidacionRostro()

    /** Error genérico durante el procesamiento */
    data class Error(val mensaje: String) : ResultadoValidacionRostro()
}
