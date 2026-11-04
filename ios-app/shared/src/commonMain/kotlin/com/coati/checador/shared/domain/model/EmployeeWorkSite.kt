package com.coati.checador.shared.domain.model

/**
 * Configuración laboral por día (SITE / FOREIGN).
 *
 * SITE: tiene un centro de trabajo fijo.
 * FOREIGN: no tiene centro fijo y la app puede activar
 * el seguimiento de ubicación durante la jornada.
 */
data class EmployeeWorkSite(
    val employeeId: String,
    /** 0 = domingo ... 6 = sábado */
    val weekday: Int,
    val workMode: String,
    val siteId: String? = null,
    val siteName: String? = null,
    val updatedAt: Long
) {
    companion object {
        const val MODE_SITE = "SITE"
        const val MODE_FOREIGN = "FOREIGN"
    }
}
