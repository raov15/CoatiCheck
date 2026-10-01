package com.coati.checador.shared.domain.usecase.employee

import com.coati.checador.shared.common.Result
import com.coati.checador.shared.domain.model.Employee
import com.coati.checador.shared.domain.repository.EmployeeRepository
import com.coati.checador.shared.domain.repository.SyncScheduler

/**
 * Caso de uso encargado de registrar al empleado
 * asociado al dispositivo.
 *
 * IMPORTANTE:
 *
 * El trabajador NO captura aquí:
 *
 * - hora de entrada
 * - hora de salida
 * - tolerancia
 * - retardos
 * - ausentismos
 *
 * Esa información pertenece a la configuración laboral
 * asignada posteriormente por el administrador/servidor.
 */
class RegisterEmployeeUseCase(
    private val repository: EmployeeRepository,
    private val syncScheduler: SyncScheduler
) {
    companion object {

        /**
         * Cargos permitidos actualmente
         * durante el enrolamiento.
         */
        val ALLOWED_POSITIONS = listOf(
            "Desarrollador web",
            "Programador",
            "Contador"
        )
    }

    /**
     * Registra un empleado localmente y posteriormente
     * solicita su sincronización con el servidor.
     */
    suspend operator fun invoke(
        employee: Employee,
        faceImage: ByteArray
    ): Result<Unit> {

        // =====================================================
        // 1. VALIDAR QUE EL DISPOSITIVO NO TENGA OTRO EMPLEADO
        // =====================================================

        val activeEmployees = try {
            repository.countActiveEmployees()
        } catch (e: Exception) {
            return Result.Error(
                exception = e,
                message = "No se pudo verificar el dispositivo. Intenta nuevamente."
            )
        }

        if (activeEmployees >= 1) {
            return Result.Error(
                exception = IllegalStateException(
                    "Este dispositivo ya tiene un empleado registrado"
                ),
                message = "Este teléfono ya tiene un empleado registrado. " +
                    "No puedes agregar otro empleado."
            )
        }

        // =====================================================
        // 2. VALIDAR CARGO
        // =====================================================

        val position = employee.department.trim()

        if (position.isBlank()) {
            return Result.Error(
                exception = IllegalArgumentException("Cargo requerido"),
                message = "Selecciona el cargo del empleado."
            )
        }

        if (position !in ALLOWED_POSITIONS) {
            return Result.Error(
                exception = IllegalArgumentException("Cargo no permitido"),
                message = "Selecciona uno de los cargos disponibles."
            )
        }

        // =====================================================
        // 3. VALIDAR CÓDIGO
        // =====================================================
        //
        // AUTO significa que todavía no existe un código
        // definitivo.
        //
        // EMP001, EMP002, etc. se asignarán desde el servidor.
        // =====================================================

        if (employee.employeeCode != "AUTO") {
            val codeExists = try {
                repository.employeeCodeExists(employee.employeeCode)
            } catch (e: Exception) {
                return Result.Error(
                    exception = e,
                    message = "No se pudo verificar el código del empleado."
                )
            }

            if (codeExists) {
                return Result.Error(
                    exception = IllegalArgumentException("Código de empleado ya registrado"),
                    message = "El código '${employee.employeeCode}' ya está asignado."
                )
            }
        }

        // =====================================================
        // 4. VALIDAR DATOS BÁSICOS
        // =====================================================

        if (employee.firstName.isBlank()) {
            return Result.Error(
                exception = IllegalArgumentException("Nombre requerido"),
                message = "El nombre del empleado es requerido."
            )
        }

        if (employee.lastNamePaternal.isBlank()) {
            return Result.Error(
                exception = IllegalArgumentException("Apellido paterno requerido"),
                message = "El apellido paterno es requerido."
            )
        }

        if (employee.rfc.isBlank()) {
            return Result.Error(
                exception = IllegalArgumentException("RFC requerido"),
                message = "El RFC es requerido."
            )
        }

        if (employee.curp.isBlank()) {
            return Result.Error(
                exception = IllegalArgumentException("CURP requerida"),
                message = "La CURP es requerida."
            )
        }

        if (employee.nss.isBlank()) {
            return Result.Error(
                exception = IllegalArgumentException("NSS requerido"),
                message = "El NSS es requerido."
            )
        }

        // =====================================================
        // 5. GUARDAR EMPLEADO LOCALMENTE
        // =====================================================

        return try {
            val success = repository.registerEmployee(employee, faceImage)

            if (!success) {
                return Result.Error(
                    exception = RuntimeException("Error al guardar en la base de datos"),
                    message = "No se pudo guardar el empleado. Intenta nuevamente."
                )
            }

            // =================================================
            // 6. SOLICITAR SINCRONIZACIÓN
            // =================================================
            //
            // Si falla la sincronización:
            //
            // NO se elimina el empleado.
            // NO se considera fallido el registro.
            //
            // El registro permanece PENDING y podrá
            // sincronizarse después.
            // =================================================

            try {
                syncScheduler.syncNow()
            } catch (_: Exception) {
                // El empleado queda PENDING; se sincronizará después.
            }

            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(
                exception = e,
                message = e.message ?: "Ocurrió un error al registrar el empleado."
            )
        }
    }
}
