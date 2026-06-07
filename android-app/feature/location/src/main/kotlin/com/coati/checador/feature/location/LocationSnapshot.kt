package com.coati.checador.feature.location

data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val altitudeMeters: Double?,
    val timestampMillis: Long,
    val isPreciseEnough: Boolean
)