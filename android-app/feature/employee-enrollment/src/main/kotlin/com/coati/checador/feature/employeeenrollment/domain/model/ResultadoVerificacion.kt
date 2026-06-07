package com.coati.checador.feature.employeeenrollment.domain.model

/**
 * Resultado de la verificación de identidad 1:N contra empleados registrados.
 * Se usa para detectar posibles duplicados antes de completar el registro.
 */
sealed class ResultadoVerificacion {

    /** No se encontró coincidencia – el empleado puede registrarse */
    object NoEncontrado : ResultadoVerificacion()

    /** Se encontró un empleado similar – posible duplicado */
    data class Coincidencia(
        val empleadoId: String,
        val nombreEmpleado: String,
        /** Distancia coseno entre embeddings (menor = más similar) */
        val distancia: Float
    ) : ResultadoVerificacion()

    /** Error durante la comparación */
    data class Error(val mensaje: String) : ResultadoVerificacion()
}
