package com.coati.checador.shared.domain.location

/**
 * Instantánea de ubicación obtenida por el rastreador de plataforma.
 * Mismo contrato que la app Android.
 */
class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val altitudeMeters: Double?,
    val timestampMillis: Long,
    val isPreciseEnough: Boolean
) {
    override fun equals(other: Any?): Boolean =
        other is LocationSnapshot &&
            other.latitude == latitude &&
            other.longitude == longitude &&
            other.accuracyMeters == accuracyMeters &&
            other.altitudeMeters == altitudeMeters &&
            other.timestampMillis == timestampMillis &&
            other.isPreciseEnough == isPreciseEnough

    override fun hashCode(): Int =
        latitude.hashCode() * 31 + longitude.hashCode() + timestampMillis.hashCode()

    override fun toString(): String =
        "LocationSnapshot(lat=$latitude, lng=$longitude, acc=$accuracyMeters m, ts=$timestampMillis)"
}
