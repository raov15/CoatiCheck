package com.coati.checador.shared.domain.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.coati.checador.shared.security.PlatformContext
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** Crea el rastreador Android sobre FusedLocationProviderClient. */
actual fun createLocationTracker(
    platformContext: PlatformContext,
    config: LocationConfig
): LocationTracker = FusedLocationTracker(platformContext, config)

internal class FusedLocationTracker(
    private val platformContext: PlatformContext,
    private val config: LocationConfig
) : LocationTracker {

    private val context: Context?
        get() = platformContext.context

    private val fusedClient: FusedLocationProviderClient? by lazy {
        context?.let { LocationServices.getFusedLocationProviderClient(it) }
    }

    override fun hasLocationPermission(): Boolean {
        val ctx = context ?: return false
        val fine = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    override fun isLocationEnabled(): Boolean {
        val ctx = context ?: return false
        val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return try {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun getBestEffortLocation(): LocationSnapshot? {
        if (!hasLocationPermission()) return null
        if (!isLocationEnabled()) return null

        // Intentos de ubicación actual con antigüedad máxima creciente.
        config.maxUpdateAgeMillisPerAttempt.forEachIndexed { index, maxAge ->
            if (index > 0) delay(config.retryDelayMillis)
            val location = requestCurrentLocation(
                durationMillis = config.attemptDurationMillis,
                maxUpdateAgeMillis = maxAge
            )
            if (location != null) return location.toSnapshot()
        }

        // Respaldo: última ubicación conocida.
        val lastKnown = try {
            withTimeoutOrNull(config.lastKnownTimeoutMillis) {
                fusedClient?.lastLocation?.await()
            }
        } catch (_: Exception) {
            null
        }
        return lastKnown?.toSnapshot()
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestCurrentLocation(
        durationMillis: Long,
        maxUpdateAgeMillis: Long
    ): Location? {
        val client = fusedClient ?: return null
        return try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setDurationMillis(durationMillis)
                .setMaxUpdateAgeMillis(maxUpdateAgeMillis)
                .build()
            withTimeoutOrNull(durationMillis + config.attemptTimeoutSlackMillis) {
                client.getCurrentLocation(request, null).await()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun Location.toSnapshot(): LocationSnapshot {
        val accuracy = if (hasAccuracy()) accuracy else null
        return LocationSnapshot(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracy,
            altitudeMeters = if (hasAltitude()) altitude else null,
            timestampMillis = if (time > 0L) time else System.currentTimeMillis(),
            isPreciseEnough = LocationSupport.isPreciseEnough(
                accuracy, config.accuracyThresholdMeters
            )
        )
    }
}
