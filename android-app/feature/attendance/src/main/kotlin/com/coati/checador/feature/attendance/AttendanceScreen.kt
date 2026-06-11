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
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun AttendanceScreen(
    onClose: () -> Unit,
    onRegisterEmployee: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onViewHistory: () -> Unit = {},
    onViewEmployees: () -> Unit = {},
    viewModel: AttendanceViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val locationPermissions = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val attendanceTypes = listOf("Entrada", "Salida", "Entrada Comida", "Salida Comida")
    
    val recognitionMsg = state.recognitionMessage
    val isRecognized = recognitionMsg?.startsWith("Reconocido") == true
    val hasAttemptedRecognition = recognitionMsg != null
    
    val selectedEmployee = state.employees.firstOrNull { it.id == state.selectedEmployeeId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Registro de Asistencia") },
                actions = {
                    IconButton(onClick = onRegisterEmployee) {
                        Icon(Icons.Default.Person, contentDescription = "Registrar Empleado")
                    }
                    IconButton(onClick = onViewEmployees) {
                        Icon(Icons.Default.People, contentDescription = "Ver Empleados")
                    }
                    IconButton(onClick = onViewHistory) {
                        Icon(Icons.Default.History, contentDescription = "Ver Historial")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Configuración")
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar")
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
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // Camera Section
            if (cameraPermission.status.isGranted) {
                AttendanceFaceCamera(
                    onFaceCapturado = viewModel::recognizeFace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    message = "Toca el botón para identificarte",
                    isProcessing = state.isRecognizing
                )

                recognitionMsg?.let { msg ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = msg,
                        color = if (isRecognized) Color(0xFF3BAF8E) else Color.Red,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isRecognized) Color(0xFFE8F5E9) else Color(0xFFFBE9E7),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp)
                    )
                }
            } else {
                Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                    Text("Permitir Cámara para Identificación")
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (hasAttemptedRecognition && !isRecognized) {
                // Form for unrecognized employees
                Text(
                    "Información del Empleado (No Reconocido)",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Red,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.unrecognizedName,
                    onValueChange = { viewModel.updateUnrecognizedInfo(it, state.unrecognizedPosition, state.unrecognizedEmployeeNumber) },
                    label = { Text("Nombre Completo") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.unrecognizedPosition,
                    onValueChange = { viewModel.updateUnrecognizedInfo(state.unrecognizedName, it, state.unrecognizedEmployeeNumber) },
                    label = { Text("Puesto") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.unrecognizedEmployeeNumber,
                    onValueChange = { viewModel.updateUnrecognizedInfo(state.unrecognizedName, state.unrecognizedPosition, it) },
                    label = { Text("Número de Empleado") },
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (isRecognized) {
                // Info for recognized employee
                selectedEmployee?.let { emp ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Empleado: ${emp.fullName}", fontWeight = FontWeight.Bold)
                            Text("Puesto: ${emp.department}")
                            Text("No. Empleado: ${emp.code}")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Attendance Type
            Text(
                text = "Tipo de Registro",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Start)
            )
            attendanceTypes.forEach { type ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = type == state.selectedEventLabel,
                        onClick = { viewModel.selectEvent(type) }
                    )
                    Text(text = type, modifier = Modifier.padding(start = 16.dp))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // GPS Location Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val loc = state.currentLocation
                    val locationText = if (loc != null) {
                        "Ubicación: Lat ${loc.latitude}, Lon ${loc.longitude}"
                    } else {
                        "Ubicación pendiente..."
                    }
                    Text(text = locationText)
                    if (!locationPermissions.allPermissionsGranted) {
                        TextButton(onClick = { locationPermissions.launchMultiplePermissionRequest() }) {
                            Text("Permitir Ubicación (GPS)")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = viewModel::saveAttendance,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                enabled = !state.isSaving && !state.isRecognizing
            ) {
                Text(text = if (state.isSaving) "Guardando..." else "Guardar Asistencia")
            }
        }
    }
}
