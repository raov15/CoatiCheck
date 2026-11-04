package com.coati.checador.shared.domain.facecamera

/**
 * Detector y recortador de rostros multiplataforma.
 *
 * La imagen se recibe como [ByteArray] (JPEG/PNG) del fotograma de cámara;
 * la sesión de cámara en sí (CameraX en Android, AVFoundation en iOS) vive
 * en la capa de UI y alimenta fotogramas a este detector.
 *
 * - Android: ML Kit Face Detection.
 * - iOS: Vision VNDetectFaceRectanglesRequest.
 *
 * Las imágenes recortadas solo se mantienen en memoria: nunca se escriben
 * a disco sin cifrar.
 */
interface FaceDetector {

    /**
     * Rostros detectados en la imagen, en coordenadas de píxel.
     * Lista vacía si no hay rostros o la imagen no se pudo decodificar.
     */
    suspend fun detectFaces(imageBytes: ByteArray): List<DetectedFace>

    /**
     * Recorta el rostro de la imagen con un margen alrededor y lo devuelve
     * como JPEG en memoria.
     *
     * @throws IllegalArgumentException si la imagen no se puede decodificar
     *         o el rostro no intersecta la imagen.
     */
    fun cropFace(
        imageBytes: ByteArray,
        face: DetectedFace,
        paddingFraction: Float = 0.25f
    ): ByteArray
}

/** Crea el detector de rostros de la plataforma actual. */
expect fun createFaceDetector(): FaceDetector

/**
 * Fachada de captura: detecta el rostro más grande y lo recorta,
 * combinando [FaceDetector.detectFaces] y [FaceDetector.cropFace] con la
 * matemática común de [FaceCrop].
 */
class FaceCapture(private val detector: FaceDetector) {

    sealed class Result {
        /** Rostro recortado listo para generar el embedding. */
        data class Captured(val faceImage: ByteArray, val face: DetectedFace) : Result()

        /** Ningún rostro visible en el fotograma. */
        data object NoFace : Result()

        data class Failure(val message: String) : Result()
    }

    suspend fun captureLargestFace(
        imageBytes: ByteArray,
        paddingFraction: Float = 0.25f
    ): Result {
        val faces = try {
            detector.detectFaces(imageBytes)
        } catch (e: Exception) {
            return Result.Failure(e.message ?: "Error al detectar rostros")
        }
        val largest = FaceCrop.largest(faces) ?: return Result.NoFace
        val cropped = try {
            detector.cropFace(imageBytes, largest, paddingFraction)
        } catch (e: Exception) {
            return Result.Failure(e.message ?: "Error al recortar el rostro")
        }
        return Result.Captured(cropped, largest)
    }
}
