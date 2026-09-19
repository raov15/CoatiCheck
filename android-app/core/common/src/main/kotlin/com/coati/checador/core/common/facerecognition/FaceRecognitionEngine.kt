package com.coati.checador.core.common.facerecognition

import android.graphics.Bitmap

interface FaceRecognitionEngine {

    val versionModelo: String

    val embeddingSize: Int

    fun generarEmbedding(
        rostro: Bitmap
    ): FloatArray

    fun cifrarEmbedding(
        embedding: FloatArray
    ): ByteArray

    fun descifrarEmbedding(
        blob: ByteArray
    ): FloatArray

    fun distanciaCoseno(
        a: FloatArray,
        b: FloatArray
    ): Float

    fun esMismaPersona(
        a: FloatArray,
        b: FloatArray
    ): Boolean
}