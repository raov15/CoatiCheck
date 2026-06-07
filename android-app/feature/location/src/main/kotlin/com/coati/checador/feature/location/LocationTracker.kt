package com.coati.checador.feature.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.coati.checador.core.common.Constants
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationTracker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val fusedClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    suspend fun getBestEffortLocation(): LocationSnapshot? {
        if (!hasLocationPermission()) return null

        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(Constants.GPS_TIMEOUT_MS)
            .setMaxUpdateAgeMillis(5_000)
            .build()

        val location = fusedClient.getCurrentLocation(request, null).await()
            ?: fusedClient.lastLocation.await()
            ?: return null

        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        val altitude = if (location.hasAltitude()) location.altitude else null

        return LocationSnapshot(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = accuracy,
            altitudeMeters = altitude,
            timestampMillis = location.time,
            isPreciseEnough = accuracy == null || accuracy <= Constants.GPS_ACCURACY_THRESHOLD_M
        )
    }
}