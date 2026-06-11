package com.coati.checador.feature.facerecognition.domain

import android.graphics.Bitmap

/**
 * Contrato público del motor de reconocimiento facial.
 * Expuesto a feature:employee-enrollment y feature:attendance como abstracción.
 *
 * La implementación concreta ([EmbeddingService]) usa TFLite MobileFaceNet
 * con fallback simulado cuando el modelo no está disponible.
 */
interface FaceRecognitionEngine {

    /** Versión del modelo activo (ej. "mobilefacenet_v1" o "simulado_v1") */
    val versionModelo: String

    /** Dimensión del vector de embeddings (128 para MobileFaceNet) */
    val embeddingSize: Int

    /**
     * Genera el vector de embeddings L2-normalizado para el bitmap de un rostro.
     * Si el modelo TFLite no está en assets, retorna un embedding simulado
     * reproducible (basado en el hash del bitmap) para modo desarrollo.
     */
    fun generarEmbedding(rostro: Bitmap): FloatArray

    /**
     * Cifra el vector float[] con AES-256-GCM usando la clave del Android Keystore.
     * El resultado incluye el IV (12 bytes) + ciphertext concatenados.
     */
    fun cifrarEmbedding(embedding: FloatArray): ByteArray

    /**
     * Descifra un blob generado por [cifrarEmbedding].
     * @throws javax.crypto.AEADBadTagException si el blob está corrupto.
     */
    fun descifrarEmbedding(blob: ByteArray): FloatArray

    /**
     * Calcula la distancia coseno entre dos embeddings L2-normalizados.
     * Rango: 0.0 (idénticos) — 2.0 (opuestos). Menor = más similares.
     */
    fun distanciaCoseno(a: FloatArray, b: FloatArray): Float

    /**
     * Retorna true si la distancia coseno es menor o igual al umbral interno (0.4).
     * Usar este método centraliza el umbral en la implementación.
     */
    fun esMismaPersona(a: FloatArray, b: FloatArray): Boolean
}
