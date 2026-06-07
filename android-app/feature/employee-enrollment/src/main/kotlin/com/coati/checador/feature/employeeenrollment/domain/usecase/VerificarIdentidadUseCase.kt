package com.coati.checador.feature.employeeenrollment.domain.usecase

import android.graphics.Bitmap
import com.coati.checador.feature.employeeenrollment.domain.model.ResultadoVerificacion
import com.coati.checador.feature.employeeenrollment.domain.repository.RepositorioRegistroEmpleado
import timber.log.Timber
import javax.inject.Inject

/**
 * Caso de uso: verificar si un rostro ya está registrado (anti-duplicados 1:N).
 *
 * Se ejecuta antes de completar el registro para alertar si el empleado
 * ya existe en el sistema con un nombre o código distinto.
 */
class VerificarIdentidadUseCase @Inject constructor(
    private val repositorio: RepositorioRegistroEmpleado
) {
    suspend operator fun invoke(imagenRostro: Bitmap): ResultadoVerificacion {
        Timber.d("VerificarIdentidadUseCase: comparando rostro contra perfiles registrados")
        return try {
            repositorio.verificarIdentidad(imagenRostro)
        } catch (e: Exception) {
            Timber.e(e, "VerificarIdentidadUseCase: error durante la comparación")
            ResultadoVerificacion.Error(e.message ?: "Error desconocido")
        }
    }
}
