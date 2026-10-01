package com.coati.checador.shared

import com.coati.checador.shared.domain.facerecognition.FaceMatcher
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FaceMatcherTest {

    private val size = 192

    private fun unitVector(seed: Float): FloatArray =
        FaceMatcher.normalizarL2(FloatArray(size) { i -> seed + (i % 13) * 0.01f })

    @Test
    fun `distancia coseno de vectores identicos es cero`() {
        val v = unitVector(0.5f)
        assertTrue(abs(FaceMatcher.distanciaCoseno(v, v)) < 1e-5f)
    }

    @Test
    fun `distancia coseno de vectores opuestos es dos`() {
        val v = unitVector(0.5f)
        val opposite = FloatArray(size) { -v[it] }
        assertTrue(abs(FaceMatcher.distanciaCoseno(v, opposite) - 2f) < 1e-3f)
    }

    @Test
    fun `distancia coseno de vectores ortogonales es uno`() {
        val a = FloatArray(size).also { it[0] = 1f }
        val b = FloatArray(size).also { it[1] = 1f }
        assertTrue(abs(FaceMatcher.distanciaCoseno(a, b) - 1f) < 1e-5f)
    }

    @Test
    fun `dimensiones distintas lanzan excepcion`() {
        assertFailsWith<IllegalArgumentException> {
            FaceMatcher.distanciaCoseno(FloatArray(size), FloatArray(128))
        }
    }

    @Test
    fun `esMismaPersona respeta el umbral`() {
        val v = unitVector(0.5f)
        assertTrue(FaceMatcher.esMismaPersona(v, v, umbral = 0.4f))

        val opposite = FloatArray(size) { -v[it] }
        assertFalse(FaceMatcher.esMismaPersona(v, opposite, umbral = 0.4f))
    }

    @Test
    fun `normalizarL2 produce norma uno`() {
        val v = FloatArray(size) { (it % 7) * 0.3f - 0.9f }
        val n = FaceMatcher.normalizarL2(v)
        var suma = 0.0
        for (x in n) suma += (x * x).toDouble()
        assertTrue(abs(kotlin.math.sqrt(suma) - 1.0) < 1e-5)
    }

    @Test
    fun `normalizarL2 con vector cero devuelve copia`() {
        val zero = FloatArray(size)
        assertTrue(FaceMatcher.normalizarL2(zero).contentEquals(zero))
    }

    @Test
    fun `floats a bytes y vuelta es identico`() {
        val original = unitVector(0.77f)
        val restaurado = FaceMatcher.bytesToFloats(FaceMatcher.floatsToBytes(original))
        assertEquals(original.size, restaurado.size)
        for (i in original.indices) {
            assertEquals(original[i], restaurado[i], "índice $i")
        }
    }

    @Test
    fun `bytesToFloats rechaza longitud no multiplo de cuatro`() {
        assertFailsWith<IllegalArgumentException> {
            FaceMatcher.bytesToFloats(ByteArray(13))
        }
    }

    @Test
    fun `serializacion respeta floats negativos y especiales`() {
        val original = floatArrayOf(-1.5f, 0f, Float.MAX_VALUE, -Float.MIN_VALUE, 3.25f)
        val restaurado = FaceMatcher.bytesToFloats(FaceMatcher.floatsToBytes(original))
        for (i in original.indices) {
            assertEquals(original[i], restaurado[i], "índice $i")
        }
    }
}
