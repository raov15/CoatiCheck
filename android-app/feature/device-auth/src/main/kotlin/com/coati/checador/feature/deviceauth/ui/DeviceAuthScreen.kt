package com.coati.checador.feature.deviceauth.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceAuthScreen(
    onBack: () -> Unit,
    viewModel: DeviceAuthViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Autenticacion del dispositivo") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Regresar")
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // Estado actual del dispositivo
            state.device?.let { device ->
                DeviceStatusCard(device = device)
                Spacer(modifier = Modifier.height(4.dp))
            }

            Text(
                text = if (state.device?.isRegistered == true)
                    "Re-registrar dispositivo"
                else
                    "Registrar dispositivo en el servidor",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = state.deviceName,
                onValueChange = viewModel::updateDeviceName,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Nombre del dispositivo") },
                placeholder = { Text("ej. Kiosk-Recepcion-01") },
                singleLine = true
            )

            OutlinedTextField(
                value = state.apiBaseUrl,
                onValueChange = viewModel::updateApiBaseUrl,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("URL del servidor (API Base URL)") },
                placeholder = { Text("ej. https://api.coati.io/api/") },
                singleLine = true
            )

            OutlinedTextField(
                value = state.enrollmentCode,
                onValueChange = viewModel::updateEnrollmentCode,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Código de enrolamiento empresarial") },
                placeholder = { Text("Código generado por el administrador") },
                singleLine = true
            )

            state.successMessage?.let { msg ->
                Text(text = msg, color = Color(0xFF3BAF8E), fontWeight = FontWeight.Bold)
            }

            state.errorMessage?.let { msg ->
                Text(text = msg, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = viewModel::register,
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Registrar en servidor")
                }
            }

            OutlinedButton(
                onClick = viewModel::enroll,
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Asociar celular a empresa")
            }

            if (state.device?.isRegistered == true) {
                OutlinedButton(
                    onClick = viewModel::verifyCurrentToken,
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Verificar token actual")
                }
            }
        }
    }
}

@Composable
private fun DeviceStatusCard(device: com.coati.checador.feature.deviceauth.domain.model.Device) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isRegistered)
                Color(0xFF1B2A4A)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (device.isRegistered) Icons.Default.CheckCircle else Icons.Default.DevicesOther,
                contentDescription = null,
                tint = if (device.isRegistered) Color(0xFF3BAF8E) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp)
            )
            Column {
                Text(
                    text = device.deviceName,
                    fontWeight = FontWeight.Bold,
                    color = if (device.isRegistered) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (device.isRegistered) "Registrado en servidor" else "Solo local — sin registrar",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (device.isRegistered) Color(0xFF3BAF8E) else MaterialTheme.colorScheme.error
                )
                device.idRemote?.let {
                    Text(
                        text = "ID remoto: ${it.take(8)}…",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}
