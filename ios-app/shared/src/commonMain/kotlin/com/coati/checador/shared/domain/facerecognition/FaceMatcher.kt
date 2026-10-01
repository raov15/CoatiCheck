package com.coati.checador.shared.domain.facerecognition

import kotlin.math.sqrt

/**
 * Utilidades compartidas de comparación facial.
 *
 * Los embeddings se normalizan con L2 al generarlos, por lo que la
 * distancia coseno equivale a 1 - producto punto.
 */
object FaceMatcher {

    /**
     * Umbral de distancia coseno por debajo del cual dos embeddings se
     * consideran de la misma persona (mismo criterio que la app Android).
     */
    const val UMBRAL_SIMILITUD_DEFAULT = 0.4f

    /** Distancia coseno entre dos embeddings L2-normalizados (0 = idéntico). */
    fun distanciaCoseno(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) {
            "Embeddings deben tener la misma dimensión (${a.size} vs ${b.size})"
        }
        var dot = 0.0
        for (i in a.indices) {
            dot += (a[i] * b[i]).toDouble()
        }
        return (1.0 - dot).toFloat()
    }

    fun esMismaPersona(
        a: FloatArray,
        b: FloatArray,
        umbral: Float = UMBRAL_SIMILITUD_DEFAULT
    ): Boolean = distanciaCoseno(a, b) <= umbral

    /** Normaliza el vector a norma euclídea 1. */
    fun normalizarL2(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) {
            sum += (x * x).toDouble()
        }
        val norma = sqrt(sum).toFloat()
        return if (norma > 0f) FloatArray(v.size) { v[it] / norma } else v.copyOf()
    }

    /** Serializa el embedding a bytes little-endian (contrato con Android). */
    fun floatsToBytes(floats: FloatArray): ByteArray {
        val out = ByteArray(floats.size * 4)
        for (i in floats.indices) {
            val bits = floats[i].toRawBits()
            out[i * 4] = (bits and 0xff).toByte()
            out[i * 4 + 1] = ((bits ushr 8) and 0xff).toByte()
            out[i * 4 + 2] = ((bits ushr 16) and 0xff).toByte()
            out[i * 4 + 3] = ((bits ushr 24) and 0xff).toByte()
        }
        return out
    }

    /** Reconstruye el embedding desde bytes little-endian. */
    fun bytesToFloats(bytes: ByteArray): FloatArray {
        require(bytes.size % 4 == 0) { "Longitud de bytes no múltiplo de 4: ${bytes.size}" }
        val out = FloatArray(bytes.size / 4)
        for (i in out.indices) {
            val b0 = bytes[i * 4].toInt() and 0xff
            val b1 = (bytes[i * 4 + 1].toInt() and 0xff) shl 8
            val b2 = (bytes[i * 4 + 2].toInt() and 0xff) shl 16
            val b3 = (bytes[i * 4 + 3].toInt() and 0xff) shl 24
            out[i] = Float.fromBits(b0 or b1 or b2 or b3)
        }
        return out
    }
}

/**
 * Embedding simulado determinista a partir de los bytes de la imagen.
 * Se usa cuando el modelo real no está disponible (desarrollo/pruebas).
 * La misma imagen produce siempre el mismo embedding, de modo que el
 * flujo enrolamiento -> reconocimiento 1:N sigue siendo funcional.
 */
internal fun generarEmbeddingSimulado(imageBytes: ByteArray, embeddingSize: Int): FloatArray {
    var state = (imageBytes.contentHashCode().toLong() and 0xffffffffL) or 1L
    fun next(): Float {
        state = state xor (state shl 13)
        state = state xor (state ushr 7)
        state = state xor (state shl 17)
        val normalized = ((state and 0xffffffL).toDouble() / 0xffffffL.toDouble()) * 2.0 - 1.0
        return normalized.toFloat()
    }
    return FaceMatcher.normalizarL2(FloatArray(embeddingSize) { next() })
}
