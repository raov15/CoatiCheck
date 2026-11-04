package com.coati.checador.shared.domain.location

import com.coati.checador.shared.security.PlatformContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeIntervalSinceReferenceDate
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.Foundation.timeIntervalSince1970

/**
 * Rastreador iOS sobre CoreLocation.
 *
 * Replica la estrategia de la app Android: varios intentos con
 * requestLocation aceptando antigüedad creciente, y respaldo con la
 * última ubicación conocida (CLLocationManager.location).
 *
 * La solicitud de permiso (requestWhenInUseAuthorization) la hace la capa
 * de UI; aquí solo se verifica el estado actual. Se asume el modo
 * "when in use", suficiente para el registro de asistencia.
 */
actual fun createLocationTracker(
    platformContext: PlatformContext,
    config: LocationConfig
): LocationTracker = CoreLocationTracker(config)

internal class CoreLocationTracker(
    private val config: LocationConfig
) : LocationTracker {

    private val manager: CLLocationManager by lazy {
        CLLocationManager().apply { desiredAccuracy = kCLLocationAccuracyBest }
    }

    /**
     * Peticiones en vuelo. CoreLocation retiene débilmente al delegado, así
     * que se mantienen referencias fuertes aquí hasta completarse.
     */
    private val activeRequests = mutableListOf<LocationRequest>()

    override fun hasLocationPermission(): Boolean {
        val status: CLAuthorizationStatus = manager.authorizationStatus
        return status == kCLAuthorizationStatusAuthorizedWhenInUse ||
            status == kCLAuthorizationStatusAuthorizedAlways
    }

    override fun isLocationEnabled(): Boolean =
        CLLocationManager.locationServicesEnabled()

    override suspend fun getBestEffortLocation(): LocationSnapshot? {
        if (!hasLocationPermission()) return null
        if (!isLocationEnabled()) return null

        config.maxUpdateAgeMillisPerAttempt.forEachIndexed { index, maxAge ->
            if (index > 0) delay(config.retryDelayMillis)
            val location = withTimeoutOrNull(
                config.attemptDurationMillis + config.attemptTimeoutSlackMillis
            ) {
                requestLocationOnce()
            }
            if (location != null) {
                val snapshot = location.toSnapshot()
                if (LocationSupport.isFreshEnough(
                        snapshot.timestampMillis,
                        currentTimeMillis(),
                        maxAge
                    )
                ) {
                    return snapshot
                }
            }
        }

        // Respaldo: última ubicación conocida en caché de CoreLocation.
        return manager.location?.toSnapshot()
    }

    private suspend fun requestLocationOnce(): CLLocation? =
        suspendCancellableCoroutine { cont ->
            val request = LocationRequest(manager) { location ->
                activeRequests.removeAll { it.finished }
                if (cont.isActive) cont.resume(location)
            }
            activeRequests.add(request)
            cont.invokeOnCancellation { request.cancel() }
            request.start()
        }

    @OptIn(ExperimentalForeignApi::class)
    private fun CLLocation.toSnapshot(): LocationSnapshot {
        val accuracy = if (horizontalAccuracy >= 0.0) horizontalAccuracy.toFloat() else null
        val point = coordinate.useContents { latitude to longitude }
        return LocationSnapshot(
            latitude = point.first,
            longitude = point.second,
            accuracyMeters = accuracy,
            altitudeMeters = if (verticalAccuracy >= 0.0) altitude else null,
            timestampMillis = (timestamp.timeIntervalSince1970 * 1000.0).toLong(),
            isPreciseEnough = LocationSupport.isPreciseEnough(
                accuracy, config.accuracyThresholdMeters
            )
        )
    }
}

/** Petición de ubicación one-shot con su delegado retenido. */
private class LocationRequest(
    private val manager: CLLocationManager,
    private val onResult: (CLLocation?) -> Unit
) : NSObject(), CLLocationManagerDelegateProtocol {

    var finished = false
        private set

    fun start() {
        manager.delegate = this
        manager.requestLocation()
    }

    fun cancel() {
        finish(null)
    }

    private fun finish(location: CLLocation?) {
        if (finished) return
        finished = true
        manager.delegate = null
        onResult(location)
    }

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        finish(didUpdateLocations.lastOrNull() as? CLLocation)
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        finish(null)
    }
}

private fun currentTimeMillis(): Long =
    ((NSDate.timeIntervalSinceReferenceDate() + REFERENCE_DATE_EPOCH_OFFSET) * 1000.0).toLong()

/** Segundos entre la época de referencia de Apple (2001) y la de Unix. */
private const val REFERENCE_DATE_EPOCH_OFFSET = 978307200.0
