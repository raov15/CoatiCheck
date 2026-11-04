package com.coati.checador.shared.domain.facerecognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.coati.checador.shared.security.PlatformContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import org.tensorflow.lite.Interpreter

/** Dimensión del embedding de MobileFaceNet. */
internal const val EMBEDDING_SIZE = 192

/**
 * Motor Android: TensorFlow Lite con MobileFaceNet.
 *
 * El modelo mobilefacenet.tflite se carga de forma lazy desde los assets
 * (la librería AAR fusiona sus assets con los de la app). Si no está
 * disponible se usa el embedding simulado determinista.
 */
actual fun createFaceRecognitionEngine(platformContext: PlatformContext): FaceRecognitionEngine =
    TFLiteFaceRecognitionEngine(platformContext)

internal class TFLiteFaceRecognitionEngine(
    private val platformContext: PlatformContext
) : FaceRecognitionEngine {

    private companion object {
        const val MODEL_FILE = "mobilefacenet.tflite"
        const val INPUT_SIZE = 112
    }

    override val embeddingSize: Int = EMBEDDING_SIZE

    override val versionModelo: String
        get() = if (interpreter != null) "mobilefacenet_v1" else "simulado_v1"

    override val isRealModelAvailable: Boolean
        get() = interpreter != null

    private val interpreter: Interpreter? by lazy {
        val context: Context = platformContext.context ?: return@lazy null
        try {
            val afd = context.assets.openFd(MODEL_FILE)
            val buffer = afd.createInputStream().channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
            Interpreter(buffer)
        } catch (_: Exception) {
            null // modelo no disponible: modo simulado
        }
    }

    override fun generarEmbedding(faceImage: ByteArray): FloatArray {
        val interp = interpreter
            ?: return SimulatedFaceRecognitionEngine().generarEmbedding(faceImage)

        val bitmap = BitmapFactory.decodeByteArray(faceImage, 0, faceImage.size)
            ?: throw IllegalArgumentException("No se pudo decodificar la imagen del rostro")

        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val input = preprocesar(scaled)
        val output = Array(1) { FloatArray(EMBEDDING_SIZE) }
        interp.run(input, output)
        return FaceMatcher.normalizarL2(output[0])
    }

    /**
     * Layout de entrada del modelo: [1, 112, 112, 3] float32 NHWC,
     * píxeles normalizados a [-1, 1] (igual que EmbeddingService Android).
     */
    private fun preprocesar(bitmap: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (px in pixels) {
            buf.putFloat(((px shr 16 and 0xFF) - 127.5f) / 128f)
            buf.putFloat(((px shr 8 and 0xFF) - 127.5f) / 128f)
            buf.putFloat(((px and 0xFF) - 127.5f) / 128f)
        }
        buf.rewind()
        return buf
    }
}
