package com.coati.checador.shared.domain.usecase.employee

import com.coati.checador.shared.domain.model.IdentityVerificationResult
import com.coati.checador.shared.domain.repository.EmployeeRepository

/**
 * Caso de uso: verificar si un rostro ya está registrado (anti-duplicados 1:N).
 *
 * Se ejecuta antes de completar el registro para alertar si el empleado
 * ya existe en el sistema con un nombre o código distinto.
 */
class VerifyIdentityUseCase(
    private val repository: EmployeeRepository
) {
    suspend operator fun invoke(faceImage: ByteArray): IdentityVerificationResult {
        return try {
            repository.verifyIdentity(faceImage)
        } catch (e: Exception) {
            IdentityVerificationResult.Failure(e.message ?: "Error desconocido")
        }
    }
}
