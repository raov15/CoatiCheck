package com.coati.checador.shared.domain.facecamera

import kotlin.math.roundToInt

/** Rectángulo entero dentro de los límites de la imagen. */
class IntRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * Matemática común de recorte facial, testeable en ambos targets.
 */
object FaceCrop {

    /** Rostro de mayor área, mismo criterio que la app Android. */
    fun largest(faces: List<DetectedFace>): DetectedFace? =
        faces.maxByOrNull { it.width * it.height }

    /**
     * Expande el rectángulo del rostro [paddingFraction] por lado y lo
     * recorta a los límites de la imagen.
     *
     * @return rectángulo de recorte válido, o null si el rostro no
     *         intersecta la imagen o tiene área nula.
     */
    fun paddedRect(
        face: DetectedFace,
        imageWidth: Int,
        imageHeight: Int,
        paddingFraction: Float = 0.25f
    ): IntRect? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        if (face.width <= 0f || face.height <= 0f) return null

        val padX = face.width * paddingFraction
        val padY = face.height * paddingFraction

        val left = (face.left - padX).roundToInt().coerceIn(0, imageWidth)
        val right = (face.right + padX).roundToInt().coerceIn(0, imageWidth)
        val top = (face.top - padY).roundToInt().coerceIn(0, imageHeight)
        val bottom = (face.bottom + padY).roundToInt().coerceIn(0, imageHeight)

        val w = right - left
        val h = bottom - top
        return if (w > 0 && h > 0) IntRect(left, top, w, h) else null
    }
}
