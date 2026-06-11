package com.coati.checador.feature.facerecognition.data.service

import android.content.Context
import android.graphics.Bitmap
import com.coati.checador.core.security.KeystoreHelper
import com.coati.checador.feature.facerecognition.domain.FaceRecognitionEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import org.tensorflow.lite.Interpreter

/**
 * Implementación del [FaceRecognitionEngine] usando TFLite MobileFaceNet.
 *
 * Responsabilidades:
 * 1. Cargar el modelo TFLite (mobilefacenet.tflite) desde assets (lazy).
 * 2. Pre-procesar el bitmap del rostro a la entrada del modelo (112×112 px, [-1,1]).
 * 3. Generar el vector de embeddings de 128 dimensiones y normalizarlo con L2.
 * 4. Cifrar/descifrar embeddings con AES-256-GCM usando la clave del Android Keystore.
 * 5. Calcular distancia coseno para comparación 1:N en la pantalla de asistencia.
 *
 * Si el modelo no está disponible, retorna embeddings simulados reproducibles
 * para permitir desarrollo y pruebas sin el archivo .tflite.
 */
@Singleton
class EmbeddingService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystoreHelper: KeystoreHelper
) : FaceRecognitionEngine {

    companion object {
        private const val MODELO_TFLITE = "mobilefacenet.tflite"
        private const val INPUT_SIZE    = 112
        private const val GCM_TAG_LENGTH = 128
        private const val UMBRAL_SIMILITUD = 0.4f
    }

    override val versionModelo: String get() = if (interpreter != null) "mobilefacenet_v1" else "simulado_v1"
    override val embeddingSize: Int = 128

    // Intérprete de TFLite inicializado de forma lazy con manejo de errores
    private val interpreter: Interpreter? by lazy {
        try {
            val afd = context.assets.openFd(MODELO_TFLITE)
            val channel = afd.createInputStream().channel
            val buffer = channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
            Timber.d("EmbeddingService: modelo TFLite cargado correctamente")
            Interpreter(buffer)
        } catch (e: Exception) {
            Timber.w(e, "EmbeddingService: $MODELO_TFLITE no encontrado — modo simulado activo")
            null
        }
    }

    // ─── Interface FaceRecognitionEngine ─────────────────────────────────────

    override fun generarEmbedding(rostro: Bitmap): FloatArray {
        val interp = interpreter
        return if (interp != null) {
            generarEmbeddingConTFLite(interp, rostro)
        } else {
            Timber.v("EmbeddingService: usando embedding simulado")
            generarEmbeddingSimulado(rostro)
        }
    }

    override fun cifrarEmbedding(embedding: FloatArray): ByteArray {
        val key = keystoreHelper.getOrCreateEmbeddingKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cifrado = cipher.doFinal(floatArrayABytes(embedding))
        return iv + cifrado          // IV (12 bytes) || ciphertext
    }

    override fun descifrarEmbedding(blob: ByteArray): FloatArray {
        val key = keystoreHelper.getOrCreateEmbeddingKey()
        val iv      = blob.copyOfRange(0, 12)
        val cifrado = blob.copyOfRange(12, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return bytesAFloatArray(cipher.doFinal(cifrado))
    }

    override fun distanciaCoseno(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embeddings deben tener la misma dimensión" }
        val dotProduct = a.zip(b).sumOf { (ai, bi) -> (ai * bi).toDouble() }.toFloat()
        return 1f - dotProduct          // ambos embeddings ya están normalizados en L2
    }

    override fun esMismaPersona(a: FloatArray, b: FloatArray): Boolean =
        distanciaCoseno(a, b) <= UMBRAL_SIMILITUD

    // ─── Implementación interna ───────────────────────────────────────────────

    private fun generarEmbeddingConTFLite(interp: Interpreter, rostro: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(rostro, INPUT_SIZE, INPUT_SIZE, true)
        val input  = preprocesarImagen(scaled)
        val output = Array(1) { FloatArray(embeddingSize) }
        interp.run(input, output)
        return normalizarL2(output[0])
    }

    private fun preprocesarImagen(bitmap: Bitmap): ByteBuffer {
        // Layout: [1, 112, 112, 3] float32, normalizado a [-1, 1]
        val buf = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        buf.order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (px in pixels) {
            buf.putFloat(((px shr 16 and 0xFF).toFloat() - 127.5f) / 128f)
            buf.putFloat(((px shr  8 and 0xFF).toFloat() - 127.5f) / 128f)
            buf.putFloat(((px        and 0xFF).toFloat() - 127.5f) / 128f)
        }
        buf.rewind()
        return buf
    }

    private fun generarEmbeddingSimulado(rostro: Bitmap): FloatArray {
        val stream = ByteArrayOutputStream()
        rostro.compress(Bitmap.CompressFormat.JPEG, 50, stream)
        val seed = stream.toByteArray().contentHashCode().toLong()
        val rng  = java.util.Random(seed)
        return normalizarL2(FloatArray(embeddingSize) { rng.nextFloat() * 2f - 1f })
    }

    private fun normalizarL2(v: FloatArray): FloatArray {
        val norma = Math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return if (norma > 0f) FloatArray(v.size) { v[it] / norma } else v
    }

    private fun floatArrayABytes(floats: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { buf.putFloat(it) }
        return buf.array()
    }

    private fun bytesAFloatArray(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buf.float }
    }
}
