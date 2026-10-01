package com.coati.checador.shared.data.db

import app.cash.sqldelight.db.SqlDriver
import com.coati.checador.shared.domain.model.AttendanceRecord
import com.coati.checador.shared.domain.model.Employee

/**
 * Punto de acceso único a la base de datos local.
 *
 * Expone las queries generadas por SQLDelight y mapea las filas
 * hacia los modelos de dominio compartidos.
 */
class CoatiCheckDatabase(driver: SqlDriver) {

    private val db = CoatiDatabase(driver)

    val employees = db.employeesQueries
    val faceProfiles = db.employeeFaceProfilesQueries
    val attendance = db.attendanceRecordsQueries
    val syncQueue = db.syncQueueQueries
    val devices = db.devicesQueries
    val foreignLocations = db.foreignLocationPointsQueries
    val workSites = db.employeeWorkSitesQueries
    val settings = db.appSettingsQueries

    fun transaction(block: () -> Unit) {
        db.transaction {
            block()
        }
    }
}

// =========================================================
// EMPLOYEES
// =========================================================

fun Employees.toDomain(): Employee = Employee(
    idLocal = id_local,
    idRemote = id_remote,
    employeeCode = employee_code,
    firstName = first_name,
    lastNamePaternal = last_name_paternal,
    lastNameMaternal = last_name_maternal,
    fullName = full_name,
    rfc = rfc,
    curp = curp,
    nss = nss,
    department = department,
    workStartTime = work_start_time,
    workEndTime = work_end_time,
    lateToleranceMinutes = late_tolerance_minutes.toInt(),
    lateCount = late_count.toInt(),
    absenceCount = absence_count.toInt(),
    isActive = is_active,
    createdAt = created_at,
    updatedAt = updated_at,
    syncStatus = sync_status
)

fun CoatiCheckDatabase.insertEmployee(employee: Employee) {
    employees.insertOrReplace(
        id_local = employee.idLocal,
        id_remote = employee.idRemote,
        employee_code = employee.employeeCode,
        first_name = employee.firstName,
        last_name_paternal = employee.lastNamePaternal,
        last_name_maternal = employee.lastNameMaternal,
        full_name = employee.fullName,
        rfc = employee.rfc,
        curp = employee.curp,
        nss = employee.nss,
        department = employee.department,
        work_start_time = employee.workStartTime,
        work_end_time = employee.workEndTime,
        late_tolerance_minutes = employee.lateToleranceMinutes.toLong(),
        late_count = employee.lateCount.toLong(),
        absence_count = employee.absenceCount.toLong(),
        is_active = employee.isActive,
        created_at = employee.createdAt,
        updated_at = employee.updatedAt,
        sync_status = employee.syncStatus
    )
}

// =========================================================
// ATTENDANCE
// =========================================================

fun Attendance_records.toDomain(): AttendanceRecord = AttendanceRecord(
    idLocal = id_local,
    idRemote = id_remote,
    employeeId = employee_id,
    eventType = event_type,
    occurredAt = occurred_at,
    isLate = is_late,
    lateMinutes = late_minutes.toInt(),
    latitude = latitude,
    longitude = longitude,
    accuracyM = accuracy_m?.toFloat(),
    altitudeM = altitude_m,
    faceConfidence = face_confidence?.toFloat(),
    deviceId = device_id,
    siteId = site_id,
    syncStatus = sync_status,
    syncAttempts = sync_attempts.toInt(),
    lastError = last_error,
    createdAt = created_at
)

fun CoatiCheckDatabase.insertAttendance(record: AttendanceRecord) {
    attendance.insertOrReplace(
        id_local = record.idLocal,
        id_remote = record.idRemote,
        employee_id = record.employeeId,
        event_type = record.eventType,
        occurred_at = record.occurredAt,
        is_late = record.isLate,
        late_minutes = record.lateMinutes.toLong(),
        latitude = record.latitude,
        longitude = record.longitude,
        accuracy_m = record.accuracyM?.toDouble(),
        altitude_m = record.altitudeM,
        face_confidence = record.faceConfidence?.toDouble(),
        device_id = record.deviceId,
        site_id = record.siteId,
        sync_status = record.syncStatus,
        sync_attempts = record.syncAttempts.toLong(),
        last_error = record.lastError,
        created_at = record.createdAt
    )
}
