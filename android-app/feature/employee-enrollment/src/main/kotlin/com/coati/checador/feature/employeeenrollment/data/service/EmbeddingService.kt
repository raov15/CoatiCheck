package com.coati.checador.feature.employeeenrollment.data.service

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.coati.checador.core.security.KeystoreHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import org.tensorflow.lite.Interpreter
import java.nio.channels.FileChannel

/**
 * Servicio encargado de:
 * 1. Cargar el modelo TFLite (MobileFaceNet) desde assets.
 * 2. Pre-procesar el bitmap del rostro a la entrada del modelo.
 * 3. Generar el vector de embeddings de 128 dimensiones.
 * 4. Cifrar el embedding con AES-256-GCM usando la clave del Keystore.
 * 5. Descifrar embeddings almacenados para comparación 1:N.
 */
@Singleton
class EmbeddingService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystoreHelper: KeystoreHelper
) {
    companion object {
        /** Nombre del modelo en src/main/assets */
        private const val MODELO_TFLITE = "mobilefacenet.tflite"

        /** Dimensiones de entrada del modelo MobileFaceNet: 112×112 px */
        private const val INPUT_SIZE = 112

        /** Tamaño del vector de embeddings */
        const val EMBEDDING_SIZE = 128

        /** Umbral de distancia coseno para considerar dos rostros iguales */
        private const val UMBRAL_SIMILITUD = 0.4f

        /** Versión del modelo para almacenar en la entidad */
        const val VERSION_MODELO = "mobilefacenet_v1"

        private const val GCM_TAG_LENGTH = 128
    }

    // Intérprete de TFLite, se inicializa de forma lazy
    private val interpreter: Interpreter? by lazy {
        try {
            val assetFileDescriptor = context.assets.openFd(MODELO_TFLITE)
            val fileChannel = assetFileDescriptor.createInputStream().channel
            val mappedBuffer = fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                assetFileDescriptor.startOffset,
                assetFileDescriptor.declaredLength
            )
            Timber.d("EmbeddingService: modelo TFLite cargado correctamente")
            Interpreter(mappedBuffer)
        } catch (e: Exception) {
            Timber.e(e, "EmbeddingService: no se pudo cargar $MODELO_TFLITE — usando modo simulado")
            null
        }
    }

    /**
     * Genera el vector de embeddings normalizado a partir de un Bitmap.
     *
     * @param rostro Bitmap del rostro recortado y alineado
     * @return FloatArray de [EMBEDDING_SIZE] elementos con L2-norm aplicada
     */
    fun generarEmbedding(rostro: Bitmap): FloatArray {
        val intérprete = interpreter
        return if (intérprete != null) {
            generarEmbeddingConTFLite(intérprete, rostro)
        } else {
            Timber.w("EmbeddingService: TFLite no disponible, usando embedding simulado para desarrollo")
            generarEmbeddingSimulado(rostro)
        }
    }

    private fun generarEmbeddingConTFLite(intérprete: Interpreter, rostro: Bitmap): FloatArray {
        // 1. Redimensionar a 112×112
        val bitmapRedim = Bitmap.createScaledBitmap(rostro, INPUT_SIZE, INPUT_SIZE, true)

        // 2. Convertir a ByteBuffer normalizado [-1, 1]
        val inputBuffer = preprocesarImagen(bitmapRedim)

        // 3. Buffer de salida: 1 × EMBEDDING_SIZE
        val outputBuffer = Array(1) { FloatArray(EMBEDDING_SIZE) }

        intérprete.run(inputBuffer, outputBuffer)

        val embedding = outputBuffer[0]

        // 4. Normalización L2
        return normalizarL2(embedding)
    }

    /**
     * Pre-procesa el bitmap a ByteBuffer float32 normalizado en [-1, 1].
     * Orden: NHWC (batch=1, height=112, width=112, channels=3)
     */
    private fun preprocesarImagen(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())

        val pixeles = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixeles, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixeles) {
            val r = ((pixel shr 16) and 0xFF).toFloat()
            val g = ((pixel shr 8) and 0xFF).toFloat()
            val b = (pixel and 0xFF).toFloat()

            // Normalizar de [0,255] a [-1, 1]
            buffer.putFloat((r - 127.5f) / 128.0f)
            buffer.putFloat((g - 127.5f) / 128.0f)
            buffer.putFloat((b - 127.5f) / 128.0f)
        }

        buffer.rewind()
        return buffer
    }

    /**
     * Embedding simulado basado en el hash de la imagen (solo para pruebas sin modelo).
     * Produce un vector determinista de 128 dimensiones para el bitmap dado.
     */
    private fun generarEmbeddingSimulado(rostro: Bitmap): FloatArray {
        val stream = ByteArrayOutputStream()
        rostro.compress(Bitmap.CompressFormat.JPEG, 50, stream)
        val bytes = stream.toByteArray()
        val hash = bytes.contentHashCode()
        val random = java.util.Random(hash.toLong())
        val embedding = FloatArray(EMBEDDING_SIZE) { random.nextFloat() * 2f - 1f }
        return normalizarL2(embedding)
    }

    /** Normalización L2 del vector de embeddings */
    private fun normalizarL2(embedding: FloatArray): FloatArray {
        val norma = Math.sqrt(embedding.map { it * it }.sum().toDouble()).toFloat()
        return if (norma > 0f) FloatArray(embedding.size) { embedding[it] / norma } else embedding
    }

    // ─── Cifrado / descifrado AES-256-GCM ──────────────────────────────────────

    /**
     * Serializa y cifra el embedding con AES-256-GCM usando la clave del Keystore.
     *
     * @param embedding FloatArray con los valores de embeddings
     * @return ByteArray: IV (12 bytes) concatenado con el texto cifrado
     */
    fun cifrarEmbedding(embedding: FloatArray): ByteArray {
        val key = keystoreHelper.getOrCreateEmbeddingKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv

        // Serializar float[] → bytes (little-endian)
        val bytesEmbedding = floatArrayABytes(embedding)
        val cifrado = cipher.doFinal(bytesEmbedding)

        // Formato: [IV 12 bytes][ciphertext]
        return iv + cifrado
    }

    /**
     * Descifra y deserializa un embedding previamente cifrado.
     *
     * @param blob ByteArray: IV (12 bytes) + texto cifrado
     * @return FloatArray con el embedding original
     */
    fun descifrarEmbedding(blob: ByteArray): FloatArray {
        val key = keystoreHelper.getOrCreateEmbeddingKey()
        val iv = blob.copyOfRange(0, 12)
        val cifrado = blob.copyOfRange(12, blob.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val bytesEmbedding = cipher.doFinal(cifrado)

        return bytesAFloatArray(bytesEmbedding)
    }

    // ─── Comparación de embeddings ──────────────────────────────────────────────

    /**
     * Calcula la distancia coseno entre dos embeddings normalizados.
     * Rango: 0 (idénticos) – 2 (opuestos).
     */
    fun distanciaCoseno(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Los embeddings deben tener la misma dimensión" }
        val productoEscalar = a.zip(b).sumOf { (ai, bi) -> (ai * bi).toDouble() }.toFloat()
        // Como los vectores están L2-normalizados, la distancia coseno = 1 - similitud
        return 1f - productoEscalar
    }

    /**
     * Indica si dos embeddings corresponden a la misma persona
     * según el umbral de similitud configurado en Constants.
     */
    fun esMismaPersona(a: FloatArray, b: FloatArray): Boolean {
        return distanciaCoseno(a, b) <= UMBRAL_SIMILITUD
    }

    // ─── Utilidades de serialización ───────────────────────────────────────────

    private fun floatArrayABytes(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun bytesAFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val result = FloatArray(bytes.size / 4)
        for (i in result.indices) result[i] = buffer.float
        return result
    }
}
