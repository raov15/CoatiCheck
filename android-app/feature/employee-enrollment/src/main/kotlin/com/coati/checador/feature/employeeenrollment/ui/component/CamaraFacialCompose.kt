package com.coati.checador.feature.employeeenrollment.ui.component

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
import androidx.compose.ui.draw.clip
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
import com.coati.checador.feature.employeeenrollment.domain.model.ResultadoValidacionRostro
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import timber.log.Timber

/**
 * Componente Compose que muestra la vista previa de la cámara frontal
 * y realiza la validación del rostro con ML Kit Face Detection.
 *
 * Al presionar el botón de captura:
 * 1. Toma una foto con ImageCapture de CameraX.
 * 2. Convierte el ImageProxy a Bitmap y lo rota correctamente.
 * 3. Ejecuta ML Kit Face Detector para validar la presencia del rostro.
 * 4. Llama a [onRostroCapturado] con el resultado de la validación y el Bitmap.
 *
 * @param onRostroCapturado Callback: (resultado, bitmap?) → Unit
 * @param modifier          Modificador de layout externo
 */
@Composable
fun CamaraFacialCompose(
    onRostroCapturado: (ResultadoValidacionRostro, Bitmap?) -> Unit,
    modifier: Modifier = Modifier
) {
    val contexto = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var mensajeGuia by remember { mutableStateOf("Posiciona tu rostro en el óvalo") }
    var colorGuia by remember { mutableStateOf(Color.White) }

    // PreviewView creado una sola vez — evita recreación en cada recomposición
    val previewView = remember {
        PreviewView(contexto).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // CameraX: ImageCapture use case
    val imageCaptureUseCase = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    // Detector de rostros de ML Kit
    val detectorOpciones = remember {
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.25f)
            .build()
    }
    val faceDetector = remember { FaceDetection.getClient(detectorOpciones) }

    // Vincula la cámara UNA SOLA VEZ al ciclo de vida — DisposableEffect garantiza esto
    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(contexto)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val previewUseCase = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    previewUseCase,
                    imageCaptureUseCase
                )
                Timber.d("CamaraFacialCompose: cámara frontal vinculada al ciclo de vida")
            } catch (e: Exception) {
                Timber.e(e, "CamaraFacialCompose: error al vincular cámara")
            }
        }, ContextCompat.getMainExecutor(contexto))

        onDispose {
            runCatching { cameraProviderFuture.get()?.unbindAll() }
            faceDetector.close()
            Timber.d("CamaraFacialCompose: cámara liberada")
        }
    }

    Box(modifier = modifier) {
        // ─── Vista previa de CameraX ────────────────────────────────────────────
        // factory solo — no se usa update para evitar rebinding en cada recomposición
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // ─── Overlay: óvalo guía del rostro ────────────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centroX = size.width / 2f
            val centroY = size.height * 0.42f
            val radioX = size.width * 0.33f
            val radioY = size.height * 0.28f

            drawRect(
                color = Color.Black.copy(alpha = 0.45f),
                size = size
            )

            drawOval(
                color = colorGuia,
                topLeft = Offset(centroX - radioX, centroY - radioY),
                size = Size(radioX * 2, radioY * 2),
                style = Stroke(width = 4.dp.toPx())
            )
        }

        // ─── Mensaje de guía ────────────────────────────────────────────────────
        Text(
            text = mensajeGuia,
            color = colorGuia,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )

        // ─── Botón de captura ────────────────────────────────────────────────────
        IconButton(
            onClick = {
                mensajeGuia = "Procesando..."
                colorGuia = Color.Yellow
                capturarYValidar(
                    imageCaptureUseCase = imageCaptureUseCase,
                    faceDetector = faceDetector,
                    contexto = contexto,
                    onResultado = { resultado, bitmap ->
                        mensajeGuia = when (resultado) {
                            is ResultadoValidacionRostro.Valido -> "¡Rostro detectado!"
                            ResultadoValidacionRostro.SinRostro -> "No se detectó rostro"
                            is ResultadoValidacionRostro.MultiplesRostros -> "Varios rostros detectados"
                            ResultadoValidacionRostro.IluminacionInsuficiente -> "Mejora la iluminación"
                            ResultadoValidacionRostro.PosicionInvalida -> "Centra tu rostro"
                            is ResultadoValidacionRostro.Error -> "Error: ${resultado.mensaje}"
                        }
                        colorGuia = when (resultado) {
                            is ResultadoValidacionRostro.Valido -> CoatiTeal
                            else -> Color.Red
                        }
                        onRostroCapturado(resultado, bitmap)
                    }
                )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .size(72.dp)
                .background(CoatiTeal, CircleShape)
                .border(3.dp, Color.White, CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.Camera,
                contentDescription = "Capturar rostro",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

/**
 * Captura un frame con ImageCapture y lo valida con ML Kit.
 * El callback se ejecuta en el hilo principal para que sea seguro actualizar el estado Compose.
 */
private fun capturarYValidar(
    imageCaptureUseCase: ImageCapture,
    faceDetector: com.google.mlkit.vision.face.FaceDetector,
    contexto: android.content.Context,
    onResultado: (ResultadoValidacionRostro, Bitmap?) -> Unit
) {
    // Usar el executor del hilo principal para que el callback sea seguro con el estado Compose
    imageCaptureUseCase.takePicture(
        ContextCompat.getMainExecutor(contexto),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                Timber.d("CamaraFacialCompose: frame capturado, procesando con ML Kit")
                val bitmap = imageProxyABitmap(image)
                image.close()

                if (bitmap == null) {
                    onResultado(ResultadoValidacionRostro.Error("No se pudo convertir la imagen"), null)
                    return
                }

                val inputImage = InputImage.fromBitmap(bitmap, 0)
                faceDetector.process(inputImage)
                    .addOnSuccessListener { rostros ->
                        val resultado = validarRostros(rostros, bitmap)
                        onResultado(resultado, if (resultado is ResultadoValidacionRostro.Valido) bitmap else null)
                    }
                    .addOnFailureListener { e ->
                        Timber.e(e, "CamaraFacialCompose: error en ML Kit")
                        onResultado(ResultadoValidacionRostro.Error(e.message ?: "Error ML Kit"), null)
                    }
            }

            override fun onError(exception: ImageCaptureException) {
                Timber.e(exception, "CamaraFacialCompose: error al capturar frame")
                onResultado(ResultadoValidacionRostro.Error(exception.message ?: "Error de cámara"), null)
            }
        }
    )
}

/**
 * Valida las detecciones de rostro de ML Kit y retorna el resultado apropiado.
 */
private fun validarRostros(
    rostros: List<com.google.mlkit.vision.face.Face>,
    bitmap: Bitmap
): ResultadoValidacionRostro {
    return when {
        rostros.isEmpty() -> ResultadoValidacionRostro.SinRostro

        rostros.size > 1 -> ResultadoValidacionRostro.MultiplesRostros(rostros.size)

        else -> {
            val rostro = rostros[0]
            val pitch = rostro.headEulerAngleX
            val yaw = rostro.headEulerAngleY
            if (Math.abs(pitch) > 25f || Math.abs(yaw) > 25f) {
                return ResultadoValidacionRostro.PosicionInvalida
            }

            val probOjoIzq = rostro.leftEyeOpenProbability
            val probOjoDer = rostro.rightEyeOpenProbability
            if (probOjoIzq != null && probOjoDer != null) {
                if (probOjoIzq < 0.4f || probOjoDer < 0.4f) {
                    return ResultadoValidacionRostro.IluminacionInsuficiente
                }
            }

            val areaRostro = rostro.boundingBox.width().toFloat() * rostro.boundingBox.height().toFloat()
            val areaImagen = bitmap.width.toFloat() * bitmap.height.toFloat()
            val cobertura = (areaRostro / areaImagen).coerceIn(0f, 1f)

            ResultadoValidacionRostro.Valido(puntuacionCalidad = cobertura)
        }
    }
}

/**
 * Convierte un [ImageProxy] (formato JPEG de ImageCapture) a [Bitmap] ARGB_8888.
 * Aplica la rotación correcta. Llama a rewind() para asegurar posición 0 del buffer.
 */
private fun imageProxyABitmap(imageProxy: ImageProxy): Bitmap? {
    return try {
        val planoBuffer = imageProxy.planes[0].buffer
        planoBuffer.rewind() // Asegurar que la posición del buffer está en 0
        val bytes = ByteArray(planoBuffer.remaining())
        planoBuffer.get(bytes)

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return null

        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    } catch (e: Exception) {
        Timber.e(e, "CamaraFacialCompose: error al convertir ImageProxy a Bitmap")
        null
    }
}
