package com.coati.checador.feature.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.coati.checador.core.common.Constants
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationTracker @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val fusedClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    // =========================================================
    // PERMISOS
    // =========================================================

    fun hasLocationPermission(): Boolean {

        val fine =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarse =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    // =========================================================
    // GPS ACTIVADO
    // =========================================================

    fun isLocationEnabled(): Boolean {

        val manager =
            context.getSystemService(
                Context.LOCATION_SERVICE
            ) as LocationManager

        return try {

            manager.isProviderEnabled(
                LocationManager.GPS_PROVIDER
            ) ||
                manager.isProviderEnabled(
                    LocationManager.NETWORK_PROVIDER
                )

        } catch (_: Exception) {

            false
        }
    }

    // =========================================================
    // OBTENER UBICACIÓN
    // =========================================================

    @SuppressLint("MissingPermission")
    suspend fun getBestEffortLocation(): LocationSnapshot? {

        // 1. Verificar permisos
        if (!hasLocationPermission()) {
            return null
        }

        // 2. Verificar GPS
        if (!isLocationEnabled()) {
            return null
        }

        // 3. Primer intento
        var location =
            requestCurrentLocation(
                durationMillis = 20_000L,
                maxUpdateAgeMillis = 5_000L
            )

        if (location != null) {
            return location.toSnapshot()
        }

        // 4. Segundo intento
        delay(1_500L)

        location =
            requestCurrentLocation(
                durationMillis = 20_000L,
                maxUpdateAgeMillis = 15_000L
            )

        if (location != null) {
            return location.toSnapshot()
        }

        // 5. Tercer intento
        delay(1_500L)

        location =
            requestCurrentLocation(
                durationMillis = 20_000L,
                maxUpdateAgeMillis = 30_000L
            )

        if (location != null) {
            return location.toSnapshot()
        }

        // 6. Última ubicación conocida como respaldo
        location =
            try {

                withTimeoutOrNull(5_000L) {
                    fusedClient
                        .lastLocation
                        .await()
                }

            } catch (_: Exception) {

                null
            }

        if (location != null) {
            return location.toSnapshot()
        }

        // 7. No se consiguió ubicación
        return null
    }

    // =========================================================
    // SOLICITAR UBICACIÓN ACTUAL
    // =========================================================

    @SuppressLint("MissingPermission")
    private suspend fun requestCurrentLocation(
        durationMillis: Long,
        maxUpdateAgeMillis: Long
    ): Location? {

        if (!hasLocationPermission()) {
            return null
        }

        return try {

            val request =
                CurrentLocationRequest.Builder()
                    .setPriority(
                        Priority.PRIORITY_HIGH_ACCURACY
                    )
                    .setDurationMillis(
                        durationMillis
                    )
                    .setMaxUpdateAgeMillis(
                        maxUpdateAgeMillis
                    )
                    .build()

            withTimeoutOrNull(
                durationMillis + 5_000L
            ) {

                fusedClient
                    .getCurrentLocation(
                        request,
                        null
                    )
                    .await()
            }

        } catch (_: Exception) {

            null
        }
    }

    // =========================================================
    // CONVERTIR LOCATION -> SNAPSHOT
    // =========================================================

    private fun Location.toSnapshot(): LocationSnapshot {

        val accuracy =
            if (hasAccuracy()) {
                accuracy
            } else {
                null
            }

        val altitude =
            if (hasAltitude()) {
                altitude
            } else {
                null
            }

        return LocationSnapshot(
            latitude = latitude,
            longitude = longitude,

            accuracyMeters = accuracy,

            altitudeMeters = altitude,

            timestampMillis =
                if (time > 0L) {
                    time
                } else {
                    System.currentTimeMillis()
                },

            isPreciseEnough =
                accuracy == null ||
                    accuracy <= Constants.GPS_ACCURACY_THRESHOLD_M
        )
    }
}