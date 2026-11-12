package com.coati.checador.shared.app

import com.coati.checador.shared.common.Result
import com.coati.checador.shared.data.db.CoatiCheckDatabase
import com.coati.checador.shared.data.db.DatabaseDriverFactory
import com.coati.checador.shared.data.network.ApiBaseUrl
import com.coati.checador.shared.data.network.CoatiApi
import com.coati.checador.shared.data.repository.DeviceAuthRepositoryImpl
import com.coati.checador.shared.data.repository.EmployeeRepositoryImpl
import com.coati.checador.shared.data.repository.FaceProfileRepositoryImpl
import com.coati.checador.shared.data.repository.generateEmployeeLocalId
import com.coati.checador.shared.domain.facecamera.FaceCapture
import com.coati.checador.shared.domain.facecamera.createFaceDetector
import com.coati.checador.shared.domain.facerecognition.FaceRecognitionEngine
import com.coati.checador.shared.domain.facerecognition.FaceRecognitionEngineRegistry
import com.coati.checador.shared.domain.facerecognition.createFaceRecognitionEngine
import com.coati.checador.shared.domain.location.createLocationTracker
import com.coati.checador.shared.domain.model.Device
import com.coati.checador.shared.domain.model.Employee
import com.coati.checador.shared.domain.model.enums.SyncStatus
import com.coati.checador.shared.domain.sync.SyncOrchestrator
import com.coati.checador.shared.domain.sync.TokenStore
import com.coati.checador.shared.domain.sync.createBackgroundSyncScheduler
import com.coati.checador.shared.domain.usecase.deviceauth.RegisterDeviceUseCase
import com.coati.checador.shared.domain.usecase.deviceauth.VerifyDeviceTokenUseCase
import com.coati.checador.shared.domain.usecase.employee.RegisterEmployeeUseCase
import com.coati.checador.shared.domain.usecase.facerecognition.RecognizeFaceUseCase
import com.coati.checador.shared.domain.usecase.facerecognition.RecognizeFaceUseCase.MatchResult
import com.coati.checador.shared.security.AdminPinManager
import com.coati.checador.shared.security.EmbeddingCipher
import com.coati.checador.shared.security.PlatformContext
import com.coati.checador.shared.security.SecureStorage
import kotlin.random.Random
import kotlinx.datetime.Clock

/**
 * Punto único de entrada para la app iOS (SwiftUI).
 *
 * La app Swift construye esta clase al arrancar y consume sus métodos;
 * internamente cablea BD, red, seguridad y casos de uso. Las funciones
 * suspend se exportan a Swift como completion handlers.
 */
@Suppress("unused", "TooManyFunctions")
class SharedApp(
    databaseDriverFactory: DatabaseDriverFactory,
    private val platformContext: PlatformContext
) {

    private val db: CoatiCheckDatabase = CoatiCheckDatabase(databaseDriverFactory.createDriver())

    private val secureStorage = SecureStorage(platformContext)

    /** Token del dispositivo cifrado en Keychain/EncryptedSharedPreferences. */
    private val tokenStore: TokenStore = object : TokenStore {
        override fun getBearer(): String? = secureStorage.getString(KEY_AUTH_TOKEN)
        override fun setBearer(rawToken: String) =
            secureStorage.putString(KEY_AUTH_TOKEN, rawToken)
    }

    private val api: CoatiApi = CoatiApi(
        baseUrl = ApiBaseUrl.normalize(getServerUrl(), allowInsecure = true)
            ?: DEFAULT_API_BASE
    )

    private val engine: FaceRecognitionEngine =
        FaceRecognitionEngineRegistry.externalEngine
            ?: createFaceRecognitionEngine(platformContext)
    private val crypto = EmbeddingCipher(secureStorage)

    private val deviceRepository = DeviceAuthRepositoryImpl(db, api, tokenStore)
    private val employeeRepository = EmployeeRepositoryImpl(db, engine, crypto)
    private val faceProfileRepository = FaceProfileRepositoryImpl(db)

    private val orchestrator = SyncOrchestrator(api, db, tokenStore)
    private val scheduler = createBackgroundSyncScheduler(platformContext, orchestrator)

    private val recognizeFaceUseCase =
        RecognizeFaceUseCase(engine, crypto, faceProfileRepository)
    private val registerEmployeeUseCase = RegisterEmployeeUseCase(employeeRepository, scheduler)
    private val registerDeviceUseCase = RegisterDeviceUseCase(deviceRepository)
    private val verifyTokenUseCase = VerifyDeviceTokenUseCase(deviceRepository)

    private val faceCapture = FaceCapture(createFaceDetector())
    private val locationTracker = createLocationTracker(platformContext)
    private val adminPin = AdminPinManager(secureStorage)

    // =========================================================
    // DISPOSITIVO
    // =========================================================

    /** true si el dispositivo ya está registrado contra el backend. */
    fun isDeviceRegistered(): Boolean =
        db.devices.getCurrent().executeAsOneOrNull()?.id_remote != null &&
            tokenStore.getBearer() != null

    suspend fun registerDevice(
        deviceName: String,
        deviceFingerprint: String
    ): Result<Device> =
        registerDeviceUseCase(deviceName, deviceFingerprint, getServerUrl())

    suspend fun enrollDevice(
        deviceName: String,
        deviceFingerprint: String,
        enrollmentCode: String
    ): Result<Device> =
        registerDeviceUseCase.enroll(
            deviceName, deviceFingerprint, enrollmentCode, getServerUrl()
        )

    suspend fun verifyDeviceToken(): Boolean =
        when (verifyTokenUseCase(getServerUrl())) {
            is Result.Success -> true
            else -> false
        }

    // =========================================================
    // ASISTENCIA
    // =========================================================

    suspend fun reconocerParaAsistencia(frame: ByteArray): AttendanceRegistration {
        if (!engine.isRealModelAvailable) return AttendanceRegistration(
            status = AttendanceStatus.ERROR, employeeName = null,
            message = "El modelo facial real no está disponible; no se puede registrar asistencia."
        )
        val capture = faceCapture.captureLargestFace(frame)
        val match = when (capture) {
            is FaceCapture.Result.Captured -> recognizeFaceUseCase(capture.faceImage)
            is FaceCapture.Result.NoFace -> return AttendanceRegistration(
                status = AttendanceStatus.NO_FACE, employeeName = null
            )
            is FaceCapture.Result.Failure -> return AttendanceRegistration(
                status = AttendanceStatus.ERROR, employeeName = null, message = capture.message
            )
        }
        val recognized = when (match) {
            is MatchResult.Recognized -> match
            is MatchResult.NoMatch -> return AttendanceRegistration(
                status = AttendanceStatus.NOT_RECOGNIZED, employeeName = null
            )
            is MatchResult.Failure -> return AttendanceRegistration(
                status = AttendanceStatus.ERROR, employeeName = null, message = match.message
            )
        }

        return AttendanceRegistration(
            status = AttendanceStatus.OK,
            employeeName = recognized.employeeName,
            confidence = recognized.confidence,
            employeeId = recognized.employeeId
        )
    }

    fun getAllowedAttendanceEvents(employeeId: String): List<String> =
        allowedAttendanceEvents(employeeId, Clock.System.now().toEpochMilliseconds())

    private fun allowedAttendanceEvents(employeeId: String, timestamp: Long): List<String> {
        val bounds = AttendanceSequence.dayBounds(timestamp)
        val events = db.attendance.getByEmployeeBetween(employeeId, bounds.first, bounds.last)
            .executeAsList().asReversed().map { it.event_type }
        return AttendanceSequence.allowedAfter(events)
    }

    suspend fun registrarAsistencia(
        frame: ByteArray,
        eventType: String,
        expectedEmployeeId: String
    ): AttendanceRegistration {
        val recognized = reconocerParaAsistencia(frame)
        if (recognized.status != AttendanceStatus.OK) return recognized
        if (recognized.employeeId != expectedEmployeeId) return AttendanceRegistration(
            status = AttendanceStatus.ERROR, employeeName = null,
            message = "La identidad reconocida cambió; vuelve a capturar el rostro."
        )

        val location = try {
            locationTracker.getBestEffortLocation()
        } catch (_: Exception) {
            null
        }

        val now = Clock.System.now().toEpochMilliseconds()
        var registered = false
        db.transaction {
            if (eventType in allowedAttendanceEvents(expectedEmployeeId, now)) {
                db.attendance.insertOrReplace(
                    id_local = "att-$now-${Random.nextInt(1000, 9999)}",
                    id_remote = null,
                    employee_id = expectedEmployeeId,
                    event_type = eventType,
                    occurred_at = now,
                    is_late = false,
                    late_minutes = 0L,
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                    accuracy_m = location?.accuracyMeters?.toDouble(),
                    altitude_m = location?.altitudeMeters,
                    face_confidence = recognized.confidence?.toDouble(),
                    device_id = db.devices.getCurrent().executeAsOneOrNull()?.id_local,
                    site_id = null,
                    sync_status = SyncStatus.PENDING,
                    sync_attempts = 0L,
                    last_error = null,
                    created_at = now
                )
                registered = true
            }
        }
        if (!registered) return AttendanceRegistration(
            status = AttendanceStatus.ERROR, employeeName = recognized.employeeName,
            message = "El movimiento no corresponde a la jornada actual. Vuelve a capturar el rostro."
        )
        scheduler.syncNow()
        return AttendanceRegistration(
            status = AttendanceStatus.OK,
            employeeName = recognized.employeeName,
            confidence = recognized.confidence,
            latitude = location?.latitude,
            longitude = location?.longitude,
            employeeId = expectedEmployeeId
        )
    }

    // =========================================================
    // EMPLEADOS / ENROLAMIENTO
    // =========================================================

    suspend fun enrollEmployee(
        firstName: String,
        lastNamePaternal: String,
        lastNameMaternal: String,
        rfc: String,
        curp: String,
        nss: String,
        department: String,
        faceImage: ByteArray
    ): Result<Unit> {
        if (!engine.isRealModelAvailable) return Result.Error(
            IllegalStateException("El modelo facial real no está disponible"),
            "El modelo facial real no está disponible; no se puede enrolar al empleado."
        )
        val croppedFace = when (val capture = faceCapture.captureLargestFace(faceImage)) {
            is FaceCapture.Result.Captured -> capture.faceImage
            is FaceCapture.Result.NoFace -> return Result.Error(
                IllegalArgumentException("No se detectó ningún rostro"),
                "No se detectó ningún rostro. Vuelve a capturar la foto."
            )
            is FaceCapture.Result.Failure -> return Result.Error(
                IllegalStateException(capture.message), capture.message
            )
        }
        val now = Clock.System.now().toEpochMilliseconds()
        val employee = Employee(
            idLocal = generateEmployeeLocalId(),
            employeeCode = "AUTO",
            firstName = firstName.trim(),
            lastNamePaternal = lastNamePaternal.trim(),
            lastNameMaternal = lastNameMaternal.trim(),
            fullName = "$firstName $lastNamePaternal $lastNameMaternal".trim(),
            rfc = rfc.trim(),
            curp = curp.trim(),
            nss = nss.trim(),
            department = department.trim(),
            createdAt = now,
            updatedAt = now,
            syncStatus = SyncStatus.PENDING
        )
        return registerEmployeeUseCase(employee, croppedFace)
    }

    suspend fun recoverFaceProfile(employeeId: String, pin: String, faceImage: ByteArray): Result<Unit> {
        if (!adminPin.hasPin()) return Result.Error(
            IllegalStateException("PIN no configurado"),
            "Configura primero el PIN de administrador en Configuración."
        )
        if (!adminPin.verifyPin(pin)) return Result.Error(
            IllegalArgumentException("PIN incorrecto"), "El PIN de administrador es incorrecto."
        )
        if (!engine.isRealModelAvailable) return Result.Error(
            IllegalStateException("El modelo facial real no está disponible"),
            "El modelo facial real no está disponible."
        )
        val croppedFace = when (val capture = faceCapture.captureLargestFace(faceImage)) {
            is FaceCapture.Result.Captured -> capture.faceImage
            is FaceCapture.Result.NoFace -> return Result.Error(
                IllegalArgumentException("No se detectó ningún rostro"),
                "No se detectó ningún rostro. Vuelve a capturar la foto."
            )
            is FaceCapture.Result.Failure -> return Result.Error(
                IllegalStateException(capture.message), capture.message
            )
        }
        return try {
            employeeRepository.recoverInvalidFaceProfile(
                employeeId, croppedFace, Clock.System.now().toEpochMilliseconds()
            )
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e, e.message ?: "No se pudo recuperar el perfil facial.")
        }
    }

    /** Cargos permitidos durante el enrolamiento (igual que Android). */
    fun allowedPositions(): List<String> = RegisterEmployeeUseCase.ALLOWED_POSITIONS

    /** Empleados activos para la lista de asistencia. */
    fun getActiveEmployees(): List<EmployeeInfo> =
        db.employees.getAllActive().executeAsList().map {
            EmployeeInfo(
                idLocal = it.id_local,
                fullName = it.full_name,
                department = it.department
            )
        }

    // =========================================================
    // HISTORIAL
    // =========================================================

    /** Registros recientes de asistencia (vista de historial). */
    fun getRecentAttendance(limit: Long = 50): List<AttendanceRecordInfo> =
        db.attendance.getRecent(limit).executeAsList().map {
            AttendanceRecordInfo(
                idLocal = it.id_local,
                employeeId = it.employee_id,
                eventType = it.event_type,
                occurredAt = it.occurred_at,
                syncStatus = it.sync_status,
                latitude = it.latitude,
                longitude = it.longitude
            )
        }

    // =========================================================
    // CONFIGURACIÓN
    // =========================================================

    fun getServerUrl(): String =
        secureStorage.getString(KEY_API_BASE) ?: DEFAULT_API_BASE

    fun setServerUrl(url: String) {
        val trimmed = url.trim()
        secureStorage.putString(KEY_API_BASE, trimmed)
        api.updateBaseUrl(trimmed)
    }

    fun verifyAdminPin(pin: String): Boolean = adminPin.verifyPin(pin)

    fun setAdminPin(pin: String) = adminPin.setPin(pin)

    fun hasAdminPin(): Boolean = adminPin.hasPin()

    // =========================================================
    // SINCRONIZACIÓN
    // =========================================================

    /** Dispara la sincronización inmediata (4 pasos). */
    fun syncNow() = scheduler.syncNow()

    /** Sincroniza y devuelve el resultado real para mostrarlo en la UI. */
    suspend fun syncAwait(): SyncOperationResult {
        val report = try {
            orchestrator.syncAll()
        } catch (e: Exception) {
            return SyncOperationResult(false, e.message ?: "No se pudo sincronizar")
        }
        val errors = listOf(
            "Empleados" to report.employees,
            "Asignaciones" to report.workAssignments,
            "Asistencias" to report.attendance,
            "Ubicaciones" to report.foreignLocations
        ).mapNotNull { (step, result) ->
            when (result) {
                is SyncOrchestrator.StepResult.Failed -> "$step: ${result.message}"
                is SyncOrchestrator.StepResult.OkWithErrors -> "$step: ${result.lastError}"
                else -> null
            }
        }
        return SyncOperationResult(
            success = errors.isEmpty(),
            message = if (errors.isEmpty()) "Sincronización completada" else errors.joinToString("\n")
        )
    }

    /** Registros pendientes de sincronizar (badge UI). */
    fun countPendingToSync(): Long =
        db.attendance.countPending().executeAsOne() +
            db.foreignLocations.countPending().executeAsOne() +
            db.employees.countPendingToSync().executeAsOne()

    // =========================================================
    // MOTOR FACIAL (registro desde Swift/CoreML)
    // =========================================================

    /**
     * La app iOS registra aquí su motor CoreML+Vision real al arrancar.
     * Si no se registra, se usa el motor simulado determinista.
     */
    fun setExternalFaceEngine(engine: FaceRecognitionEngine) {
        FaceRecognitionEngineRegistry.externalEngine = engine
    }

    companion object {
        /** Sin fallback HTTP hardcodeado: la app debe configurar la URL real. */
        const val DEFAULT_API_BASE = "https://configura-url-del-servidor"

        private const val KEY_AUTH_TOKEN = "device_auth_token"
        private const val KEY_API_BASE = "api_base_url"
    }
}

/** Resultado de una sincronización esperada por la interfaz. */
class SyncOperationResult(
    val success: Boolean,
    val message: String
)

/** Estados del registro de asistencia expuestos a la UI. */
enum class AttendanceStatus {
    OK, NO_FACE, NOT_RECOGNIZED, ERROR
}

/** Resultado del registro de asistencia para la UI. */
class AttendanceRegistration(
    val status: AttendanceStatus,
    val employeeName: String?,
    val confidence: Float? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val message: String? = null,
    val employeeId: String? = null
)

/** Empleado activo (vista de lista). */
class EmployeeInfo(
    val idLocal: String,
    val fullName: String,
    val department: String
)

/** Registro de asistencia (vista de historial). */
class AttendanceRecordInfo(
    val idLocal: String,
    val employeeId: String,
    val eventType: String,
    val occurredAt: Long,
    val syncStatus: String,
    val latitude: Double?,
    val longitude: Double?
)
