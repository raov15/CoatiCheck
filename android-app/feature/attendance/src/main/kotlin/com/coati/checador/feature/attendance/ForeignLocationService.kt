package com.coati.checador.feature.attendance

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.coati.checador.core.database.dao.ForeignLocationPointDao
import com.coati.checador.core.database.entity.ForeignLocationPointEntity
import com.coati.checador.core.database.model.SyncStatus
import com.coati.checador.core.sync.SyncManager
import com.coati.checador.feature.location.LocationTracker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class ForeignLocationService : Service() {

    @Inject
    lateinit var locationTracker: LocationTracker

    @Inject
    lateinit var foreignLocationPointDao: ForeignLocationPointDao

    @Inject
    lateinit var syncManager: SyncManager

    private val serviceJob = SupervisorJob()

    private val serviceScope =
        CoroutineScope(
            Dispatchers.IO + serviceJob
        )

    private var trackingJob: Job? = null

    private var activeEmployeeId: String? = null
    private var activeDeviceId: String? = null
    private var activeWorkDate: String? = null

    // =========================================================
    // CICLO DE VIDA
    // =========================================================

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_START -> {

                val employeeId =
                    intent.getStringExtra(
                        EXTRA_EMPLOYEE_ID
                    )

                val deviceId =
                    intent.getStringExtra(
                        EXTRA_DEVICE_ID
                    )

                val workDate =
                    intent.getStringExtra(
                        EXTRA_WORK_DATE
                    ) ?: currentWorkDate()

                if (employeeId.isNullOrBlank()) {

                    Timber.e(
                        "ForeignLocationService: no se recibió employeeId"
                    )

                    stopSelf()

                    return START_NOT_STICKY
                }

                startForegroundServiceNotification()

                saveSession(
                    employeeId = employeeId,
                    deviceId = deviceId,
                    workDate = workDate
                )

                startTracking(
                    employeeId = employeeId,
                    deviceId = deviceId,
                    workDate = workDate
                )
            }

            ACTION_STOP -> {

                val session =
                    loadSession()

                val employeeId =
                    intent.getStringExtra(
                        EXTRA_EMPLOYEE_ID
                    )
                        ?: activeEmployeeId
                        ?: session?.employeeId

                val deviceId =
                    intent.getStringExtra(
                        EXTRA_DEVICE_ID
                    )
                        ?: activeDeviceId
                        ?: session?.deviceId

                val workDate =
                    intent.getStringExtra(
                        EXTRA_WORK_DATE
                    )
                        ?: activeWorkDate
                        ?: session?.workDate
                        ?: currentWorkDate()

                startForegroundServiceNotification()

                trackingJob?.cancel()
                trackingJob = null

                serviceScope.launch {

                    if (!employeeId.isNullOrBlank()) {

                        try {

                            captureAndSaveLocation(
                                employeeId = employeeId,
                                deviceId = deviceId,
                                workDate = workDate
                            )

                        } catch (e: Exception) {

                            Timber.e(
                                e,
                                "ForeignLocationService: error guardando última ubicación"
                            )
                        }
                    }

                    clearSession()

                    stopForeground(
                        STOP_FOREGROUND_REMOVE
                    )

                    stopSelf()
                }
            }

            else -> {

                val session =
                    loadSession()

                if (session != null) {

                    startForegroundServiceNotification()

                    startTracking(
                        employeeId = session.employeeId,
                        deviceId = session.deviceId,
                        workDate = session.workDate
                    )

                } else {

                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    // =========================================================
    // INICIAR RASTREO
    // =========================================================

    private fun startTracking(
        employeeId: String,
        deviceId: String?,
        workDate: String
    ) {

        activeEmployeeId = employeeId
        activeDeviceId = deviceId
        activeWorkDate = workDate

        if (trackingJob?.isActive == true) {

            Timber.d(
                "ForeignLocationService: el rastreo ya está activo"
            )

            return
        }

        trackingJob =
            serviceScope.launch {

                Timber.i(
                    "ForeignLocationService: rastreo iniciado. " +
                        "employee=$employeeId, " +
                        "workDate=$workDate"
                )

                while (isActive) {

                    val locationSaved =
                        try {

                            captureAndSaveLocation(
                                employeeId = employeeId,
                                deviceId = deviceId,
                                workDate = workDate
                            )

                        } catch (e: CancellationException) {

                            throw e

                        } catch (e: Exception) {

                            Timber.e(
                                e,
                                "ForeignLocationService: error capturando ubicación"
                            )

                            false
                        }

                    if (locationSaved) {

                        // Punto guardado correctamente.
                        // Siguiente captura en 30 minutos.
                        delay(
                            LOCATION_INTERVAL_MS
                        )

                    } else {

                        // Si no se obtuvo ubicación,
                        // reintentar en 30 segundos.
                        delay(
                            LOCATION_RETRY_INTERVAL_MS
                        )
                    }
                }
            }
    }

    // =========================================================
    // CAPTURAR Y GUARDAR PUNTO
    // =========================================================

    private suspend fun captureAndSaveLocation(
        employeeId: String,
        deviceId: String?,
        workDate: String
    ): Boolean {

        val snapshot =
            try {

                locationTracker
                    .getBestEffortLocation()

            } catch (e: Exception) {

                Timber.e(
                    e,
                    "ForeignLocationService: error obteniendo ubicación"
                )

                return false
            }

        if (snapshot == null) {

            Timber.w(
                "ForeignLocationService: no se obtuvo ubicación"
            )

            return false
        }

        val now =
            System.currentTimeMillis()

        val point =
            ForeignLocationPointEntity(

                idLocal =
                    UUID.randomUUID().toString(),

                employeeId =
                    employeeId,

                workDate =
                    workDate,

                occurredAt =
                    now,

                latitude =
                    snapshot.latitude,

                longitude =
                    snapshot.longitude,

                accuracyM =
                    snapshot.accuracyMeters,

                altitudeM =
                    snapshot.altitudeMeters,

                deviceId =
                    deviceId,

                syncStatus =
                    SyncStatus.PENDING,

                syncAttempts =
                    0,

                lastError =
                    null,

                createdAt =
                    now
            )

        // =====================================================
        // GUARDAR LOCALMENTE
        // =====================================================

        try {

            foreignLocationPointDao.insert(
                point
            )

        } catch (e: Exception) {

            Timber.e(
                e,
                "ForeignLocationService: error guardando punto GPS en Room"
            )

            return false
        }

        Timber.i(
            "ForeignLocationService: punto GPS guardado. " +
                "employee=$employeeId, " +
                "lat=${snapshot.latitude}, " +
                "lng=${snapshot.longitude}"
        )

        // =====================================================
        // SINCRONIZAR
        // =====================================================

        try {

            syncManager.syncForeignLocationsNow()

        } catch (e: Exception) {

            /*
             * El punto ya quedó guardado en Room.
             * Si la sincronización inmediata falla,
             * el Worker periódico podrá enviarlo después.
             */
            Timber.w(
                e,
                "ForeignLocationService: punto guardado, " +
                    "pero no se pudo solicitar sincronización inmediata"
            )
        }

        return true
    }

    // =========================================================
    // NOTIFICACIÓN FOREGROUND
    // =========================================================

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            val channel =
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Ubicación durante jornada",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {

                    description =
                        "Registro de ubicación durante una jornada foránea"
                }

            manager.createNotificationChannel(
                channel
            )
        }
    }

    private fun startForegroundServiceNotification() {

        val notification =
            NotificationCompat.Builder(
                this,
                NOTIFICATION_CHANNEL_ID
            )
                .setSmallIcon(
                    android.R.drawable.ic_menu_mylocation
                )
                .setContentTitle(
                    "Jornada foránea activa"
                )
                .setContentText(
                    "Registrando ubicación durante la jornada"
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(
                    NotificationCompat.PRIORITY_LOW
                )
                .build()

        val foregroundServiceType =
            if (
                Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.Q
            ) {

                ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_LOCATION

            } else {

                0
            }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundServiceType
        )
    }

    // =========================================================
    // PERSISTIR JORNADA ACTIVA
    // =========================================================

    private fun saveSession(
        employeeId: String,
        deviceId: String?,
        workDate: String
    ) {

        getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
            .edit()
            .putBoolean(
                KEY_ACTIVE,
                true
            )
            .putString(
                KEY_EMPLOYEE_ID,
                employeeId
            )
            .putString(
                KEY_DEVICE_ID,
                deviceId
            )
            .putString(
                KEY_WORK_DATE,
                workDate
            )
            .apply()
    }

    private fun loadSession():
        ForeignTrackingSession? {

        val prefs =
            getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

        if (
            !prefs.getBoolean(
                KEY_ACTIVE,
                false
            )
        ) {

            return null
        }

        val employeeId =
            prefs.getString(
                KEY_EMPLOYEE_ID,
                null
            )
                ?: return null

        val deviceId =
            prefs.getString(
                KEY_DEVICE_ID,
                null
            )

        val workDate =
            prefs.getString(
                KEY_WORK_DATE,
                null
            )
                ?: return null

        return ForeignTrackingSession(
            employeeId =
                employeeId,

            deviceId =
                deviceId,

            workDate =
                workDate
        )
    }

    private fun clearSession() {

        activeEmployeeId = null
        activeDeviceId = null
        activeWorkDate = null

        getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
            .edit()
            .clear()
            .apply()
    }

    // =========================================================
    // FECHA DE JORNADA
    // =========================================================

    private fun currentWorkDate():
        String {

        val formatter =
            SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.US
            )

        formatter.timeZone =
            TimeZone.getTimeZone(
                "America/Mexico_City"
            )

        return formatter.format(
            Date()
        )
    }

    // =========================================================
    // CICLO DE VIDA
    // =========================================================

    override fun onDestroy() {

        trackingJob?.cancel()

        serviceScope.cancel()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? =
        null

    private data class ForeignTrackingSession(
        val employeeId: String,
        val deviceId: String?,
        val workDate: String
    )

    companion object {

        const val ACTION_START =
            "com.coati.checador.action.START_FOREIGN_TRACKING"

        const val ACTION_STOP =
            "com.coati.checador.action.STOP_FOREIGN_TRACKING"

        const val EXTRA_EMPLOYEE_ID =
            "employee_id"

        const val EXTRA_DEVICE_ID =
            "device_id"

        const val EXTRA_WORK_DATE =
            "work_date"

        // Captura normal cada 30 minutos.
        private const val LOCATION_INTERVAL_MS =
            30L * 60L * 1000L

        // Si falla el GPS, reintenta en 30 segundos.
        private const val LOCATION_RETRY_INTERVAL_MS =
            30L * 1000L

        private const val NOTIFICATION_CHANNEL_ID =
            "foreign_location_tracking"

        private const val NOTIFICATION_ID =
            4101

        private const val PREFS_NAME =
            "foreign_location_session"

        private const val KEY_ACTIVE =
            "active"

        private const val KEY_EMPLOYEE_ID =
            "employee_id"

        private const val KEY_DEVICE_ID =
            "device_id"

        private const val KEY_WORK_DATE =
            "work_date"
    }
}