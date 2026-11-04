package com.coati.checador.shared

import com.coati.checador.shared.domain.facecamera.DetectedFace
import com.coati.checador.shared.domain.facecamera.FaceCapture
import com.coati.checador.shared.domain.facecamera.FaceCrop
import com.coati.checador.shared.domain.facecamera.FaceDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FaceCropTest {

    @Test
    fun `largest elige el rostro de mayor area`() {
        val chico = DetectedFace(0f, 0f, 10f, 10f, null)        // 100
        val grande = DetectedFace(0f, 0f, 50f, 40f, null)       // 2000
        val medio = DetectedFace(0f, 0f, 30f, 30f, null)        // 900
        assertEquals(grande, FaceCrop.largest(listOf(chico, medio, grande)))
    }

    @Test
    fun `largest con lista vacia devuelve null`() {
        assertNull(FaceCrop.largest(emptyList()))
    }

    @Test
    fun `paddedRect aplica margen y respeta limites de imagen`() {
        // Rostro 100x100 centrado en imagen 200x200: pad 25 por lado.
        val face = DetectedFace(50f, 50f, 150f, 150f, null)
        val rect = FaceCrop.paddedRect(face, 200, 200, 0.25f)!!
        assertEquals(25, rect.left)
        assertEquals(25, rect.top)
        assertEquals(150, rect.width)
        assertEquals(150, rect.height)
    }

    @Test
    fun `paddedRect recorta cuando el margen excede la imagen`() {
        val face = DetectedFace(0f, 0f, 50f, 50f, null)
        val rect = FaceCrop.paddedRect(face, 40, 40, 0.5f)!!
        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertEquals(40, rect.width)
        assertEquals(40, rect.height)
    }

    @Test
    fun `paddedRect rechaza rostro fuera de imagen`() {
        val face = DetectedFace(300f, 300f, 350f, 350f, null)
        assertNull(FaceCrop.paddedRect(face, 200, 200, 0.25f))
    }

    @Test
    fun `paddedRect rechaza imagen o rostro con dimension cero`() {
        val face = DetectedFace(0f, 0f, 50f, 50f, null)
        assertNull(FaceCrop.paddedRect(face, 0, 200, 0.25f))
        assertNull(FaceCrop.paddedRect(face, 200, 0, 0.25f))
        val vacio = DetectedFace(10f, 10f, 10f, 10f, null)
        assertNull(FaceCrop.paddedRect(vacio, 200, 200, 0.25f))
    }

    @Test
    fun `paddedRect con margen por defecto de un cuarto`() {
        val face = DetectedFace(40f, 40f, 60f, 60f, null) // 20x20
        val rect = FaceCrop.paddedRect(face, 200, 200)!!
        assertEquals(35, rect.left)   // 40 - 5
        assertEquals(35, rect.top)
        assertEquals(30, rect.width)  // 20 + 10
        assertEquals(30, rect.height)
    }
}

class FaceCaptureTest {

    /** Detector falso con rostros configurables. */
    private class FakeDetector(
        private val faces: List<DetectedFace> = emptyList(),
        private val failDetection: Boolean = false,
        private val failCrop: Boolean = false
    ) : FaceDetector {
        var lastCropFace: DetectedFace? = null
            private set

        override suspend fun detectFaces(imageBytes: ByteArray): List<DetectedFace> {
            if (failDetection) throw IllegalStateException("detector roto")
            return faces
        }

        override fun cropFace(
            imageBytes: ByteArray,
            face: DetectedFace,
            paddingFraction: Float
        ): ByteArray {
            if (failCrop) throw IllegalStateException("recorte roto")
            lastCropFace = face
            return face.toString().encodeToByteArray()
        }
    }

    @Test
    fun `captura el rostro mas grande y lo recorta`() = runTest {
        val detector = FakeDetector(
            listOf(
                DetectedFace(0f, 0f, 20f, 20f, 0.9f),
                DetectedFace(0f, 0f, 80f, 80f, 0.9f)
            )
        )
        val result = FaceCapture(detector).captureLargestFace(ByteArray(4) { 1 })
        assertIs<FaceCapture.Result.Captured>(result)
        // Se recortó el rostro grande (80x80), no el chico.
        assertEquals(80f, detector.lastCropFace?.right)
        assertTrue(result.faceImage.isNotEmpty())
    }

    @Test
    fun `sin rostros devuelve NoFace`() = runTest {
        val result = FaceCapture(FakeDetector()).captureLargestFace(ByteArray(4) { 1 })
        assertIs<FaceCapture.Result.NoFace>(result)
    }

    @Test
    fun `fallo del detector devuelve Failure`() = runTest {
        val result = FaceCapture(
            FakeDetector(failDetection = true)
        ).captureLargestFace(ByteArray(4) { 1 })
        assertIs<FaceCapture.Result.Failure>(result)
    }

    @Test
    fun `fallo del recorte devuelve Failure`() = runTest {
        val result = FaceCapture(
            FakeDetector(
                faces = listOf(DetectedFace(0f, 0f, 50f, 50f, null)),
                failCrop = true
            )
        ).captureLargestFace(ByteArray(4) { 1 })
        assertIs<FaceCapture.Result.Failure>(result)
    }
}
