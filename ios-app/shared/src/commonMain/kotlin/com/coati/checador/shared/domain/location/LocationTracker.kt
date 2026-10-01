package com.coati.checador.shared.domain.location

import com.coati.checador.shared.security.PlatformContext

/**
 * Rastreador de ubicación multiplataforma.
 *
 * - Android: FusedLocationProviderClient.
 * - iOS: CoreLocation (CLLocationManager con requestLocation).
 *
 * El permiso se solicita fuera de esta clase (capa de UI); aquí solo se
 * verifica el estado actual.
 */
interface LocationTracker {

    /** true si la app tiene permiso de ubicación concedido (fino o aproximado). */
    fun hasLocationPermission(): Boolean

    /** true si los servicios de ubicación del sistema están activados. */
    fun isLocationEnabled(): Boolean

    /**
     * Mejor ubicación disponible siguiendo la estrategia de la app Android:
     *
     * 1. Hasta [LocationConfig.attempts] solicitudes de ubicación actual,
     *    aceptando resultados con antigüedad creciente por intento.
     * 2. Respaldo con la última ubicación conocida.
     * 3. null si nada funcionó (sin permiso, GPS apagado, timeouts).
     */
    suspend fun getBestEffortLocation(): LocationSnapshot?
}

/** Crea el rastreador de ubicación de la plataforma actual. */
expect fun createLocationTracker(
    platformContext: PlatformContext,
    config: LocationConfig = LocationConfig()
): LocationTracker
