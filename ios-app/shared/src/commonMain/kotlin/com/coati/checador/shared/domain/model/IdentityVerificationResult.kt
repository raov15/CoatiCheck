package com.coati.checador.shared.domain.model

/**
 * Resultado de la verificación de identidad 1:N contra empleados registrados.
 * Se usa para detectar posibles duplicados antes de completar el registro.
 */
sealed class IdentityVerificationResult {

    /** No se encontró coincidencia – el empleado puede registrarse */
    data object NotFound : IdentityVerificationResult()

    /** Se encontró un empleado similar – posible duplicado */
    data class Match(
        val employeeId: String,
        val employeeName: String,
        /** Distancia coseno entre embeddings (menor = más similar) */
        val distance: Float
    ) : IdentityVerificationResult()

    /** Error durante la comparación */
    data class Failure(val message: String) : IdentityVerificationResult()
}
