package com.coati.checador.shared.domain.facecamera

/**
 * Rectángulo de un rostro detectado, en coordenadas de píxel de la imagen.
 */
class DetectedFace(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float?
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    override fun toString(): String =
        "DetectedFace([$left,$top - $right,$bottom], conf=$confidence)"
}
