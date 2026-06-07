package com.coati.checador.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "device_preferences")

@Singleton
class DevicePreferencesDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val AUTH_TOKEN = stringPreferencesKey("auth_token")
        private val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        private val API_BASE_URL = stringPreferencesKey("api_base_url")
    }

    val deviceId: Flow<String?> = context.dataStore.data.map { it[DEVICE_ID] }
    val authToken: Flow<String?> = context.dataStore.data.map { it[AUTH_TOKEN] }
    val lastSyncAt: Flow<Long?> = context.dataStore.data.map { it[LAST_SYNC_AT] }
    val apiBaseUrl: Flow<String?> = context.dataStore.data.map { it[API_BASE_URL] }

    suspend fun saveDeviceId(deviceId: String) {
        context.dataStore.edit { it[DEVICE_ID] = deviceId }
    }

    suspend fun saveAuthToken(token: String) {
        context.dataStore.edit { it[AUTH_TOKEN] = token }
    }

    suspend fun updateLastSyncAt(timestamp: Long) {
        context.dataStore.edit { it[LAST_SYNC_AT] = timestamp }
    }

    suspend fun saveApiBaseUrl(url: String) {
        context.dataStore.edit { it[API_BASE_URL] = url }
    }
}
