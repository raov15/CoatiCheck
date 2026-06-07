package com.coati.checador.feature.employeeenrollment.ui.screen

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.coati.checador.core.ui.theme.CoatiBackground
import com.coati.checador.core.ui.theme.CoatiBlue
import com.coati.checador.core.ui.theme.CoatiNavy
import com.coati.checador.core.ui.theme.CoatiTeal
import com.coati.checador.feature.employeeenrollment.ui.component.CamaraFacialCompose
import com.coati.checador.feature.employeeenrollment.ui.viewmodel.EstadoRegistro
import com.coati.checador.feature.employeeenrollment.ui.viewmodel.RegistroEmpleadoViewModel
import timber.log.Timber

/**
 * Pantalla principal del flujo de registro de empleado.
 *
 * Gestiona dos sub-vistas mediante AnimatedContent:
 * 1. [FormularioRegistro] — campos de texto y botón de captura
 * 2. [CamaraFacialCompose] — vista de cámara con validación de rostro
 *
 * @param onRegistroCompleto  Callback cuando el registro fue guardado exitosamente
 * @param onRegresar          Callback para navegar hacia atrás
 * @param viewModel           ViewModel inyectado por Hilt
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaRegistroEmpleado(
    onRegistroCompleto: () -> Unit = {},
    onRegresar: () -> Unit = {},
    viewModel: RegistroEmpleadoViewModel = hiltViewModel()
) {
    val estado by viewModel.estado.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Navegar de regreso al completar exitosamente
    LaunchedEffect(estado.registroExitoso) {
        if (estado.registroExitoso) {
            Timber.i("PantallaRegistroEmpleado: registro exitoso, volviendo al dashboard")
            onRegistroCompleto()
            viewModel.limpiarEstado()
        }
    }

    // Mostrar error general en snackbar
    LaunchedEffect(estado.errorGeneral) {
        estado.errorGeneral?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.limpiarErrorGeneral()
        }
    }

    // Diálogo de advertencia de duplicado
    if (estado.advertenciaDuplicado != null) {
        AlertDialog(
            onDismissRequest = { viewModel.descartarAdvertenciaDuplicado() },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Posible duplicado detectado") },
            text = { Text(estado.advertenciaDuplicado!!) },
            confirmButton = {
                TextButton(onClick = { viewModel.descartarAdvertenciaDuplicado() }) {
                    Text("Continuar de todas formas")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.descartarAdvertenciaDuplicado()
                        viewModel.iniciarCapturaCamara()
                    }
                ) {
                    Text("Volver a capturar")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!estado.mostrarCamara) {
                TopAppBar(
                    title = {
                        Text(
                            text = "Registro de Empleado",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onRegresar) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Regresar",
                                tint = Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = CoatiNavy
                    )
                )
            }
        }
    ) { paddingValues ->
        ContenidoRegistroEmpleado(
            estado = estado,
            paddingValues = paddingValues,
            onNombreChange = viewModel::actualizarNombre,
            onCodigoChange = viewModel::actualizarCodigo,
            onDepartamentoChange = viewModel::actualizarDepartamento,
            onHorarioEntradaChange = viewModel::actualizarHorarioEntrada,
            onHorarioSalidaChange = viewModel::actualizarHorarioSalida,
            onCapturarRostro = viewModel::iniciarCapturaCamara,
            onCancelarCapturaCamara = viewModel::cancelarCapturaCamara,
            onResultadoCaptura = viewModel::procesarResultadoCaptura,
            onGuardar = viewModel::guardarRegistro
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun ContenidoRegistroEmpleado(
    estado: EstadoRegistro,
    paddingValues: androidx.compose.foundation.layout.PaddingValues,
    onNombreChange: (String) -> Unit,
    onCodigoChange: (String) -> Unit,
    onDepartamentoChange: (String) -> Unit,
    onHorarioEntradaChange: (String) -> Unit,
    onHorarioSalidaChange: (String) -> Unit,
    onCapturarRostro: () -> Unit,
    onCancelarCapturaCamara: () -> Unit,
    onResultadoCaptura: (com.coati.checador.feature.employeeenrollment.domain.model.ResultadoValidacionRostro, Bitmap?) -> Unit,
    onGuardar: () -> Unit
) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    AnimatedContent(
        targetState = estado.mostrarCamara,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "transicion_camara"
    ) { mostrandoCamara ->
        if (mostrandoCamara) {
            if (cameraPermissionState.status.isGranted) {
                Box(modifier = Modifier.fillMaxSize()) {
                    CamaraFacialCompose(
                        onRostroCapturado = onResultadoCaptura,
                        modifier = Modifier.fillMaxSize()
                    )
                    IconButton(
                        onClick = onCancelarCapturaCamara,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Cancelar captura",
                            tint = Color.White
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .background(CoatiBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoCamera,
                                contentDescription = null,
                                tint = CoatiBlue,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "Permiso de cámara requerido",
                                style = MaterialTheme.typography.titleMedium,
                                color = CoatiNavy,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Para registrar un empleado necesitas activar la cámara y capturar su rostro en el dispositivo.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = { cameraPermissionState.launchPermissionRequest() },
                                colors = ButtonDefaults.buttonColors(containerColor = CoatiNavy)
                            ) {
                                Text("Permitir cámara")
                            }
                            OutlinedButton(onClick = onCancelarCapturaCamara) {
                                Text("Volver")
                            }
                        }
                    }
                }
            }
        } else {
            FormularioRegistro(
                estado = estado,
                paddingModifier = Modifier.padding(paddingValues),
                onNombreChange = onNombreChange,
                onCodigoChange = onCodigoChange,
                onDepartamentoChange = onDepartamentoChange,
                onHorarioEntradaChange = onHorarioEntradaChange,
                onHorarioSalidaChange = onHorarioSalidaChange,
                onCapturarRostro = {
                    onCapturarRostro()
                    if (!cameraPermissionState.status.isGranted) {
                        cameraPermissionState.launchPermissionRequest()
                    }
                },
                onGuardar = onGuardar
            )
        }
    }
}

// ─── Sub-componente: Formulario de Registro ──────────────────────────────────

@Composable
private fun FormularioRegistro(
    estado: EstadoRegistro,
    paddingModifier: Modifier,
    onNombreChange: (String) -> Unit,
    onCodigoChange: (String) -> Unit,
    onDepartamentoChange: (String) -> Unit,
    onHorarioEntradaChange: (String) -> Unit,
    onHorarioSalidaChange: (String) -> Unit,
    onCapturarRostro: () -> Unit,
    onGuardar: () -> Unit
) {
    Column(
        modifier = paddingModifier
            .fillMaxSize()
            .background(CoatiBackground)
            .verticalScroll(rememberScrollState())
    ) {
        // ─── Encabezado con logo COATI ───────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(CoatiNavy, CoatiBlue)
                    )
                )
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "COATI",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Nuevo Empleado",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Completa los datos para registrar al empleado",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ─── Sección: Datos personales ───────────────────────────────────────
            SeccionTitulo(texto = "Datos Personales")

            CampoTexto(
                valor = estado.nombreCompleto,
                onCambio = onNombreChange,
                etiqueta = "Nombre completo",
                icono = Icons.Default.Person,
                error = estado.errorNombre,
                teclado = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next
                )
            )

            CampoTexto(
                valor = estado.codigoEmpleado,
                onCambio = onCodigoChange,
                etiqueta = "ID / Código de empleado",
                icono = Icons.Default.Badge,
                error = estado.errorCodigo,
                teclado = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                )
            )

            CampoTexto(
                valor = estado.departamento,
                onCambio = onDepartamentoChange,
                etiqueta = "Departamento",
                icono = Icons.Default.Business,
                error = estado.errorDepartamento,
                teclado = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next
                )
            )

            // ─── Sección: Horario ────────────────────────────────────────────────
            SeccionTitulo(texto = "Horario Laboral")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CampoTexto(
                    valor = estado.horarioEntrada,
                    onCambio = onHorarioEntradaChange,
                    etiqueta = "Hora entrada",
                    icono = Icons.Default.Schedule,
                    placeholder = "08:00",
                    teclado = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.weight(1f)
                )

                CampoTexto(
                    valor = estado.horarioSalida,
                    onCambio = onHorarioSalidaChange,
                    etiqueta = "Hora salida",
                    icono = Icons.Default.Schedule,
                    placeholder = "17:00",
                    teclado = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            // ─── Sección: Captura facial ─────────────────────────────────────────
            SeccionTitulo(texto = "Reconocimiento Facial")

            TarjetaCapturaDeFoto(
                bitmapRostro = estado.bitmapRostro,
                rostroCapturado = estado.rostroCapturado,
                mensajeCaptura = estado.mensajeCaptura,
                errorCaptura = estado.errorCaptura,
                verificando = estado.verificandoIdentidad,
                onCapturar = onCapturarRostro
            )

            // ─── Botón guardar ───────────────────────────────────────────────────
            Spacer(modifier = Modifier.height(8.dp))

            if (estado.cargando) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = CoatiTeal)
                }
            } else {
                Button(
                    onClick = onGuardar,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CoatiNavy),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Guardar Registro",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ─── Componentes auxiliares ──────────────────────────────────────────────────

@Composable
private fun SeccionTitulo(texto: String) {
    Text(
        text = texto,
        style = MaterialTheme.typography.titleSmall,
        color = CoatiNavy,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun CampoTexto(
    valor: String,
    onCambio: (String) -> Unit,
    etiqueta: String,
    icono: androidx.compose.ui.graphics.vector.ImageVector,
    error: String? = null,
    placeholder: String? = null,
    teclado: KeyboardOptions = KeyboardOptions.Default,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    OutlinedTextField(
        value = valor,
        onValueChange = onCambio,
        label = { Text(etiqueta) },
        leadingIcon = {
            Icon(imageVector = icono, contentDescription = null, tint = CoatiBlue)
        },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
        keyboardOptions = teclado,
        singleLine = true,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp)
    )
}

@Composable
private fun TarjetaCapturaDeFoto(
    bitmapRostro: Bitmap?,
    rostroCapturado: Boolean,
    mensajeCaptura: String,
    errorCaptura: String?,
    verificando: Boolean,
    onCapturar: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (rostroCapturado) CoatiTeal.copy(alpha = 0.08f)
                            else MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Miniatura del rostro capturado o placeholder
            if (bitmapRostro != null) {
                Image(
                    bitmap = bitmapRostro.asImageBitmap(),
                    contentDescription = "Foto del empleado",
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .border(3.dp, if (rostroCapturado) CoatiTeal else CoatiBlue, CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(2.dp, CoatiBlue.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = null,
                        tint = CoatiBlue,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            // Mensaje de estado
            if (mensajeCaptura.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (rostroCapturado) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = CoatiTeal,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = mensajeCaptura,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (rostroCapturado) CoatiTeal else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Mensaje de verificación de duplicados
            if (verificando) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = CoatiBlue,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Verificando identidad...",
                        style = MaterialTheme.typography.bodySmall,
                        color = CoatiBlue
                    )
                }
            }

            // Error de captura
            if (errorCaptura != null) {
                Text(
                    text = errorCaptura,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            // Botón de captura
            OutlinedButton(
                onClick = onCapturar,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = CoatiNavy
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (rostroCapturado) "Recapturar foto" else "Capturar foto",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
