package com.coati.checador.shared.domain.facecamera

import com.coati.checador.shared.security.toByteArray
import com.coati.checador.shared.security.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGImageCreateWithImageInRect
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRectMake
import kotlinx.cinterop.useContents
import platform.Foundation.NSData
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.Vision.VNDetectFaceRectanglesRequest
import platform.Vision.VNFaceObservation
import platform.Vision.VNImageRequestHandler

/**
 * Detector iOS sobre Vision (VNDetectFaceRectanglesRequest).
 *
 * Las observaciones de Vision traen el boundingBox normalizado (0..1) con
 * origen en la esquina inferior izquierda; se convierten a píxeles con
 * origen superior izquierdo, igual que ML Kit en Android.
 *
 * Los recortes solo viven en memoria (JPEG); nunca se escriben a disco
 * sin cifrar.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun createFaceDetector(): FaceDetector = VisionFaceDetector()

@OptIn(ExperimentalForeignApi::class)
internal class VisionFaceDetector : FaceDetector {

    override suspend fun detectFaces(imageBytes: ByteArray): List<DetectedFace> {
        val image = decode(imageBytes) ?: return emptyList()
        val cgImage: CGImageRef = image.CGImage ?: return emptyList()
        val width = CGImageGetWidth(cgImage).toInt()
        val height = CGImageGetHeight(cgImage).toInt()
        if (width <= 0 || height <= 0) return emptyList()

        val nsData: NSData = imageBytes.toNSData()
        val handler = VNImageRequestHandler(data = nsData, options = mapOf<Any?, Any?>())
        val request = VNDetectFaceRectanglesRequest()

        val ok = try {
            handler.performRequests(listOf<Any?>(request), null)
        } catch (_: Exception) {
            false
        }
        if (!ok) return emptyList()

        val observations = request.results?.filterIsInstance<VNFaceObservation>() ?: emptyList()
        return observations.map { obs ->
            val box = obs.boundingBox
            // Conversión de coords normalizadas (origen abajo-izquierda) a
            // píxeles (origen arriba-izquierda). CGRect es struct C: se
            // accede con useContents.
            val (originX, originY, sizeW, sizeH) = box.useContents {
                listOf(origin.x, origin.y, size.width, size.height)
            }
            val left = (originX * width).toFloat()
            val boxTopFromBottom = ((originY + sizeH) * height).toFloat()
            DetectedFace(
                left = left,
                top = height - boxTopFromBottom,
                right = left + (sizeW * width).toFloat(),
                bottom = height - (originY * height).toFloat(),
                confidence = obs.confidence.toFloat()
            )
        }
    }

    override fun cropFace(
        imageBytes: ByteArray,
        face: DetectedFace,
        paddingFraction: Float
    ): ByteArray {
        val image = decode(imageBytes)
            ?: throw IllegalArgumentException("No se pudo decodificar la imagen")
        val cgImage: CGImageRef = image.CGImage
            ?: throw IllegalArgumentException("La imagen no tiene representación CGImage")
        val imgW = CGImageGetWidth(cgImage).toInt()
        val imgH = CGImageGetHeight(cgImage).toInt()
        val rect = FaceCrop.paddedRect(face, imgW, imgH, paddingFraction)
            ?: throw IllegalArgumentException("El rostro no intersecta la imagen")

        val cgCrop = CGImageCreateWithImageInRect(
            cgImage,
            CGRectMake(rect.left.toDouble(), rect.top.toDouble(), rect.width.toDouble(), rect.height.toDouble())
        ) ?: throw IllegalStateException("No se pudo extraer el rostro")
        val uiCrop = UIImage.imageWithCGImage(cgCrop, 1.0, platform.UIKit.UIImageOrientation.UIImageOrientationUp)
        CGImageRelease(cgCrop)
        return UIImageJPEGRepresentation(uiCrop, 0.92)?.toByteArray()
            ?: throw IllegalStateException("No se pudo codificar el recorte a JPEG")
    }

    private fun decode(imageBytes: ByteArray): UIImage? =
        UIImage(data = imageBytes.toNSData())
}
