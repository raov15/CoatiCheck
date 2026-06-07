package com.coati.checador

import android.app.Application
import android.content.Context
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class CoatiApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Timber.plant(Timber.DebugTree())
        }
        installCrashLogger()
    }

    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = throwable.stackTraceToString()
                Timber.e(throwable, "CRASH no capturado en hilo: ${thread.name}")
                getSharedPreferences("crash_log", Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_crash", trace.take(4000))
                    .putLong("crash_time", System.currentTimeMillis())
                    .apply()
            } catch (_: Exception) { /* no se puede registrar, ignorar */ }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
