package com.coati.checador.feature.employeeenrollment.domain.usecase

import android.graphics.Bitmap
import com.coati.checador.core.common.Result
import com.coati.checador.core.database.dao.EmployeeDao
import com.coati.checador.core.sync.SyncManager
import com.coati.checador.feature.employeeenrollment.domain.model.Empleado
import com.coati.checador.feature.employeeenrollment.domain.repository.RepositorioRegistroEmpleado
import timber.log.Timber
import javax.inject.Inject

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
class RegistrarEmpleadoUseCase @Inject constructor(

    private val repositorio: RepositorioRegistroEmpleado,

    private val employeeDao: EmployeeDao,

    private val syncManager: SyncManager

) {

    companion object {

        /**
         * Cargos permitidos actualmente
         * durante el enrolamiento.
         */
        val CARGOS_PERMITIDOS = listOf(
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
        empleado: Empleado,
        imagenRostro: Bitmap
    ): Result<Unit> {

        Timber.d(
            "RegistrarEmpleadoUseCase: intentando registrar " +
                empleado.nombreCompleto
        )

        // =====================================================
        // 1. VALIDAR QUE EL DISPOSITIVO NO TENGA OTRO EMPLEADO
        // =====================================================

        val empleadosActivos =
            try {

                employeeDao.countActive()

            } catch (e: Exception) {

                Timber.e(
                    e,
                    "RegistrarEmpleadoUseCase: error consultando empleados activos"
                )

                return Result.Error(
                    exception = e,
                    message =
                        "No se pudo verificar el dispositivo. Intenta nuevamente."
                )
            }

        if (empleadosActivos >= 1) {

            Timber.w(
                "RegistrarEmpleadoUseCase: " +
                    "el dispositivo ya tiene un empleado registrado"
            )

            return Result.Error(
                exception = IllegalStateException(
                    "Este dispositivo ya tiene un empleado registrado"
                ),
                message =
                    "Este teléfono ya tiene un empleado registrado. " +
                    "No puedes agregar otro empleado."
            )
        }

        // =====================================================
        // 2. VALIDAR CARGO
        // =====================================================

        val cargo =
            empleado.departamento.trim()

        if (cargo.isBlank()) {

            return Result.Error(
                exception = IllegalArgumentException(
                    "Cargo requerido"
                ),
                message =
                    "Selecciona el cargo del empleado."
            )
        }

        if (cargo !in CARGOS_PERMITIDOS) {

            Timber.w(
                "RegistrarEmpleadoUseCase: cargo no permitido: $cargo"
            )

            return Result.Error(
                exception = IllegalArgumentException(
                    "Cargo no permitido"
                ),
                message =
                    "Selecciona uno de los cargos disponibles."
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

        if (empleado.codigoEmpleado != "AUTO") {

            val codigoExiste =
                try {

                    repositorio.existeCodigoEmpleado(
                        empleado.codigoEmpleado
                    )

                } catch (e: Exception) {

                    Timber.e(
                        e,
                        "RegistrarEmpleadoUseCase: error verificando código"
                    )

                    return Result.Error(
                        exception = e,
                        message =
                            "No se pudo verificar el código del empleado."
                    )
                }

            if (codigoExiste) {

                Timber.w(
                    "RegistrarEmpleadoUseCase: código duplicado " +
                        empleado.codigoEmpleado
                )

                return Result.Error(
                    exception = IllegalArgumentException(
                        "Código de empleado ya registrado"
                    ),
                    message =
                        "El código '${empleado.codigoEmpleado}' " +
                        "ya está asignado."
                )
            }
        }

        // =====================================================
        // 4. VALIDAR DATOS BÁSICOS
        // =====================================================

        if (empleado.nombre.isBlank()) {

            return Result.Error(
                exception = IllegalArgumentException(
                    "Nombre requerido"
                ),
                message =
                    "El nombre del empleado es requerido."
            )
        }

        if (empleado.apellidoPaterno.isBlank()) {

            return Result.Error(
                exception = IllegalArgumentException(
                    "Apellido paterno requerido"
                ),
                message =
                    "El apellido paterno es requerido."
            )
        }

        if (empleado.rfc.isBlank()) {

            return Result.Error(
                exception = IllegalArgumentException(
                    "RFC requerido"
                ),
                message =
                    "El RFC es requerido."
            )
        }

        if (empleado.curp.isBlank()) {

            return Result.Error(
                exception = IllegalArgumentException(
                    "CURP requerida"
                ),
                message =
                    "La CURP es requerida."
            )
        }

        if (empleado.nss.isBlank()) {

            return Result.Error(
                exception = IllegalArgumentException(
                    "NSS requerido"
                ),
                message =
                    "El NSS es requerido."
            )
        }

        // =====================================================
        // 5. GUARDAR EMPLEADO LOCALMENTE
        // =====================================================

        return try {

            val exito =
                repositorio.registrarEmpleado(
                    empleado = empleado,
                    imagenRostro = imagenRostro
                )

            if (!exito) {

                Timber.e(
                    "RegistrarEmpleadoUseCase: " +
                        "el repositorio no pudo guardar el empleado"
                )

                return Result.Error(
                    exception = RuntimeException(
                        "Error al guardar en la base de datos"
                    ),
                    message =
                        "No se pudo guardar el empleado. Intenta nuevamente."
                )
            }

            Timber.i(
                "RegistrarEmpleadoUseCase: " +
                    "empleado ${empleado.idLocal} " +
                    "registrado correctamente en Room"
            )

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

                syncManager.syncNow()

                Timber.i(
                    "RegistrarEmpleadoUseCase: " +
                        "sincronización inmediata solicitada"
                )

            } catch (e: Exception) {

                Timber.w(
                    e,
                    "RegistrarEmpleadoUseCase: empleado guardado " +
                        "pero no fue posible iniciar la sincronización"
                )
            }

            Result.Success(Unit)

        } catch (e: Exception) {

            Timber.e(
                e,
                "RegistrarEmpleadoUseCase: excepción registrando empleado"
            )

            Result.Error(
                exception = e,
                message =
                    e.message
                        ?: "Ocurrió un error al registrar el empleado."
            )
        }
    }
}