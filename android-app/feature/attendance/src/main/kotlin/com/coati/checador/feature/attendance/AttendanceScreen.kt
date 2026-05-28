package com.coati.checador.feature.attendance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceScreen(
    onClose: () -> Unit
) {
    var isImageCaptured by remember { mutableStateOf(false) }
    var isRecognized by remember { mutableStateOf(false) }
    
    // Form fields for non-recognized users
    var name by remember { mutableStateOf("") }
    var position by remember { mutableStateOf("") }
    var employeeNumber by remember { mutableStateOf("") }
    
    // Attendance type
    var selectedType by remember { mutableStateOf("Entrada") }
    val attendanceTypes = listOf("Entrada", "Salida", "Entrada Comida", "Salida Comida")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Registro de Asistencia") },
                actions = {
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
            // Camera Area Placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(Color.LightGray, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (!isImageCaptured) {
                    Button(
                        onClick = { 
                            isImageCaptured = true
                            // Simulate recognition logic
                            isRecognized = false 
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Capturar Imagen")
                    }
                } else {
                    Text(
                        text = if (isRecognized) "Persona Reconocida" else "Persona No Reconocida",
                        color = if (isRecognized) Color(0xFF3BAF8E) else Color.Red,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (isImageCaptured) {
                if (!isRecognized) {
                    Text(
                        "Información del Empleado",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nombre Completo") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = position,
                        onValueChange = { position = it },
                        label = { Text("Puesto") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = employeeNumber,
                        onValueChange = { employeeNumber = it },
                        label = { Text("Número de Empleado") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    // Placeholder for recognized person info
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Empleado: Roberto Gomez", fontWeight = FontWeight.Bold)
                            Text("Puesto: Desarrollador")
                            Text("No. Empleado: 12345")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    "Tipo de Registro",
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
                            selected = (type == selectedType),
                            onClick = { selectedType = type }
                        )
                        Text(
                            text = type,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                // Simulation of GPS location
                Text(
                    "Localización: 19.4326° N, 99.1332° W (Cdmx)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { /* Handle Save Logic */ },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Guardar Información")
                }
                
                TextButton(onClick = { isImageCaptured = false }) {
                    Text("Reintentar Captura")
                }
            }
        }
    }
}
