package com.coati.checador.shared.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * SecureStorage Android: EncryptedSharedPreferences con clave maestra
 * AES256-GCM protegida por AndroidKeyStore.
 */
actual class SecureStorage actual constructor(
    platformContext: PlatformContext
) : KeyValueStore {

    private val prefs: android.content.SharedPreferences by lazy {
        val appContext = platformContext.context
            ?: error("PlatformContext sin Context: regístralo con PlatformContext(context)")
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    actual override fun getString(key: String): String? = prefs.getString(key, null)

    actual override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    actual override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private companion object {
        const val FILE_NAME = "coati_secure_prefs"
    }
}
