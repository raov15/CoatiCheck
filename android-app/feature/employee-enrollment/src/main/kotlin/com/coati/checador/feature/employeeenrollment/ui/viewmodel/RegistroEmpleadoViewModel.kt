package com.coati.checador.feature.employeeenrollment.ui.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coati.checador.core.common.Result
import com.coati.checador.core.database.model.SyncStatus
import com.coati.checador.feature.employeeenrollment.domain.model.Empleado
import com.coati.checador.feature.employeeenrollment.domain.model.ResultadoValidacionRostro
import com.coati.checador.feature.employeeenrollment.domain.model.ResultadoVerificacion
import com.coati.checador.feature.employeeenrollment.domain.usecase.RegistrarEmpleadoUseCase
import com.coati.checador.feature.employeeenrollment.domain.usecase.VerificarIdentidadUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel del flujo de registro de empleado.
 *
 * Gestiona el estado de la UI a través de [EstadoRegistro] expuesto
 * como [StateFlow] inmutable para las pantallas Compose.
 *
 * Sigue MVVM + UseCase: no tiene acceso directo a Room ni a servicios,
 * solo se comunica con los casos de uso de la capa de dominio.
 */
@HiltViewModel
class RegistroEmpleadoViewModel @Inject constructor(
    private val registrarEmpleadoUseCase: RegistrarEmpleadoUseCase,
    private val verificarIdentidadUseCase: VerificarIdentidadUseCase
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoRegistro())
    val estado: StateFlow<EstadoRegistro> = _estado.asStateFlow()

    // ─── Actualización de campos del formulario ─────────────────────────────────

    fun actualizarNombre(valor: String) {
        _estado.update { it.copy(nombreCompleto = valor, errorNombre = null) }
    }

    fun actualizarCodigo(valor: String) {
        _estado.update { it.copy(codigoEmpleado = valor, errorCodigo = null) }
    }

    fun actualizarDepartamento(valor: String) {
        _estado.update { it.copy(departamento = valor, errorDepartamento = null) }
    }

    fun actualizarHorarioEntrada(valor: String) {
        _estado.update { it.copy(horarioEntrada = valor) }
    }

    fun actualizarHorarioSalida(valor: String) {
        _estado.update { it.copy(horarioSalida = valor) }
    }

    // ─── Flujo de captura facial ─────────────────────────────────────────────────

    fun iniciarCapturaCamara() {
        Timber.d("RegistroEmpleadoViewModel: navegando a captura de cámara")
        _estado.update { it.copy(mostrarCamara = true, errorCaptura = null) }
    }

    fun cancelarCapturaCamara() {
        _estado.update { it.copy(mostrarCamara = false) }
    }

    /**
     * Procesa el resultado de la captura facial.
     * Si es válido, lanza la verificación anti-duplicados en background.
     */
    fun procesarResultadoCaptura(
        resultado: ResultadoValidacionRostro,
        bitmap: Bitmap?
    ) {
        when (resultado) {
            is ResultadoValidacionRostro.Valido -> {
                Timber.i("RegistroEmpleadoViewModel: rostro válido, calidad=${resultado.puntuacionCalidad}")
                _estado.update {
                    it.copy(
                        mostrarCamara = false,
                        bitmapRostro = bitmap,
                        rostroCapturado = true,
                        errorCaptura = null,
                        mensajeCaptura = "Rostro capturado correctamente"
                    )
                }
                // Verificar anti-duplicados en paralelo
                if (bitmap != null) verificarDuplicado(bitmap)
            }

            ResultadoValidacionRostro.SinRostro -> {
                _estado.update {
                    it.copy(errorCaptura = "No se detectó ningún rostro. Intenta de nuevo.")
                }
            }

            is ResultadoValidacionRostro.MultiplesRostros -> {
                _estado.update {
                    it.copy(errorCaptura = "Se detectaron ${resultado.cantidad} rostros. Asegúrate de estar solo frente a la cámara.")
                }
            }

            ResultadoValidacionRostro.IluminacionInsuficiente -> {
                _estado.update {
                    it.copy(errorCaptura = "Iluminación insuficiente. Busca un lugar con más luz.")
                }
            }

            ResultadoValidacionRostro.PosicionInvalida -> {
                _estado.update {
                    it.copy(errorCaptura = "Centra tu rostro y mira directo a la cámara.")
                }
            }

            is ResultadoValidacionRostro.Error -> {
                Timber.e("RegistroEmpleadoViewModel: error en captura: ${resultado.mensaje}")
                _estado.update {
                    it.copy(errorCaptura = "Error al procesar la imagen: ${resultado.mensaje}")
                }
            }
        }
    }

    private fun verificarDuplicado(bitmap: Bitmap) {
        viewModelScope.launch {
            Timber.d("RegistroEmpleadoViewModel: verificando identidad 1:N")
            _estado.update { it.copy(verificandoIdentidad = true) }

            when (val resultado = verificarIdentidadUseCase(bitmap)) {
                ResultadoVerificacion.NoEncontrado -> {
                    Timber.d("RegistroEmpleadoViewModel: no hay duplicados")
                    _estado.update { it.copy(verificandoIdentidad = false, advertenciaDuplicado = null) }
                }

                is ResultadoVerificacion.Coincidencia -> {
                    Timber.w("RegistroEmpleadoViewModel: posible duplicado: ${resultado.nombreEmpleado}")
                    _estado.update {
                        it.copy(
                            verificandoIdentidad = false,
                            advertenciaDuplicado = "Este rostro es muy similar al de '${resultado.nombreEmpleado}' " +
                                "(distancia: ${"%.2f".format(resultado.distancia)}). " +
                                "¿Deseas continuar de todas formas?"
                        )
                    }
                }

                is ResultadoVerificacion.Error -> {
                    Timber.e("RegistroEmpleadoViewModel: error en verificación: ${resultado.mensaje}")
                    _estado.update { it.copy(verificandoIdentidad = false) }
                }
            }
        }
    }

    fun descartarAdvertenciaDuplicado() {
        _estado.update { it.copy(advertenciaDuplicado = null) }
    }

    // ─── Registro final ──────────────────────────────────────────────────────────

    /**
     * Valida el formulario y ejecuta el caso de uso de registro.
     * Los datos se guardan inmediatamente en Room (OFFLINE FIRST).
     */
    fun guardarRegistro() {
        val estadoActual = _estado.value

        // Validar campos
        val errores = validarFormulario(estadoActual)
        if (errores.isNotEmpty()) {
            _estado.update {
                it.copy(
                    errorNombre = errores["nombre"],
                    errorCodigo = errores["codigo"],
                    errorDepartamento = errores["departamento"],
                    errorCaptura = errores["rostro"]
                )
            }
            return
        }

        viewModelScope.launch {
            Timber.i("RegistroEmpleadoViewModel: iniciando registro de ${estadoActual.nombreCompleto}")
            _estado.update { it.copy(cargando = true, errorGeneral = null) }

            val empleado = Empleado(
                idLocal = UUID.randomUUID().toString(),
                codigoEmpleado = estadoActual.codigoEmpleado.trim(),
                nombreCompleto = estadoActual.nombreCompleto.trim(),
                departamento = estadoActual.departamento.trim(),
                horarioEntrada = estadoActual.horarioEntrada,
                horarioSalida = estadoActual.horarioSalida,
                activo = true,
                creadoEn = System.currentTimeMillis(),
                estadoSync = SyncStatus.PENDING
            )

            when (val resultado = registrarEmpleadoUseCase(empleado, estadoActual.bitmapRostro!!)) {
                is Result.Success -> {
                    Timber.i("RegistroEmpleadoViewModel: empleado registrado exitosamente")
                    _estado.update {
                        it.copy(
                            cargando = false,
                            registroExitoso = true,
                            errorGeneral = null
                        )
                    }
                }

                is Result.Error -> {
                    Timber.e(resultado.exception, "RegistroEmpleadoViewModel: error al registrar")
                    _estado.update {
                        it.copy(
                            cargando = false,
                            errorGeneral = resultado.message ?: "Error desconocido al guardar"
                        )
                    }
                }

                Result.Loading -> { /* No se emite Loading desde el UseCase */ }
            }
        }
    }

    fun limpiarEstado() {
        _estado.update { EstadoRegistro() }
    }

    fun limpiarErrorGeneral() {
        _estado.update { it.copy(errorGeneral = null) }
    }

    // ─── Validación del formulario ───────────────────────────────────────────────

    private fun validarFormulario(estado: EstadoRegistro): Map<String, String> {
        val errores = mutableMapOf<String, String>()

        if (estado.nombreCompleto.isBlank()) {
            errores["nombre"] = "El nombre completo es requerido"
        } else if (estado.nombreCompleto.trim().length < 3) {
            errores["nombre"] = "El nombre debe tener al menos 3 caracteres"
        }

        if (estado.codigoEmpleado.isBlank()) {
            errores["codigo"] = "El código de empleado es requerido"
        }

        if (estado.departamento.isBlank()) {
            errores["departamento"] = "El departamento es requerido"
        }

        if (!estado.rostroCapturado || estado.bitmapRostro == null) {
            errores["rostro"] = "Debes capturar la imagen facial del empleado"
        }

        return errores
    }
}

/**
 * Estado inmutable de la pantalla de registro de empleado.
 * Expuesto como StateFlow para las pantallas Compose.
 */
data class EstadoRegistro(
    // ─── Campos del formulario ──────────────────────────────────────────────────
    val nombreCompleto: String = "",
    val codigoEmpleado: String = "",
    val departamento: String = "",
    val horarioEntrada: String = "08:00",
    val horarioSalida: String = "17:00",

    // ─── Captura facial ─────────────────────────────────────────────────────────
    val mostrarCamara: Boolean = false,
    val bitmapRostro: Bitmap? = null,
    val rostroCapturado: Boolean = false,
    val mensajeCaptura: String = "",
    val verificandoIdentidad: Boolean = false,
    val advertenciaDuplicado: String? = null,

    // ─── Errores de validación ──────────────────────────────────────────────────
    val errorNombre: String? = null,
    val errorCodigo: String? = null,
    val errorDepartamento: String? = null,
    val errorCaptura: String? = null,
    val errorGeneral: String? = null,

    // ─── Estado de proceso ──────────────────────────────────────────────────────
    val cargando: Boolean = false,
    val registroExitoso: Boolean = false
)
