package com.coati.checador.shared

import com.coati.checador.shared.domain.location.LocationConfig
import com.coati.checador.shared.domain.location.LocationSnapshot
import com.coati.checador.shared.domain.location.LocationSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocationSupportTest {

    @Test
    fun `ubicacion sin precision reportada se considera suficiente`() {
        assertTrue(LocationSupport.isPreciseEnough(null, 20f))
    }

    @Test
    fun `precision dentro del umbral es suficiente`() {
        assertTrue(LocationSupport.isPreciseEnough(15f, 20f))
        assertTrue(LocationSupport.isPreciseEnough(20f, 20f))
    }

    @Test
    fun `precision fuera del umbral no es suficiente`() {
        assertFalse(LocationSupport.isPreciseEnough(25f, 20f))
    }

    @Test
    fun `ageMillis calcula la antiguedad y nunca es negativa`() {
        assertEquals(5_000L, LocationSupport.ageMillis(10_000L, 15_000L))
        assertEquals(0L, LocationSupport.ageMillis(15_000L, 10_000L))
    }

    @Test
    fun `isFreshEnough respeta la antiguedad maxima`() {
        assertTrue(LocationSupport.isFreshEnough(10_000L, 14_000L, 5_000L))
        assertTrue(LocationSupport.isFreshEnough(10_000L, 15_000L, 5_000L))
        assertFalse(LocationSupport.isFreshEnough(10_000L, 16_000L, 5_000L))
    }

    @Test
    fun `config por defecto replica la estrategia android`() {
        val config = LocationConfig()
        assertEquals(3, config.attempts)
        assertEquals(listOf(5_000L, 15_000L, 30_000L), config.maxUpdateAgeMillisPerAttempt)
        assertEquals(20_000L, config.attemptDurationMillis)
        assertEquals(20f, config.accuracyThresholdMeters)
    }

    @Test
    fun `snapshot expone todos los campos`() {
        val snapshot = LocationSnapshot(
            latitude = 20.66,
            longitude = -103.35,
            accuracyMeters = 12f,
            altitudeMeters = 1_560.0,
            timestampMillis = 123_456L,
            isPreciseEnough = true
        )
        assertEquals(20.66, snapshot.latitude)
        assertEquals(-103.35, snapshot.longitude)
        assertEquals(12f, snapshot.accuracyMeters)
        assertEquals(1_560.0, snapshot.altitudeMeters)
        assertEquals(123_456L, snapshot.timestampMillis)
        assertTrue(snapshot.isPreciseEnough)
        assertEquals(snapshot, snapshot.copy())
    }

    private fun LocationSnapshot.copy() = LocationSnapshot(
        latitude, longitude, accuracyMeters, altitudeMeters, timestampMillis, isPreciseEnough
    )
}
