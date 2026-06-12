package com.coati.checador.feature.attendance

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coati.checador.core.database.dao.AttendanceRecordDao
import com.coati.checador.core.database.dao.EmployeeDao
import com.coati.checador.core.database.dao.EmployeeFaceProfileDao
import com.coati.checador.core.database.entity.AttendanceRecordEntity
import com.coati.checador.core.database.entity.EmployeeEntity
import com.coati.checador.core.database.model.EventType
import com.coati.checador.core.database.model.SyncStatus
import com.coati.checador.feature.facerecognition.domain.FaceRecognitionEngine
import com.coati.checador.feature.location.LocationSnapshot
import com.coati.checador.feature.location.LocationTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AttendanceViewModel @Inject constructor(
    private val employeeDao: EmployeeDao,
    private val attendanceRecordDao: AttendanceRecordDao,
    private val faceProfileDao: EmployeeFaceProfileDao,
    private val embeddingService: FaceRecognitionEngine,
    private val locationTracker: LocationTracker
) : ViewModel() {

    private val _state = MutableStateFlow(AttendanceUiState())
    val state: StateFlow<AttendanceUiState> = _state.asStateFlow()

    init {
        loadEmployees()
        observeRecentRecords()
    }

    private fun observeRecentRecords() {
        viewModelScope.launch {
            attendanceRecordDao.observeRecent(50).collect { records ->
                _state.update { it.copy(recentRecords = records) }
            }
        }
    }

    fun loadEmployees() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                employeeDao.getAllActive().map { entity ->
                    val parts = entity.employeeCode.split("|")
                    AttendanceEmployee(
                        id = entity.idLocal,
                        code = parts.getOrElse(0) { entity.employeeCode },
                        department = parts.getOrElse(1) { "" },
                        fullName = entity.fullName,
                        createdAt = entity.createdAt
                    )
                }
            }.onSuccess { employees ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        employees = employees,
                        selectedEmployeeId = it.selectedEmployeeId ?: employees.firstOrNull()?.id
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "No se pudieron cargar los empleados"
                    )
                }
            }
        }
    }

    fun selectEmployee(employeeId: String) {
        _state.update { it.copy(selectedEmployeeId = employeeId, successMessage = null, errorMessage = null) }
    }

    fun selectEvent(label: String) {
        _state.update { it.copy(selectedEventLabel = label, successMessage = null, errorMessage = null) }
    }

    fun deleteEmployee(employeeId: String, context: android.content.Context) {
        viewModelScope.launch {
            runCatching {
                employeeDao.deleteById(employeeId)
                // Borrar foto física
                val file = File(File(context.filesDir, "employee_photos"), "$employeeId.jpg")
                if (file.exists()) file.delete()
            }.onSuccess {
                loadEmployees()
            }.onFailure { error ->
                _state.update { it.copy(errorMessage = "No se pudo borrar: ${error.message}") }
            }
        }
    }

    fun updateUnrecognizedInfo(name: String, position: String, number: String) {
        _state.update { 
            it.copy(
                unrecognizedName = name,
                unrecognizedPosition = position,
                unrecognizedEmployeeNumber = number
            )
        }
    }

    /**
     * Recibe un Bitmap capturado por la cámara y ejecuta reconocimiento facial 1:N
     * contra los perfiles almacenados en Room. Si encuentra coincidencia, selecciona
     * automáticamente al empleado en la UI.
     */
    fun recognizeFace(bitmap: Bitmap) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isRecognizing = true,
                    recognitionMessage = "Analizando rostro...",
                    faceConfidence = null,
                    successMessage = null,
                    errorMessage = null
                )
            }

            runCatching {
                val embeddingNuevo = embeddingService.generarEmbedding(bitmap)
                val perfiles = faceProfileDao.getAllActiveForRecognition()

                if (perfiles.isEmpty()) {
                    _state.update {
                        it.copy(
                            isRecognizing = false,
                            recognitionMessage = "Sin perfiles registrados. Registra empleados primero."
                        )
                    }
                    return@launch
                }

                var bestDist = Float.MAX_VALUE
                var bestEmployeeId: String? = null

                for (perfil in perfiles) {
                    try {
                        val stored = embeddingService.descifrarEmbedding(perfil.embeddingBlob)
                        val dist = embeddingService.distanciaCoseno(embeddingNuevo, stored)
                        Timber.v("AttendanceViewModel: dist con ${perfil.employeeId} = $dist")
                        if (dist < bestDist) {
                            bestDist = dist
                            bestEmployeeId = perfil.employeeId
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "AttendanceViewModel: error al descifrar perfil ${perfil.id}")
                    }
                }

                val THRESHOLD = 0.55f   // MobileFaceNet: umbral generoso para condiciones reales
                if (bestDist <= THRESHOLD && bestEmployeeId != null) {
                    val employee = employeeDao.findById(bestEmployeeId)
                    val confidence = ((1f - bestDist / THRESHOLD) * 100f).toInt()
                    Timber.i("AttendanceViewModel: reconocido ${employee?.fullName} (dist=$bestDist, confianza=$confidence%)")
                    _state.update {
                        it.copy(
                            isRecognizing = false,
                            selectedEmployeeId = bestEmployeeId,
                            faceConfidence = 1f - bestDist,
                            recognitionMessage = "Reconocido: ${employee?.fullName ?: "Empleado"} ($confidence%)"
                        )
                    }
                } else {
                    Timber.d("AttendanceViewModel: rostro no reconocido (mejor dist=$bestDist)")
                    _state.update {
                        it.copy(
                            isRecognizing = false,
                            faceConfidence = null,
                            recognitionMessage = "Rostro no reconocido. Selecciona manualmente."
                        )
                    }
                }
            }.onFailure { error ->
                Timber.e(error, "AttendanceViewModel: error en reconocimiento facial")
                _state.update {
                    it.copy(
                        isRecognizing = false,
                        recognitionMessage = "Error al reconocer: ${error.message}"
                    )
                }
            }
        }
    }

    fun saveAttendance() {
        val current = _state.value
        val employeeId = current.selectedEmployeeId
        
        // Validation: must have either a selected employee or manual info
        if (employeeId == null && current.unrecognizedName.isBlank()) {
            _state.update { it.copy(errorMessage = "Selecciona un empleado o ingresa los datos manualmente") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, successMessage = null, errorMessage = null) }
            
            runCatching {
                val now = System.currentTimeMillis()
                val snapshot = locationTracker.getBestEffortLocation()
                val eventType = current.selectedEventLabel.toEventType()
                
                val finalEmployeeId = if (employeeId != null) {
                    employeeId
                } else {
                    // Create new employee for unrecognized person
                    val newId = UUID.randomUUID().toString()
                    val newEmployee = EmployeeEntity(
                        idLocal = newId,
                        employeeCode = "${current.unrecognizedEmployeeNumber}|${current.unrecognizedPosition}",
                        fullName = current.unrecognizedName,
                        isActive = true,
                        createdAt = now,
                        updatedAt = now,
                        syncStatus = SyncStatus.PENDING
                    )
                    employeeDao.insertOrReplace(newEmployee)
                    newId
                }

                val record = AttendanceRecordEntity(
                    idLocal = UUID.randomUUID().toString(),
                    employeeId = finalEmployeeId,
                    eventType = eventType,
                    occurredAt = now,
                    latitude = snapshot?.latitude,
                    longitude = snapshot?.longitude,
                    accuracyM = snapshot?.accuracyMeters,
                    altitudeM = snapshot?.altitudeMeters,
                    faceConfidence = current.faceConfidence,
                    deviceId = null,
                    syncStatus = SyncStatus.PENDING,
                    syncAttempts = 0,
                    lastError = null,
                    createdAt = now
                )
                
                attendanceRecordDao.insert(record)
                snapshot
            }.onSuccess { snapshot ->
                _state.update {
                    it.copy(
                        isSaving = false,
                        currentLocation = snapshot,
                        faceConfidence = null,
                        recognitionMessage = null,
                        unrecognizedName = "",
                        unrecognizedPosition = "",
                        unrecognizedEmployeeNumber = "",
                        successMessage = "Asistencia guardada con éxito",
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = error.message ?: "No se pudo guardar la asistencia",
                        successMessage = null
                    )
                }
            }
        }
    }
}

data class AttendanceUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isRecognizing: Boolean = false,
    val employees: List<AttendanceEmployee> = emptyList(),
    val selectedEmployeeId: String? = null,
    val selectedEventLabel: String = "Entrada",
    val currentLocation: LocationSnapshot? = null,
    val faceConfidence: Float? = null,
    val recognitionMessage: String? = null,
    val successMessage: String? = null,
    val errorMessage: String? = null,
    val recentRecords: List<AttendanceRecordEntity> = emptyList(),
    // New fields for unrecognized employees
    val unrecognizedName: String = "",
    val unrecognizedPosition: String = "",
    val unrecognizedEmployeeNumber: String = ""
)

data class AttendanceEmployee(
    val id: String,
    val code: String,
    val department: String,
    val fullName: String,
    val createdAt: Long = 0L
)

private fun String.toEventType(): String = when (this) {
    "Entrada"        -> EventType.CLOCK_IN
    "Salida"         -> EventType.CLOCK_OUT
    "Entrada Comida" -> EventType.MEAL_START
    "Salida Comida"  -> EventType.MEAL_END
    else             -> EventType.CLOCK_IN
}
