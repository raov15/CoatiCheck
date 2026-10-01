package com.coati.checador.shared.domain.facerecognition

import com.coati.checador.shared.security.PlatformContext

/**
 * Motor de reconocimiento facial multiplataforma.
 *
 * La imagen del rostro se recibe como [ByteArray] (JPEG/PNG del rostro ya
 * recortado) para mantener el contrato independiente de la plataforma.
 *
 * - Android: TensorFlow Lite con MobileFaceNet (mobilefacenet.tflite).
 * - iOS: CoreML con MobileFaceNet (mobilefacenet.mlmodelc en el bundle).
 * - Fallback común: embedding simulado determinista cuando el modelo real
 *   no está disponible, para permitir desarrollo sin el archivo del modelo.
 */
interface FaceRecognitionEngine {

    /** Identificador del modelo en uso (p. ej. "mobilefacenet_v1"). */
    val versionModelo: String

    /** Dimensión del embedding generado (192 para MobileFaceNet). */
    val embeddingSize: Int

    /** true si hay modelo real cargado; false si se usa el modo simulado. */
    val isRealModelAvailable: Boolean

    /**
     * Genera el embedding del rostro, normalizado con L2.
     *
     * @param faceImage bytes de la imagen del rostro recortado (JPEG/PNG).
     * @return embedding de dimensión [embeddingSize].
     * @throws IllegalArgumentException si la imagen no se puede decodificar.
     */
    fun generarEmbedding(faceImage: ByteArray): FloatArray
}

/** Crea el motor de reconocimiento facial para la plataforma actual. */
expect fun createFaceRecognitionEngine(platformContext: PlatformContext): FaceRecognitionEngine

/**
 * Registro para inyectar un motor externo desde la capa nativa de la app.
 *
 * En iOS, el motor real con CoreML + Vision se implementa en Swift
 * (el modelo mobilefacenet.mlmodelc vive en el bundle de la app) y se
 * registra aquí al arrancar. Si no hay motor registrado, se usa el
 * embedding simulado determinista.
 */
object FaceRecognitionEngineRegistry {

    var externalEngine: FaceRecognitionEngine? = null
}

/** Motor de respaldo determinista: misma imagen -> mismo embedding. */
internal class SimulatedFaceRecognitionEngine : FaceRecognitionEngine {

    internal companion object {
        const val SIZE = 192
        const val VERSION = "simulado_v1"
    }

    override val versionModelo: String = VERSION

    override val embeddingSize: Int = SIZE

    override val isRealModelAvailable: Boolean = false

    override fun generarEmbedding(faceImage: ByteArray): FloatArray =
        generarEmbeddingSimulado(faceImage, SIZE)
}
