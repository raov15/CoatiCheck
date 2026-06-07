package com.coati.checador.feature.employeeenrollment.domain.usecase

import android.graphics.Bitmap
import com.coati.checador.feature.employeeenrollment.domain.model.Empleado
import com.coati.checador.feature.employeeenrollment.domain.repository.RepositorioRegistroEmpleado
import com.coati.checador.core.common.Result
import timber.log.Timber
import javax.inject.Inject

/**
 * Caso de uso: registrar un empleado nuevo con su perfil facial.
 *
 * Flujo:
 * 1. Valida que el código de empleado no esté duplicado.
 * 2. Persiste el empleado y su embedding cifrado en Room (OFFLINE FIRST).
 * 3. Devuelve Result.Success o Result.Error.
 */
class RegistrarEmpleadoUseCase @Inject constructor(
    private val repositorio: RepositorioRegistroEmpleado
) {
    suspend operator fun invoke(
        empleado: Empleado,
        imagenRostro: Bitmap
    ): Result<Unit> {
        Timber.d("RegistrarEmpleadoUseCase: iniciando registro para código=${empleado.codigoEmpleado}")

        // Validar duplicado por código
        if (repositorio.existeCodigoEmpleado(empleado.codigoEmpleado)) {
            Timber.w("RegistrarEmpleadoUseCase: código ${empleado.codigoEmpleado} ya existe")
            return Result.Error(
                exception = IllegalArgumentException("Código de empleado ya registrado"),
                message = "El código '${empleado.codigoEmpleado}' ya está asignado a otro empleado."
            )
        }

        return try {
            val exito = repositorio.registrarEmpleado(empleado, imagenRostro)
            if (exito) {
                Timber.i("RegistrarEmpleadoUseCase: empleado ${empleado.idLocal} registrado correctamente")
                Result.Success(Unit)
            } else {
                Timber.e("RegistrarEmpleadoUseCase: fallo al insertar en Room")
                Result.Error(
                    exception = RuntimeException("Error al guardar en la base de datos"),
                    message = "No se pudo guardar el empleado. Intenta de nuevo."
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "RegistrarEmpleadoUseCase: excepción inesperada")
            Result.Error(exception = e, message = e.message)
        }
    }
}
