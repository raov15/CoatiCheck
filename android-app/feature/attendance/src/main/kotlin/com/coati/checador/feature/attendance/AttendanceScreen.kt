package com.coati.checador.feature.attendance

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var expanded by remember { mutableStateOf(false) }
    val selectedEmployee = state.employees.firstOrNull { it.id == state.selectedEmployeeId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Registro de Asistencia") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Configuración")
                    }
                    TextButton(onClick = onRegisterEmployee) {
                        Text(text = "Nuevo empleado")
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

            // ── Sección de cámara: reconocimiento facial ──────────────────────────
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

                // Resultado del reconocimiento
                state.recognitionMessage?.let { msg ->
                    Spacer(modifier = Modifier.height(8.dp))
                    val isRecognized = msg.startsWith("Reconocido")
                    Text(
                        text = msg,
                        color = if (isRecognized) Color(0xFF3BAF8E) else Color.DarkGray,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isRecognized) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isRecognized) Color(0xFFE8F5E9) else Color(0xFFF5F5F5),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp)
                    )
                }
            } else {
                // Solicitar permiso de cámara
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .background(Color.LightGray.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoCamera,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = Color.Gray
                        )
                        Text(
                            text = "La cámara permite identificación automática",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.DarkGray
                        )
                        Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                            Text("Permitir cámara")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (state.isLoading) {
                CircularProgressIndicator()
                return@Column
            }

            if (state.employees.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "No hay empleados registrados en este dispositivo.",
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "Usa el botón 'Nuevo empleado' para registrar al primero.")
                    }
                }
                return@Column
            }

            // ── Selector manual de empleado ───────────────────────────────────────
            Text(
                text = "Empleado",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = selectedEmployee?.let { "${it.fullName} (${it.code})" } ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Selecciona empleado") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    state.employees.forEach { employee ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(text = employee.fullName)
                                    Text(
                                        text = "${employee.code} • ${employee.department.ifBlank { "Sin departamento" }}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            },
                            onClick = {
                                viewModel.selectEmployee(employee.id)
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Tipo de registro ──────────────────────────────────────────────────
            Text(
                text = "Tipo de Registro",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Start)
            )

            attendanceTypes.forEach { type ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
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

            // ── Info de ubicación y modo offline ─────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Modo offline activo", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    val locationText = state.currentLocation?.let { loc ->
                        "Ubicación: ${"%.4f".format(loc.latitude)}, ${"%.4f".format(loc.longitude)} • ${loc.accuracyMeters?.let { "±${"%.0f".format(it)}m" } ?: ""}"
                    } ?: "Ubicación pendiente — se intentará obtener al guardar"
                    Text(text = locationText)
                    Text(text = "El registro se guardará con sync_status = pending")
                    if (!locationPermissions.permissions.all { it.status.isGranted }) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { locationPermissions.launchMultiplePermissionRequest() }) {
                            Text("Permitir ubicación")
                        }
                    }
                }
            }

            // ── Mensajes de éxito / error ─────────────────────────────────────────
            state.successMessage?.let { message ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = message, color = Color(0xFF3BAF8E), fontWeight = FontWeight.Bold)
            }

            state.errorMessage?.let { message ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = message, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Botón guardar ─────────────────────────────────────────────────────
            Button(
                onClick = viewModel::saveAttendance,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                enabled = !state.isSaving && !state.isRecognizing
            ) {
                Text(text = if (state.isSaving) "Guardando..." else "Guardar Asistencia Offline")
            }
        }
    }
}
