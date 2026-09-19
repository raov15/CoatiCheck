package com.coati.checador.feature.attendance

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.rememberMultiplePermissionsState

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalPermissionsApi::class
)
@Composable
fun AttendanceScreen(
    onClose: () -> Unit,
    onRegisterEmployee: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onViewHistory: () -> Unit = {},
    viewModel: AttendanceViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    val snackbarHostState =
        remember { SnackbarHostState() }

    var showCamera by remember {
        mutableStateOf(false)
    }

    val locationPermissions =
        rememberMultiplePermissionsState(
            permissions = listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )

    val cameraPermission =
        rememberPermissionState(
            Manifest.permission.CAMERA
        )

    val attendanceTypes =
        listOf(
            "Entró a trabajar",
            "Salió del trabajo",
            "Salió a comer",
            "Regresó de comer"
        )

    val recognitionMsg =
        state.recognitionMessage

    val isRecognized =
        recognitionMsg?.startsWith("Reconocido") == true

    val hasAttemptedRecognition =
        recognitionMsg != null

    val selectedEmployee =
        state.employees.firstOrNull {
            it.id == state.selectedEmployeeId
        }

    /*
     * Cuando termina el reconocimiento, cerramos la cámara.
     * Si fue correcto, queda habilitado Guardar asistencia.
     * Si falló, vuelve a aparecer el botón Verificar rostro.
     */
    LaunchedEffect(
        state.isRecognizing,
        state.recognitionMessage
    ) {
        if (
            !state.isRecognizing &&
            state.recognitionMessage != null
        ) {
            showCamera = false
        }
    }

   LaunchedEffect(state.successMessage) {
    state.successMessage?.let { mensaje ->

        val snackbarJob = launch {
            snackbarHostState.showSnackbar(
                message = "✓ $mensaje",
                duration = SnackbarDuration.Indefinite
            )
        }

        // Mantener el mensaje visible durante 8 segundos
        delay(8_000)

        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarJob.cancel()

        viewModel.clearMessages()
    }
}

LaunchedEffect(state.errorMessage) {
    state.errorMessage?.let { mensaje ->

        val snackbarJob = launch {
            snackbarHostState.showSnackbar(
                message = mensaje,
                duration = SnackbarDuration.Indefinite
            )
        }

        // Mantener el mensaje visible durante 8 segundos
        delay(8_000)

        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarJob.cancel()

        viewModel.clearMessages()
    }
}

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState
            ) { snackbarData ->
                Snackbar(
                    snackbarData = snackbarData,
                    containerColor = Color(0xFF172642),
                    contentColor = Color.White
                )
            }
        },

        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Registro de Asistencia"
                    )
                },

                actions = {
                    IconButton(
                        onClick = onRegisterEmployee
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Registrar Empleado"
                        )
                    }

                    IconButton(
                        onClick = onViewHistory
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "Ver Historial"
                        )
                    }

                    IconButton(
                        onClick = onOpenSettings
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Configuración"
                        )
                    }

                    IconButton(
                        onClick = onClose
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(
                    rememberScrollState()
                ),
            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            // =====================================================
            // VERIFICACIÓN FACIAL
            // =====================================================

            if (!showCamera) {

                Button(
                    onClick = {
                        if (cameraPermission.status.isGranted) {
                            showCamera = true
                        } else {
                            cameraPermission.launchPermissionRequest()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(8.dp),
                    enabled =
                        !state.isSaving &&
                        !state.isRecognizing
                ) {
                    Text(
                        text =
                            if (isRecognized) {
                                "Verificar rostro nuevamente"
                            } else {
                                "Verificar rostro"
                            }
                    )
                }

            } else {

                AttendanceFaceCamera(
                    onFaceCapturado =
                        viewModel::recognizeFace,

                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(
                            RoundedCornerShape(16.dp)
                        ),

                    message =
                        "Verificación facial",

                    isProcessing =
                        state.isRecognizing
                )
            }

            Spacer(
                modifier = Modifier.height(12.dp)
            )

            recognitionMsg?.let { msg ->

                Text(
                    text = msg,
                    color =
                        if (isRecognized) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                    style =
                        MaterialTheme.typography.bodyMedium,
                    fontWeight =
                        FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color =
                                if (isRecognized) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.errorContainer
                                },
                            shape =
                                RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp)
                )
            }

            Spacer(
                modifier = Modifier.height(20.dp)
            )

            // =====================================================
            // RESULTADO DEL RECONOCIMIENTO
            // =====================================================

            if (
                hasAttemptedRecognition &&
                !isRecognized
            ) {

                Card(
                    modifier =
                        Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor =
                                MaterialTheme.colorScheme.errorContainer
                        )
                ) {
                    Column(
                        modifier =
                            Modifier.padding(16.dp)
                    ) {

                        Text(
                            text =
                                "Rostro no reconocido",
                            color =
                                MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight =
                                FontWeight.Bold,
                            style =
                                MaterialTheme.typography.titleMedium
                        )

                        Spacer(
                            modifier =
                                Modifier.height(6.dp)
                        )

                        Text(
                            text =
                                "No puedes registrar asistencia hasta completar la prueba de vida y ser reconocido.",
                            color =
                                MaterialTheme.colorScheme.onErrorContainer,
                            style =
                                MaterialTheme.typography.bodyMedium
                        )
                    }
                }

            } else if (isRecognized) {

                selectedEmployee?.let { emp ->

                    Card(
                        modifier =
                            Modifier.fillMaxWidth(),
                        colors =
                            CardDefaults.cardColors(
                                containerColor =
                                    MaterialTheme.colorScheme.surfaceVariant
                            )
                    ) {

                        Column(
                            modifier =
                                Modifier.padding(16.dp)
                        ) {

                            Text(
                                text =
                                    "✓ Rostro verificado",
                                color =
                                    MaterialTheme.colorScheme.primary,
                                fontWeight =
                                    FontWeight.Bold,
                                style =
                                    MaterialTheme.typography.titleMedium
                            )

                            Spacer(
                                modifier =
                                    Modifier.height(8.dp)
                            )

                            Text(
                                text =
                                    "Empleado: ${emp.fullName}",
                                color =
                                    MaterialTheme.colorScheme.onSurface,
                                fontWeight =
                                    FontWeight.SemiBold
                            )

                            Spacer(
                                modifier =
                                    Modifier.height(4.dp)
                            )

                            Text(
                                text =
                                    "Cargo: ${emp.department}",
                                color =
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Text(
                                text =
                                    "Código: ${emp.code}",
                                color =
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(
                modifier = Modifier.height(20.dp)
            )

            // =====================================================
            // TIPO DE REGISTRO
            // =====================================================

            Text(
                text =
                    "Tipo de Registro",
                style =
                    MaterialTheme.typography.titleMedium,
                modifier =
                    Modifier.align(
                        Alignment.Start
                    )
            )

            attendanceTypes.forEach { type ->

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected =
                            type ==
                                state.selectedEventLabel,
                        onClick = {
                            if (!state.isSaving) {
                                viewModel.selectEvent(type)
                            }
                        },
                        enabled =
                            !state.isSaving
                    )

                    Text(
                        text = type,
                        modifier =
                            Modifier.padding(
                                start = 16.dp
                            )
                    )
                }
            }

            Spacer(
                modifier = Modifier.height(20.dp)
            )

            // =====================================================
            // GPS
            // =====================================================

            Card(
                modifier =
                    Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            MaterialTheme
                                .colorScheme
                                .surfaceVariant
                    )
            ) {
                Column(
                    modifier =
                        Modifier.padding(16.dp)
                ) {

                    val loc =
                        state.currentLocation

                    val locationText =
                        if (loc != null) {
                            "Ubicación: Lat ${loc.latitude}, Lon ${loc.longitude}"
                        } else {
                            "Ubicación pendiente..."
                        }

                    Text(
                        text = locationText
                    )

                    if (
                        !locationPermissions
                            .allPermissionsGranted
                    ) {
                        TextButton(
                            onClick = {
                                locationPermissions
                                    .launchMultiplePermissionRequest()
                            },
                            enabled =
                                !state.isSaving
                        ) {
                            Text(
                                "Permitir Ubicación (GPS)"
                            )
                        }
                    }
                }
            }

            Spacer(
                modifier = Modifier.height(24.dp)
            )

            // =====================================================
            // BOTÓN GUARDAR
            // =====================================================

            if (!isRecognized) {
                Text(
                    text =
                        "Primero verifica tu rostro para habilitar el registro.",
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    style =
                        MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            bottom = 8.dp
                        )
                )
            }

            Button(
                onClick = {
                    if (
                        isRecognized &&
                        !state.isSaving &&
                        !state.isRecognizing
                    ) {
                        viewModel.saveAttendance()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape =
                    RoundedCornerShape(8.dp),
                enabled =
                    isRecognized &&
                    !state.isSaving &&
                    !state.isRecognizing
            ) {

                if (state.isSaving) {

                    CircularProgressIndicator(
                        modifier =
                            Modifier.size(20.dp),
                        strokeWidth =
                            2.dp,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onPrimary
                    )

                    Spacer(
                        modifier =
                            Modifier.width(8.dp)
                    )

                    Text(
                        text = "Guardando..."
                    )

                } else {

                    Text(
                        text =
                            "Guardar Asistencia"
                    )
                }
            }

            Spacer(
                modifier =
                    Modifier.height(24.dp)
            )
        }
    }
}