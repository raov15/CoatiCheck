package com.coati.checador.feature.attendance

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coati.checador.core.common.facerecognition.FaceRecognitionEngine
import com.coati.checador.core.database.dao.AppSettingDao
import com.coati.checador.core.database.dao.AttendanceRecordDao
import com.coati.checador.core.database.dao.DeviceDao
import com.coati.checador.core.database.dao.EmployeeDao
import com.coati.checador.core.database.dao.EmployeeFaceProfileDao
import com.coati.checador.core.database.entity.AppSettingEntity
import com.coati.checador.core.database.entity.AttendanceRecordEntity
import com.coati.checador.core.database.model.EventType
import com.coati.checador.core.database.model.SyncStatus
import com.coati.checador.core.sync.SyncManager
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AttendanceViewModel @Inject constructor(
    private val employeeDao: EmployeeDao,
    private val attendanceRecordDao: AttendanceRecordDao,
    private val faceProfileDao: EmployeeFaceProfileDao,
    private val appSettingDao: AppSettingDao,
    private val deviceDao: DeviceDao,
    private val embeddingService: FaceRecognitionEngine,
    private val locationTracker: LocationTracker,
    private val syncManager: SyncManager
) : ViewModel() {

    private val _state = MutableStateFlow(AttendanceUiState())
    val state: StateFlow<AttendanceUiState> = _state.asStateFlow()

    init {
        loadEmployees()
        observeRecentRecords()
        observeCompanyBranding()
    }

    // =========================================================
    // EMPRESA
    // =========================================================

    private fun observeCompanyBranding() {

        viewModelScope.launch {
            appSettingDao
                .observeValue(AppSettingEntity.KEY_COMPANY_NAME)
                .collect { companyName ->
                    _state.update {
                        it.copy(companyName = companyName.orEmpty())
                    }
                }
        }

        viewModelScope.launch {
            appSettingDao
                .observeValue(AppSettingEntity.KEY_COMPANY_LOGO_URL)
                .collect { logoUrl ->
                    _state.update {
                        it.copy(companyLogoUrl = logoUrl)
                    }
                }
        }
    }

    // =========================================================
    // REGISTROS RECIENTES
    // =========================================================

    private fun observeRecentRecords() {

        viewModelScope.launch {
            attendanceRecordDao
                .observeRecent(50)
                .collect { records ->
                    _state.update {
                        it.copy(recentRecords = records)
                    }
                }
        }
    }

    // =========================================================
    // EMPLEADOS
    // =========================================================

    fun loadEmployees() {

        viewModelScope.launch {

            _state.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null
                )
            }

            runCatching {
                employeeDao
                    .getAllActive()
                    .map { entity ->
                        AttendanceEmployee(
                            id = entity.idLocal,
                            code = entity.employeeCode,
                            department = entity.department,
                            fullName = entity.fullName,
                            workStartTime = entity.workStartTime,
                            workEndTime = entity.workEndTime,
                            lateToleranceMinutes = entity.lateToleranceMinutes,
                            lateCount = entity.lateCount,
                            absenceCount = entity.absenceCount,
                            createdAt = entity.createdAt
                        )
                    }
            }.onSuccess { employees ->

                _state.update { current ->
                    current.copy(
                        isLoading = false,
                        employees = employees,
                        selectedEmployeeId = current.selectedEmployeeId
                    )
                }

                Timber.d(
                    "AttendanceViewModel: empleados locales actualizados: ${employees.size}"
                )

            }.onFailure { error ->

                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage =
                            error.message
                                ?: "No se pudieron cargar los empleados"
                    )
                }
            }
        }
    }

    fun selectEmployee(employeeId: String) {

        _state.update {
            it.copy(
                selectedEmployeeId = employeeId,
                successMessage = null,
                errorMessage = null
            )
        }
    }

    fun selectEvent(label: String) {

        _state.update {
            it.copy(
                selectedEventLabel = label,
                successMessage = null,
                errorMessage = null
            )
        }
    }

    // =========================================================
    // BORRAR EMPLEADO LOCAL
    // =========================================================

    fun deleteEmployee(
        employeeId: String,
        context: android.content.Context
    ) {

        viewModelScope.launch {

            runCatching {

                employeeDao.deleteById(employeeId)

                val file =
                    File(
                        File(
                            context.filesDir,
                            "employee_photos"
                        ),
                        "$employeeId.jpg"
                    )

                if (file.exists()) {
                    file.delete()
                }

            }.onSuccess {

                loadEmployees()

            }.onFailure { error ->

                _state.update {
                    it.copy(
                        errorMessage =
                            "No se pudo borrar: ${error.message}"
                    )
                }
            }
        }
    }

    // =========================================================
    // PERSONA NO RECONOCIDA
    // =========================================================

    fun updateUnrecognizedInfo(
        name: String,
        position: String,
        number: String
    ) {

        _state.update {
            it.copy(
                unrecognizedName = name,
                unrecognizedPosition = position,
                unrecognizedEmployeeNumber = number
            )
        }
    }

    // =========================================================
    // RECONOCIMIENTO FACIAL
    // =========================================================

    fun recognizeFace(bitmap: Bitmap) {

        viewModelScope.launch {

            _state.update {
                it.copy(
                    isRecognizing = true,
                    selectedEmployeeId = null,
                    recognitionMessage = "Validando presencia...",
                    faceConfidence = null,
                    successMessage = null,
                    errorMessage = null
                )
            }

            /*
             * MODO TEMPORAL:
             *
             * AttendanceFaceCamera llama a recognizeFace() después
             * de que la prueba de vida termina.
             *
             * Se conserva el mismo comportamiento de tu proyecto:
             * se asigna el único empleado activo de este teléfono.
             *
             * faceProfileDao, embeddingService y bitmap permanecen
             * disponibles para reactivar posteriormente el matching facial.
             */
            runCatching {

                val empleados =
                    employeeDao.getAllActive()

                val employee =
                    empleados.firstOrNull()

                if (employee == null) {

                    _state.update {
                        it.copy(
                            isRecognizing = false,
                            selectedEmployeeId = null,
                            faceConfidence = null,
                            recognitionMessage =
                                "No hay empleado registrado en este dispositivo."
                        )
                    }

                    return@launch
                }

                Timber.i(
                    "AttendanceViewModel: prueba de vida superada. " +
                        "Empleado local=${employee.fullName}, id=${employee.idLocal}"
                )

                _state.update {
                    it.copy(
                        isRecognizing = false,
                        selectedEmployeeId = employee.idLocal,
                        faceConfidence = 1f,
                        recognitionMessage =
                            "Reconocido: ${employee.fullName}"
                    )
                }

            }.onFailure { error ->

                Timber.e(
                    error,
                    "AttendanceViewModel: error validando presencia"
                )

                _state.update {
                    it.copy(
                        isRecognizing = false,
                        selectedEmployeeId = null,
                        faceConfidence = null,
                        recognitionMessage =
                            "Error al validar presencia: ${error.message}"
                    )
                }
            }
        }
    }

    // =========================================================
    // GUARDAR ASISTENCIA
    // =========================================================

    fun saveAttendance() {

        if (_state.value.isSaving) {
            return
        }

        val current = _state.value
        val employeeId = current.selectedEmployeeId

        val recognized =
            current.recognitionMessage
                ?.startsWith("Reconocido") == true

        if (
            employeeId == null ||
            !recognized ||
            current.faceConfidence == null
        ) {

            _state.update {
                it.copy(
                    errorMessage =
                        "Debes completar la prueba de vida y ser reconocido para registrar asistencia",
                    successMessage = null
                )
            }

            return
        }

        _state.update {
            it.copy(
                isSaving = true,
                successMessage = null,
                errorMessage = null
            )
        }

        viewModelScope.launch {

            runCatching {

                val now =
                    System.currentTimeMillis()

                val employee =
                    employeeDao.findById(employeeId)
                        ?: throw IllegalStateException(
                            "No se encontró el empleado registrado"
                        )

                val snapshot =
                    locationTracker
                        .getBestEffortLocation()

                val currentDevice =
                    deviceDao.getCurrent()

                val eventType =
                    current
                        .selectedEventLabel
                        .toEventType()

                // =================================================
                // CALCULAR RETARDO
                // =================================================

                val lateResult =
                    if (eventType == EventType.CLOCK_IN) {

                        calculateLateness(
                            currentTimeMillis = now,
                            workStartTime = employee.workStartTime,
                            toleranceMinutes = employee.lateToleranceMinutes
                        )

                    } else {

                        LateResult(
                            isLate = false,
                            lateMinutes = 0
                        )
                    }

                // =================================================
                // CREAR REGISTRO DE ASISTENCIA
                // =================================================

                val record =
                    AttendanceRecordEntity(
                        idLocal =
                            UUID.randomUUID().toString(),

                        employeeId =
                            employeeId,

                        eventType =
                            eventType,

                        occurredAt =
                            now,

                        isLate =
                            lateResult.isLate,

                        lateMinutes =
                            lateResult.lateMinutes,

                        latitude =
                            snapshot?.latitude,

                        longitude =
                            snapshot?.longitude,

                        accuracyM =
                            snapshot?.accuracyMeters,

                        altitudeM =
                            snapshot?.altitudeMeters,

                        faceConfidence =
                            current.faceConfidence,

                        deviceId =
                            currentDevice?.idRemote,

                        siteId =
                            currentDevice?.siteId,

                        syncStatus =
                            SyncStatus.PENDING,

                        syncAttempts =
                            0,

                        lastError =
                            null,

                        createdAt =
                            now
                    )

                attendanceRecordDao.insert(record)

                // =================================================
                // ACTUALIZAR CONTEO DE RETARDOS
                // =================================================

                var successMessage =
                    when (eventType) {

                        EventType.CLOCK_IN ->
                            "Entrada registrada correctamente"

                        EventType.CLOCK_OUT ->
                            "Salida registrada correctamente"

                        EventType.MEAL_START ->
                            "Salida a comida registrada correctamente"

                        EventType.MEAL_END ->
                            "Regreso de comida registrado correctamente"

                        else ->
                            "Registro guardado correctamente"
                    }

                if (
                    eventType == EventType.CLOCK_IN &&
                    lateResult.isLate
                ) {

                    val newLateCount =
                        employee.lateCount + 1

                    /*
                     * Cuando llega al tercer retardo:
                     *
                     * lateCount vuelve a 0 y absenceCount aumenta 1.
                     *
                     * Los retardos históricos NO se pierden porque quedan
                     * almacenados individualmente en attendance_records
                     * con is_late = true.
                     */
                    if (newLateCount >= 3) {

                        employeeDao.update(
                            employee.copy(
                                lateCount = 0,
                                absenceCount =
                                    employee.absenceCount + 1,
                                updatedAt = now,
                                syncStatus = SyncStatus.PENDING
                            )
                        )

                        successMessage =
                            "Registro guardado con retardo. " +
                                "Llegaste ${lateResult.lateMinutes} minutos después de tu hora de entrada. " +
                                "Este es tu tercer retardo y equivale a 1 ausentismo."

                    } else {

                        employeeDao.update(
                            employee.copy(
                                lateCount = newLateCount,
                                updatedAt = now,
                                syncStatus = SyncStatus.PENDING
                            )
                        )

                        val faltantes =
                            3 - newLateCount

                        successMessage =
                            "Registro guardado con retardo. " +
                                "Llegaste ${lateResult.lateMinutes} minutos después de tu hora de entrada. " +
                                "Llevas $newLateCount de 3 retardos. " +
                                "Te ${if (faltantes == 1) "falta" else "faltan"} $faltantes " +
                                "${if (faltantes == 1) "retardo" else "retardos"} para generar 1 ausentismo."
                    }
                }

                Timber.i(
                    "AttendanceViewModel: asistencia guardada localmente. " +
                        "idLocal=${record.idLocal}, " +
                        "employeeId=$employeeId, " +
                        "eventType=$eventType, " +
                        "isLate=${record.isLate}, " +
                        "lateMinutes=${record.lateMinutes}"
                )

                // Solicitar sincronización inmediata.
                try {
                    syncManager.syncNow()
                } catch (e: Exception) {
                    Timber.w(
                        e,
                        "AttendanceViewModel: registro guardado pero no se pudo solicitar sync inmediata"
                    )
                }

                SavedAttendanceResult(
                    location = snapshot,
                    message = successMessage,
                    isLate = lateResult.isLate,
                    lateMinutes = lateResult.lateMinutes
                )

            }.onSuccess { result ->

                _state.update {
                    it.copy(
                        isSaving = false,
                        currentLocation = result.location,

                        selectedEmployeeId = null,
                        faceConfidence = null,
                        recognitionMessage = null,

                        unrecognizedName = "",
                        unrecognizedPosition = "",
                        unrecognizedEmployeeNumber = "",

                        successMessage = result.message,
                        errorMessage = null
                    )
                }

                /*
                 * Recargamos los empleados para que la UI reciba
                 * lateCount y absenceCount actualizados.
                 */
                loadEmployees()

            }.onFailure { error ->

                Timber.e(
                    error,
                    "AttendanceViewModel: error guardando asistencia"
                )

                _state.update {
                    it.copy(
                        isSaving = false,
                        errorMessage =
                            error.message
                                ?: "No se pudo guardar la asistencia",
                        successMessage = null
                    )
                }
            }
        }
    }

    // =========================================================
    // CÁLCULO DE RETARDO
    // =========================================================

    /**
     * Ejemplo:
     *
     * workStartTime = "08:00"
     * toleranceMinutes = 10
     *
     * 08:00 -> normal
     * 08:10 -> normal
     * 08:11 -> retardo
     *
     * lateMinutes representa la diferencia REAL contra la hora
     * oficial de entrada. Por eso 08:11 guarda 11 minutos.
     */
    private fun calculateLateness(
        currentTimeMillis: Long,
        workStartTime: String,
        toleranceMinutes: Int
    ): LateResult {

        val normalizedStartTime =
            workStartTime
                .trim()
                .ifBlank { "08:00" }

        val parts =
            normalizedStartTime
                .split(":")

        val startHour =
            parts
                .getOrNull(0)
                ?.toIntOrNull()
                ?.coerceIn(0, 23)
                ?: 8

        val startMinute =
            parts
                .getOrNull(1)
                ?.toIntOrNull()
                ?.coerceIn(0, 59)
                ?: 0

        val nowCalendar =
            Calendar.getInstance().apply {
                timeInMillis = currentTimeMillis
            }

        val startCalendar =
            Calendar.getInstance().apply {
                timeInMillis = currentTimeMillis
                set(Calendar.HOUR_OF_DAY, startHour)
                set(Calendar.MINUTE, startMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

        val differenceMillis =
            currentTimeMillis -
                startCalendar.timeInMillis

        val differenceMinutes =
            (differenceMillis / 60_000L)
                .toInt()

        /*
         * Si marca antes de su hora, nunca existen minutos negativos.
         */
        val actualLateMinutes =
            differenceMinutes
                .coerceAtLeast(0)

        /*
         * Con tolerancia 10:
         *
         * 0..10  = normal
         * 11+    = retardo
         */
        val isLate =
            actualLateMinutes >
                toleranceMinutes.coerceAtLeast(0)

        Timber.d(
            "AttendanceViewModel: evaluación de entrada. " +
                "horario=$normalizedStartTime, " +
                "horaMarca=${formatTime(currentTimeMillis)}, " +
                "tolerancia=$toleranceMinutes, " +
                "minutos=$actualLateMinutes, " +
                "retardo=$isLate"
        )

        return LateResult(
            isLate = isLate,
            lateMinutes =
                if (isLate) actualLateMinutes else 0
        )
    }

    private fun formatTime(
        timeMillis: Long
    ): String {

        return SimpleDateFormat(
            "HH:mm",
            Locale.getDefault()
        ).format(timeMillis)
    }

    fun clearMessages() {

        _state.update {
            it.copy(
                successMessage = null,
                errorMessage = null
            )
        }
    }
}

// =============================================================
// RESULTADOS INTERNOS
// =============================================================

private data class LateResult(
    val isLate: Boolean,
    val lateMinutes: Int
)

private data class SavedAttendanceResult(
    val location: LocationSnapshot?,
    val message: String,
    val isLate: Boolean,
    val lateMinutes: Int
)

// =============================================================
// ESTADO UI
// =============================================================

data class AttendanceUiState(

    val isLoading: Boolean = false,

    val isSaving: Boolean = false,

    val isRecognizing: Boolean = false,

    val employees:
        List<AttendanceEmployee> =
        emptyList(),

    val selectedEmployeeId:
        String? =
        null,

    val selectedEventLabel:
        String =
        "Entró a trabajar",

    val currentLocation:
        LocationSnapshot? =
        null,

    val faceConfidence:
        Float? =
        null,

    val recognitionMessage:
        String? =
        null,

    val successMessage:
        String? =
        null,

    val errorMessage:
        String? =
        null,

    val recentRecords:
        List<AttendanceRecordEntity> =
        emptyList(),

    val unrecognizedName:
        String =
        "",

    val unrecognizedPosition:
        String =
        "",

    val unrecognizedEmployeeNumber:
        String =
        "",

    val companyName:
        String =
        "",

    val companyLogoUrl:
        String? =
        null
)

// =============================================================
// EMPLEADO PARA UI
// =============================================================

data class AttendanceEmployee(

    val id: String,

    val code: String,

    val department: String,

    val fullName: String,

    val workStartTime: String = "08:00",

    val workEndTime: String = "17:00",

    val lateToleranceMinutes: Int = 10,

    val lateCount: Int = 0,

    val absenceCount: Int = 0,

    val createdAt: Long = 0L
)

// =============================================================
// EVENTOS
// =============================================================

private fun String.toEventType():
    String =
    when (this) {

        "Entró a trabajar" ->
            EventType.CLOCK_IN

        "Salió del trabajo" ->
            EventType.CLOCK_OUT

        "Salió a comer" ->
            EventType.MEAL_START

        "Regresó de comer" ->
            EventType.MEAL_END

        else ->
            EventType.CLOCK_IN
    }