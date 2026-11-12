package com.coati.checador.shared

import com.coati.checador.shared.domain.facecamera.DetectedFace
import com.coati.checador.shared.domain.facecamera.VisionFaceDetector
import com.coati.checador.shared.security.toNSData
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIImage

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
class FaceCropIosTest {
    @Test
    fun cropUsesTopLeftImageCoordinates() {
        val image = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAABQAAAAoCAIAAABxU02MAAAAPElEQVR4nO3PIRIAIAwDwTT//3OxIA9mMM3prEi17vODVTDMFOwFw0zBXvBPXFJP++xgluH+KBhmCjQcL7ydAk1cPQO/AAAAAElFTkSuQmCC")
        val face = DetectedFace(left = 0f, top = 0f, right = 20f, bottom = 20f, confidence = 1f)
        val cropped = VisionFaceDetector().cropFace(image, face, 0f)
        val cgImage = requireNotNull(UIImage(data = cropped.toNSData()).CGImage)
        val pixels = ByteArray(4)
        pixels.usePinned { pinned ->
            val context = requireNotNull(CGBitmapContextCreate(
                pinned.addressOf(0), 1u, 1u, 8u, 4u, CGColorSpaceCreateDeviceRGB(), 1u
            ))
            CGContextDrawImage(context, CGRectMake(0.0, 0.0, 1.0, 1.0), cgImage)
        }
        assertTrue((pixels[0].toInt() and 255) > 200)
        assertTrue((pixels[2].toInt() and 255) < 50)
    }
}
