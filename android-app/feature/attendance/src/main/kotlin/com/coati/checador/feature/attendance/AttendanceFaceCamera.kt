package com.coati.checador.feature.attendance

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

private enum class LivenessChallenge(
    val instruction: String
) {
    TURN_LEFT("Gira la cabeza a la izquierda"),
    TURN_RIGHT("Gira la cabeza a la derecha")
}

private enum class LivenessStage {
    WAITING_CENTER,
    DO_CHALLENGE,
    RETURN_CENTER,
    VERIFIED
}

@Composable
fun AttendanceFaceCamera(
    onFaceCapturado: (Bitmap) -> Unit,
    modifier: Modifier = Modifier,
    message: String = "Verificación facial",
    isProcessing: Boolean = false
) {
    val context =
        LocalContext.current

    val lifecycleOwner =
        LocalLifecycleOwner.current

    var guideColor by remember {
        mutableStateOf(Color.White)
    }

    var isCapturing by remember {
        mutableStateOf(false)
    }

    var challenge by remember {
        mutableStateOf(
            LivenessChallenge.entries.random()
        )
    }

    var stage by remember {
        mutableStateOf(
            LivenessStage.WAITING_CENTER
        )
    }

    var stableFrames by remember {
        mutableIntStateOf(0)
    }

    var challengeFrames by remember {
        mutableIntStateOf(0)
    }

    var returnFrames by remember {
        mutableIntStateOf(0)
    }

    var lastFaceSeenAt by remember {
        mutableLongStateOf(0L)
    }

    var statusText by remember {
        mutableStateOf(
            "Centra tu rostro"
        )
    }

    val previewView =
        remember {
            PreviewView(context).apply {
                scaleType =
                    PreviewView.ScaleType.FILL_CENTER
            }
        }

    val imageCapture =
        remember {
            ImageCapture.Builder()
                .setCaptureMode(
                    ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                )
                .build()
        }

    val imageAnalysis =
        remember {
            ImageAnalysis.Builder()
                .setBackpressureStrategy(
                    ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                )
                .build()
        }

    val detectorOptions =
        remember {
            FaceDetectorOptions.Builder()
                .setPerformanceMode(
                    FaceDetectorOptions.PERFORMANCE_MODE_FAST
                )
                .setLandmarkMode(
                    FaceDetectorOptions.LANDMARK_MODE_NONE
                )
                .setClassificationMode(
                    FaceDetectorOptions.CLASSIFICATION_MODE_ALL
                )
                .setMinFaceSize(
                    0.20f
                )
                .build()
        }

    val faceDetector =
        remember {
            FaceDetection.getClient(
                detectorOptions
            )
        }

    val analysisExecutor =
        remember {
            Executors
                .newSingleThreadExecutor()
        }

    val analysisBusy =
        remember {
            AtomicBoolean(false)
        }

    fun captureFinalImage() {

        if (
            isCapturing ||
            isProcessing
        ) {
            return
        }

        isCapturing =
            true

        guideColor =
            Color.Yellow

        statusText =
            "Capturando..."

        imageCapture.takePicture(
            ContextCompat
                .getMainExecutor(
                    context
                ),
            object :
                ImageCapture.OnImageCapturedCallback() {

                override fun onCaptureSuccess(
                    image: ImageProxy
                ) {

                    val bitmap =
                        imageProxyToBitmap(
                            image
                        )

                    image.close()

                    isCapturing =
                        false

                    if (bitmap != null) {

                        guideColor =
                            CoatiTeal

                        statusText =
                            "Prueba de vida superada"

                        onFaceCapturado(
                            bitmap
                        )

                    } else {

                        guideColor =
                            Color.Red

                        statusText =
                            "No se pudo capturar el rostro"
                    }
                }

                override fun onError(
                    exception:
                        ImageCaptureException
                ) {

                    isCapturing =
                        false

                    guideColor =
                        Color.Red

                    statusText =
                        "Error al capturar"

                    Timber.e(
                        exception,
                        "AttendanceFaceCamera: error en captura"
                    )
                }
            }
        )
    }

    DisposableEffect(
        lifecycleOwner
    ) {

        val future =
            ProcessCameraProvider
                .getInstance(
                    context
                )

        imageAnalysis.setAnalyzer(
            analysisExecutor
        ) { imageProxy ->

            if (
                analysisBusy
                    .getAndSet(true)
            ) {
                imageProxy.close()
                return@setAnalyzer
            }

            val mediaImage =
                imageProxy.image

            if (mediaImage == null) {
                analysisBusy.set(false)
                imageProxy.close()
                return@setAnalyzer
            }

            val inputImage =
                InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy
                        .imageInfo
                        .rotationDegrees
                )

            faceDetector
                .process(inputImage)
                .addOnSuccessListener {
                    faces: List<Face> ->

                    val now =
                        System.currentTimeMillis()

                    if (
                        faces.size != 1
                    ) {

                        if (
                            faces.isEmpty()
                        ) {

                            if (
                                now -
                                lastFaceSeenAt >
                                800L
                            ) {

                                statusText =
                                    "Coloca un solo rostro en el óvalo"

                                guideColor =
                                    Color.White
                            }

                        } else {

                            statusText =
                                "Solo puede haber una persona"

                            guideColor =
                                Color.Red
                        }

                        stableFrames = 0
                        challengeFrames = 0
                        returnFrames = 0

                        return@addOnSuccessListener
                    }

                    lastFaceSeenAt =
                        now

                    val face =
                        faces.first()

                    val centered =
                        isFaceCentered(
                            face
                        )

                    when (stage) {

                        LivenessStage.WAITING_CENTER -> {

                            if (centered) {

                                stableFrames++

                                guideColor =
                                    Color.Yellow

                                statusText =
                                    "Mantente al centro..."

                                if (
                                    stableFrames >=
                                    REQUIRED_STABLE_FRAMES
                                ) {

                                    stage =
                                        LivenessStage.DO_CHALLENGE

                                    challengeFrames =
                                        0

                                    guideColor =
                                        Color.White

                                    statusText =
                                        challenge.instruction
                                }

                            } else {

                                stableFrames =
                                    0

                                guideColor =
                                    Color.White

                                statusText =
                                    "Centra tu rostro"
                            }
                        }

                        LivenessStage.DO_CHALLENGE -> {

                            val passed =
                                challengePassed(
                                    face,
                                    challenge
                                )

                            if (passed) {

                                challengeFrames++

                                guideColor =
                                    Color.Yellow

                                statusText =
                                    "${challenge.instruction} ✓"

                                if (
                                    challengeFrames >=
                                    REQUIRED_CHALLENGE_FRAMES
                                ) {

                                    stage =
                                        LivenessStage.RETURN_CENTER

                                    returnFrames =
                                        0

                                    statusText =
                                        "Vuelve al centro"
                                }

                            } else {

                                challengeFrames =
                                    0

                                guideColor =
                                    Color.White

                                statusText =
                                    challenge.instruction
                            }
                        }

                        LivenessStage.RETURN_CENTER -> {

                            if (centered) {

                                returnFrames++

                                guideColor =
                                    Color.Yellow

                                statusText =
                                    "Mantente al centro..."

                                if (
                                    returnFrames >=
                                    REQUIRED_RETURN_FRAMES
                                ) {

                                    stage =
                                        LivenessStage.VERIFIED

                                    guideColor =
                                        CoatiTeal

                                    statusText =
                                        "Prueba de vida superada"

                                    ContextCompat
                                        .getMainExecutor(
                                            context
                                        )
                                        .execute {
                                            captureFinalImage()
                                        }
                                }

                            } else {

                                returnFrames =
                                    0

                                guideColor =
                                    Color.White

                                statusText =
                                    "Vuelve al centro"
                            }
                        }

                        LivenessStage.VERIFIED -> {
                            guideColor =
                                CoatiTeal
                        }
                    }
                }
                .addOnFailureListener {
                    error: Exception ->

                    Timber.e(
                        error,
                        "AttendanceFaceCamera: error analizando rostro"
                    )

                    statusText =
                        "No se pudo analizar el rostro"

                    guideColor =
                        Color.Red
                }
                .addOnCompleteListener {

                    analysisBusy.set(
                        false
                    )

                    imageProxy.close()
                }
        }

        future.addListener(
            {
                val provider =
                    future.get()

                val preview =
                    Preview.Builder()
                        .build()
                        .also {
                            it.setSurfaceProvider(
                                previewView
                                    .surfaceProvider
                            )
                        }

                try {

                    provider.unbindAll()

                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector
                            .DEFAULT_FRONT_CAMERA,
                        preview,
                        imageCapture,
                        imageAnalysis
                    )

                } catch (
                    e: Exception
                ) {

                    Timber.e(
                        e,
                        "AttendanceFaceCamera: error al vincular cámara"
                    )
                }
            },
            ContextCompat
                .getMainExecutor(
                    context
                )
        )

        onDispose {

            imageAnalysis
                .clearAnalyzer()

            runCatching {
                future
                    .get()
                    ?.unbindAll()
            }

            analysisExecutor
                .shutdown()

            runCatching {
                faceDetector.close()
            }
        }
    }

    Box(
        modifier =
            modifier
    ) {

        AndroidView(
            factory = {
                previewView
            },
            modifier =
                Modifier.fillMaxSize()
        )

        Canvas(
            modifier =
                Modifier.fillMaxSize()
        ) {

            val cx =
                size.width / 2f

            val cy =
                size.height * 0.44f

            val rx =
                size.width * 0.22f

            val ry =
                size.width * 0.32f

            drawRect(
                color =
                    Color.Black.copy(
                        alpha = 0.4f
                    ),
                size =
                    size
            )

            drawOval(
                color =
                    guideColor,
                topLeft =
                    Offset(
                        cx - rx,
                        cy - ry
                    ),
                size =
                    Size(
                        rx * 2,
                        ry * 2
                    ),
                style =
                    Stroke(
                        width =
                            4.dp.toPx()
                    )
            )
        }

        Text(
            text =
                when {

                    isProcessing ->
                        "Reconociendo identidad..."

                    isCapturing ->
                        "Capturando..."

                    stage ==
                        LivenessStage.VERIFIED ->
                        "Prueba de vida superada"

                    else ->
                        statusText
                },
            color =
                Color.White,
            style =
                MaterialTheme
                    .typography
                    .labelLarge,
            modifier =
                Modifier
                    .align(
                        Alignment.TopCenter
                    )
                    .padding(
                        top = 16.dp
                    )
                    .background(
                        Color.Black.copy(
                            alpha = 0.65f
                        ),
                        RoundedCornerShape(
                            8.dp
                        )
                    )
                    .padding(
                        horizontal = 16.dp,
                        vertical = 8.dp
                    )
        )

        if (
            isProcessing ||
            isCapturing
        ) {

            CircularProgressIndicator(
                color =
                    CoatiTeal,
                modifier =
                    Modifier
                        .align(
                            Alignment.Center
                        )
                        .size(
                            48.dp
                        )
            )
        }
    }
}

private fun isFaceCentered(
    face: Face
): Boolean {

    val pitch: Float =
        face.headEulerAngleX

    val yaw: Float =
        face.headEulerAngleY

    return (
        abs(pitch) <=
            CENTER_PITCH_LIMIT &&
        abs(yaw) <=
            CENTER_YAW_LIMIT
    )
}

private fun challengePassed(
    face: Face,
    challenge:
        LivenessChallenge
): Boolean {

    val yaw: Float =
        face.headEulerAngleY

    return when (challenge) {

        LivenessChallenge.TURN_LEFT ->
            yaw >
                CHALLENGE_YAW_THRESHOLD

        LivenessChallenge.TURN_RIGHT ->
            yaw <
                -CHALLENGE_YAW_THRESHOLD
    }
}

private fun imageProxyToBitmap(
    imageProxy: ImageProxy
): Bitmap? {

    return try {

        val buffer =
            imageProxy
                .planes[0]
                .buffer

        buffer.rewind()

        val bytes =
            ByteArray(
                buffer.remaining()
            )

        buffer.get(
            bytes
        )

        val bitmap =
            BitmapFactory
                .decodeByteArray(
                    bytes,
                    0,
                    bytes.size
                )
                ?: return null

        val rotation =
            imageProxy
                .imageInfo
                .rotationDegrees

        if (
            rotation != 0
        ) {

            val matrix =
                Matrix().apply {
                    postRotate(
                        rotation.toFloat()
                    )
                }

            Bitmap
                .createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    matrix,
                    true
                )

        } else {

            bitmap
        }

    } catch (
        e: Exception
    ) {

        Timber.e(
            e,
            "AttendanceFaceCamera: error al convertir imagen"
        )

        null
    }
}

private const val CENTER_YAW_LIMIT =
    10f

private const val CENTER_PITCH_LIMIT =
    10f

private const val CHALLENGE_YAW_THRESHOLD =
    18f

private const val REQUIRED_STABLE_FRAMES =
    4

private const val REQUIRED_CHALLENGE_FRAMES =
    3

private const val REQUIRED_RETURN_FRAMES =
    3