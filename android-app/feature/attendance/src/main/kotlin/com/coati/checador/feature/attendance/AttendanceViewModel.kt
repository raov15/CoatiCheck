package com.coati.checador.feature.attendance

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coati.checador.core.common.facerecognition.FaceRecognitionEngine
import com.coati.checador.core.database.dao.AppSettingDao
import com.coati.checador.core.database.dao.AttendanceRecordDao
import com.coati.checador.core.database.dao.DeviceDao
import com.coati.checador.core.database.dao.EmployeeDao
import com.coati.checador.core.database.dao.EmployeeFaceProfileDao
import com.coati.checador.core.database.dao.EmployeeWorkSiteDao
import com.coati.checador.core.database.entity.AppSettingEntity
import com.coati.checador.core.database.entity.AttendanceRecordEntity
import com.coati.checador.core.database.entity.EmployeeWorkSiteEntity
import com.coati.checador.core.database.model.EventType
import com.coati.checador.core.database.model.SyncStatus
import com.coati.checador.core.network.CoatiApiServiceFactory
import com.coati.checador.core.sync.SyncManager
import com.coati.checador.feature.location.LocationSnapshot
import com.coati.checador.feature.location.LocationTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AttendanceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val employeeDao: EmployeeDao,
    private val attendanceRecordDao: AttendanceRecordDao,
    private val faceProfileDao: EmployeeFaceProfileDao,
    private val appSettingDao: AppSettingDao,
    private val deviceDao: DeviceDao,
    private val employeeWorkSiteDao: EmployeeWorkSiteDao,
    private val embeddingService: FaceRecognitionEngine,
    private val locationTracker: LocationTracker,
    private val syncManager: SyncManager,
    private val apiServiceFactory: CoatiApiServiceFactory
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
        context: Context
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
                // OBTENER CONFIGURACIÓN SITE / FOREIGN DEL DÍA
                // =================================================

                val mexicoZone =
                    ZoneId.of("America/Mexico_City")

                val localDate =
                    Instant
                        .ofEpochMilli(now)
                        .atZone(mexicoZone)
                        .toLocalDate()

                val weekday =
                    localDate.dayOfWeek.value

                val workDate =
                    localDate.toString()

                // =================================================
                // ACTUALIZAR SITE / FOREIGN DESDE EL SERVIDOR
                // ANTES DE DECIDIR EL TIPO DE JORNADA
                // =================================================

                refreshWorkAssignmentsFromServer()

                val workAssignment =
                    employeeWorkSiteDao.getForDay(
                        employeeId = employee.idLocal,
                        weekday = weekday
                    )

                val isForeign =
                    workAssignment
                        ?.workMode
                        ?.equals(
                            "FOREIGN",
                            ignoreCase = true
                        ) == true

                val attendanceSiteId =
                    if (isForeign) {
                        null
                    } else {
                        workAssignment?.siteId
                            ?: currentDevice?.siteId
                    }

                // =================================================
                // VALIDAR UBICACIÓN EN SEGUNDO PLANO PARA FORÁNEO
                // =================================================

                if (
                    isForeign &&
                    eventType == EventType.CLOCK_IN &&
                    !hasForeignBackgroundLocationPermission()
                ) {

                    throw IllegalStateException(
                        "Para iniciar una jornada foránea debes permitir la ubicación todo el tiempo. " +
                            "Activa ese permiso en Configuración y vuelve a registrar tu entrada."
                    )
                }

                Timber.i(
                    "AttendanceViewModel: configuración de jornada. " +
                        "employee=${employee.idLocal}, " +
                        "weekday=$weekday, " +
                        "workDate=$workDate, " +
                        "mode=${workAssignment?.workMode ?: "SITE"}, " +
                        "siteId=$attendanceSiteId"
                )

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
                // CREAR REGISTRO
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
                            attendanceSiteId,

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
                // JORNADA FORÁNEA
                // =================================================

                if (
                    isForeign &&
                    eventType == EventType.CLOCK_IN
                ) {

                    startForeignTracking(
                        employeeId = employee.idLocal,
                        deviceId = currentDevice?.idRemote,
                        workDate = workDate
                    )
                }

                if (
                    isForeign &&
                    eventType == EventType.CLOCK_OUT
                ) {

                    stopForeignTracking(
                        employeeId = employee.idLocal,
                        deviceId = currentDevice?.idRemote,
                        workDate = workDate
                    )
                }

                // =================================================
                // MENSAJE Y CONTEO DE RETARDOS
                // =================================================

                val successMessage =
                    when (eventType) {

                        EventType.CLOCK_IN -> {

                            if (lateResult.isLate) {

                                
                        val totalLateCount =
    employee.lateCount + 1

val generatedAbsence =
    totalLateCount >= 3

val newLateCount =
    totalLateCount % 3

val newAbsenceCount =
    if (generatedAbsence) {
        employee.absenceCount + 1
    } else {
        employee.absenceCount
    }

employeeDao.update(
    employee.copy(
        lateCount = newLateCount,
        absenceCount = newAbsenceCount,
        updatedAt = now,
        syncStatus = SyncStatus.PENDING
    )
)

if (generatedAbsence) {

    if (isForeign) {
        if (newAbsenceCount == 1) {
            "Jornada foránea iniciada. Llevas 1 ausentismo."
        } else {
            "Jornada foránea iniciada. Llevas $newAbsenceCount ausentismos."
        }
    } else {
        if (newAbsenceCount == 1) {
            "Jornada iniciada. Llevas 1 ausentismo."
        } else {
            "Jornada iniciada. Llevas $newAbsenceCount ausentismos."
        }
    }

} else {

    if (isForeign) {
        if (newLateCount == 1) {
            "Jornada foránea iniciada. Llevas 1 retardo."
        } else {
            "Jornada foránea iniciada. Llevas $newLateCount retardos."
        }
    } else {
        if (newLateCount == 1) {
            "Jornada iniciada. Llevas 1 retardo."
        } else {
            "Jornada iniciada. Llevas $newLateCount retardos."
        }
    }
}

                            } else {

                                if (isForeign) {
                                    "Jornada foránea iniciada."
                                } else {
                                    "Jornada iniciada."
                                }
                            }
                        }

                        EventType.CLOCK_OUT -> {

                            if (isForeign) {
                                "Jornada foránea finalizada."
                            } else {
                                "Jornada finalizada."
                            }
                        }

                        EventType.MEAL_START ->
                            "Salida a comida registrada correctamente"

                        EventType.MEAL_END ->
                            "Regreso de comida registrado correctamente"

                        else ->
                            "Registro guardado correctamente"
                    }

                Timber.i(
                    "AttendanceViewModel: asistencia guardada localmente. " +
                        "idLocal=${record.idLocal}, " +
                        "employeeId=$employeeId, " +
                        "eventType=$eventType, " +
                        "mode=${if (isForeign) "FOREIGN" else "SITE"}, " +
                        "siteId=${record.siteId}, " +
                        "isLate=${record.isLate}, " +
                        "lateMinutes=${record.lateMinutes}"
                )

                // =================================================
                // SINCRONIZACIÓN
                // =================================================

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
    // ACTUALIZAR CONFIGURACIÓN SITE / FOREIGN
    // =========================================================

    private suspend fun refreshWorkAssignmentsFromServer() {

        try {

            val authToken =
                appSettingDao.getValue(
                    AppSettingEntity.KEY_AUTH_TOKEN
                )

            if (authToken.isNullOrBlank()) {

                Timber.w(
                    "AttendanceViewModel: no hay authToken; se usará la configuración local SITE/FOREIGN"
                )

                return
            }

            val apiBaseUrl =
                appSettingDao.getValue(
                    AppSettingEntity.KEY_API_BASE_URL
                )

            val apiService =
                apiServiceFactory.create(
                    apiBaseUrl
                )

            val response =
                apiService.getEmployeeWorkAssignments(
                    bearerToken = "Bearer $authToken"
                )

            if (response.assignments.isEmpty()) {

                Timber.w(
                    "AttendanceViewModel: el servidor devolvió 0 asignaciones; se conserva Room"
                )

                return
            }

            val now =
                System.currentTimeMillis()

            val assignments =
                response.assignments.mapNotNull { remote ->

                    var localEmployee =
                        if (remote.employee_id_local.isNotBlank()) {

                            employeeDao.findById(
                                remote.employee_id_local.trim()
                            )

                        } else {
                            null
                        }

                    if (
                        localEmployee == null &&
                        remote.employee_id_remote.isNotBlank()
                    ) {

                        localEmployee =
                            employeeDao.findByRemoteId(
                                remote.employee_id_remote.trim()
                            )
                    }

                    if (
                        localEmployee == null &&
                        remote.employee_code.isNotBlank()
                    ) {

                        localEmployee =
                            employeeDao.findByCode(
                                remote.employee_code.trim()
                            )
                    }

                    if (localEmployee == null) {

                        Timber.w(
                            "AttendanceViewModel: no se encontró empleado local para " +
                                "asignación remote=${remote.employee_id_remote}, " +
                                "local=${remote.employee_id_local}, " +
                                "code=${remote.employee_code}"
                        )

                        null

                    } else {

                        val normalizedMode =
                            remote.work_mode
                                .trim()
                                .uppercase()

                        if (
                            normalizedMode != "SITE" &&
                            normalizedMode != "FOREIGN"
                        ) {

                            Timber.w(
                                "AttendanceViewModel: work_mode inválido=${remote.work_mode}"
                            )

                            null

                        } else if (
                            remote.weekday !in 1..7
                        ) {

                            Timber.w(
                                "AttendanceViewModel: weekday inválido=${remote.weekday}"
                            )

                            null

                        } else {

                            EmployeeWorkSiteEntity(
                                employeeId =
                                    localEmployee.idLocal,

                                weekday =
                                    remote.weekday,

                                workMode =
                                    normalizedMode,

                                siteId =
                                    if (normalizedMode == "FOREIGN") {
                                        null
                                    } else {
                                        remote.site_id
                                    },

                                siteName =
                                    if (normalizedMode == "FOREIGN") {
                                        null
                                    } else {
                                        remote.site_name
                                    },

                                updatedAt =
                                    now
                            )
                        }
                    }
                }

            /*
             * Solo reemplazamos Room cuando el servidor sí devolvió
             * asignaciones y logramos convertir al menos una.
             * Así evitamos borrar una configuración local válida
             * por un problema temporal de sincronización.
             */
            if (assignments.isNotEmpty()) {

                employeeWorkSiteDao.deleteAll()

                employeeWorkSiteDao.insertAll(
                    assignments
                )

                Timber.i(
                    "AttendanceViewModel: ${assignments.size} asignaciones SITE/FOREIGN actualizadas antes del registro"
                )

            } else {

                Timber.w(
                    "AttendanceViewModel: no se pudo convertir ninguna asignación; se conserva Room"
                )
            }

        } catch (e: Exception) {

            /*
             * Si no hay internet o falla el servidor, no impedimos
             * registrar asistencia. Se utiliza la última configuración
             * SITE/FOREIGN que ya exista en Room.
             */
            Timber.w(
                e,
                "AttendanceViewModel: no se pudo actualizar SITE/FOREIGN; usando configuración local"
            )
        }
    }

    // =========================================================
    // PERMISO UBICACIÓN JORNADA FORÁNEA
    // =========================================================

    private fun hasForeignBackgroundLocationPermission(): Boolean {

        val hasForegroundLocation =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

        if (!hasForegroundLocation) {
            return false
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        } else {

            true
        }
    }

    // =========================================================
    // INICIAR RASTREO FORÁNEO
    // =========================================================

    private fun startForeignTracking(
        employeeId: String,
        deviceId: String?,
        workDate: String
    ) {

        val intent =
            Intent(
                context,
                ForeignLocationService::class.java
            ).apply {

                action =
                    ForeignLocationService.ACTION_START

                putExtra(
                    ForeignLocationService.EXTRA_EMPLOYEE_ID,
                    employeeId
                )

                putExtra(
                    ForeignLocationService.EXTRA_DEVICE_ID,
                    deviceId
                )

                putExtra(
                    ForeignLocationService.EXTRA_WORK_DATE,
                    workDate
                )
            }

        ContextCompat.startForegroundService(
            context,
            intent
        )

        Timber.i(
            "AttendanceViewModel: ForeignLocationService iniciado. " +
                "employee=$employeeId, workDate=$workDate"
        )
    }

    // =========================================================
    // DETENER RASTREO FORÁNEO
    // =========================================================

    private fun stopForeignTracking(
        employeeId: String,
        deviceId: String?,
        workDate: String
    ) {

        val intent =
            Intent(
                context,
                ForeignLocationService::class.java
            ).apply {

                action =
                    ForeignLocationService.ACTION_STOP

                putExtra(
                    ForeignLocationService.EXTRA_EMPLOYEE_ID,
                    employeeId
                )

                putExtra(
                    ForeignLocationService.EXTRA_DEVICE_ID,
                    deviceId
                )

                putExtra(
                    ForeignLocationService.EXTRA_WORK_DATE,
                    workDate
                )
            }

        ContextCompat.startForegroundService(
            context,
            intent
        )

        Timber.i(
            "AttendanceViewModel: solicitado cierre de jornada foránea. " +
                "employee=$employeeId, workDate=$workDate"
        )
    }

    // =========================================================
    // CÁLCULO DE RETARDO
    // =========================================================

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

        val actualLateMinutes =
            differenceMinutes
                .coerceAtLeast(0)

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