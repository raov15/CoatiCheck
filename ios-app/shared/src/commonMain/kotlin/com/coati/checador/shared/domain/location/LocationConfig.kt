package com.coati.checador.shared.domain.location

/**
 * Configuración del rastreador de ubicación.
 *
 * Los valores por defecto replican la estrategia de la app Android:
 * 3 intentos de 20 s con antigüedad máxima creciente (5/15/30 s) y
 * respaldo de última ubicación conocida con timeout de 5 s.
 */
class LocationConfig(
    /** Antigüedad máxima aceptable por intento, en ms. */
    val maxUpdateAgeMillisPerAttempt: List<Long> = listOf(5_000L, 15_000L, 30_000L),
    /** Duración de cada solicitud de ubicación, en ms. */
    val attemptDurationMillis: Long = 20_000L,
    /** Margen adicional sobre la duración antes de dar por perdido el intento. */
    val attemptTimeoutSlackMillis: Long = 5_000L,
    /** Pausa entre intentos fallidos, en ms. */
    val retryDelayMillis: Long = 1_500L,
    /** Timeout del respaldo de última ubicación conocida, en ms. */
    val lastKnownTimeoutMillis: Long = 5_000L,
    /** Umbral de precisión para marcar la ubicación como suficiente, en m. */
    val accuracyThresholdMeters: Float = 20f
) {
    val attempts: Int get() = maxUpdateAgeMillisPerAttempt.size
}

/** Utilidades comunes de ubicación, testeables en ambos targets. */
object LocationSupport {

    /**
     * Una ubicación es suficientemente precisa si no reporta precisión o
     * si su precisión está dentro del umbral.
     */
    fun isPreciseEnough(accuracyMeters: Float?, thresholdMeters: Float): Boolean =
        accuracyMeters == null || accuracyMeters <= thresholdMeters

    /** Antigüedad de una ubicación respecto al instante actual, en ms. */
    fun ageMillis(timestampMillis: Long, nowMillis: Long): Long =
        (nowMillis - timestampMillis).coerceAtLeast(0L)

    /** true si la ubicación tiene una antigüedad aceptable para el intento. */
    fun isFreshEnough(timestampMillis: Long, nowMillis: Long, maxAgeMillis: Long): Boolean =
        ageMillis(timestampMillis, nowMillis) <= maxAgeMillis
}
