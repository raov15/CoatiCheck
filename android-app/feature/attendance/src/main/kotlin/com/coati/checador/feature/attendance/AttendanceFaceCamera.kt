package com.coati.checador.feature.attendance

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.coati.checador.core.ui.theme.CoatiTeal
import timber.log.Timber

/**
 * Componente Compose que muestra la vista previa de la cámara frontal
 * para el reconocimiento facial en el flujo de asistencia.
 *
 * Al presionar el botón, captura el frame y entrega el Bitmap al ViewModel
 * para que ejecute el reconocimiento 1:N contra los perfiles registrados.
 */
@Composable
fun AttendanceFaceCamera(
    onFaceCapturado: (Bitmap) -> Unit,
    modifier: Modifier = Modifier,
    message: String = "Toca el botón para identificarte",
    isProcessing: Boolean = false
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var guideColor by remember { mutableStateOf(Color.White) }
    var isCapturing by remember { mutableStateOf(false) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    // Vincula cámara una sola vez al ciclo de vida
    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageCapture
                )
                Timber.d("AttendanceFaceCamera: cámara frontal vinculada")
            } catch (e: Exception) {
                Timber.e(e, "AttendanceFaceCamera: error al vincular cámara")
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { future.get()?.unbindAll() }
            Timber.d("AttendanceFaceCamera: cámara liberada")
        }
    }

    Box(modifier = modifier) {
        // Vista previa de la cámara
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay semitransparente con óvalo guía
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height * 0.44f
            val rx = size.width * 0.33f
            val ry = size.height * 0.30f

            drawRect(color = Color.Black.copy(alpha = 0.4f), size = size)
            drawOval(
                color = guideColor,
                topLeft = Offset(cx - rx, cy - ry),
                size = Size(rx * 2, ry * 2),
                style = Stroke(width = 4.dp.toPx())
            )
        }

        // Mensaje de estado
        Text(
            text = when {
                isProcessing -> "Reconociendo..."
                isCapturing  -> "Capturando..."
                else         -> message
            },
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )

        // Indicador de procesamiento
        if (isProcessing) {
            CircularProgressIndicator(
                color = CoatiTeal,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
            )
        }

        // Botón de captura
        IconButton(
            onClick = {
                if (!isCapturing && !isProcessing) {
                    isCapturing = true
                    guideColor = Color.Yellow
                    imageCapture.takePicture(
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val bitmap = imageProxyToBitmap(image)
                                image.close()
                                isCapturing = false
                                if (bitmap != null) {
                                    guideColor = CoatiTeal
                                    onFaceCapturado(bitmap)
                                } else {
                                    guideColor = Color.Red
                                    Timber.e("AttendanceFaceCamera: bitmap nulo")
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                isCapturing = false
                                guideColor = Color.Red
                                Timber.e(exception, "AttendanceFaceCamera: error en captura")
                            }
                        }
                    )
                }
            },
            enabled = !isCapturing && !isProcessing,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .size(68.dp)
                .background(CoatiTeal, CircleShape)
                .border(3.dp, Color.White, CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.Camera,
                contentDescription = "Capturar rostro",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

/**
 * Convierte un [ImageProxy] (JPEG de ImageCapture) a [Bitmap], rotado correctamente.
 */
private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
    return try {
        val buffer = imageProxy.planes[0].buffer
        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    } catch (e: Exception) {
        Timber.e(e, "AttendanceFaceCamera: error al convertir imagen")
        null
    }
}
