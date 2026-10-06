package com.coati.checador.shared.domain.facecamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.tasks.await

/** Crea el detector Android sobre ML Kit Face Detection. */
actual fun createFaceDetector(): FaceDetector = MlKitFaceDetector()

internal class MlKitFaceDetector : FaceDetector {

    private val client by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()
        )
    }

    override suspend fun detectFaces(imageBytes: ByteArray): List<DetectedFace> {
        val bitmap = decode(imageBytes) ?: return emptyList()
        val faces = try {
            client.process(InputImage.fromBitmap(bitmap, 0)).await()
        } catch (_: Exception) {
            return emptyList()
        }
        return faces.map { face ->
            DetectedFace(
                left = face.boundingBox.left.toFloat(),
                top = face.boundingBox.top.toFloat(),
                right = face.boundingBox.right.toFloat(),
                bottom = face.boundingBox.bottom.toFloat(),
                confidence = null // ML Kit no expone confianza por rostro
            )
        }
    }

    override fun cropFace(
        imageBytes: ByteArray,
        face: DetectedFace,
        paddingFraction: Float
    ): ByteArray {
        val bitmap = decode(imageBytes)
            ?: throw IllegalArgumentException("No se pudo decodificar la imagen")
        val rect = FaceCrop.paddedRect(face, bitmap.width, bitmap.height, paddingFraction)
            ?: throw IllegalArgumentException("El rostro no intersecta la imagen")

        val cropped = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width, rect.height)
        return ByteArrayOutputStream().apply {
            cropped.compress(Bitmap.CompressFormat.JPEG, 92, this)
        }.toByteArray()
    }

    private fun decode(imageBytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}
