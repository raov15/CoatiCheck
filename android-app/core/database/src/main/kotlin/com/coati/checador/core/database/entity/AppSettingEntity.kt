package com.coati.checador.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,

    @ColumnInfo(name = "value")
    val value: String? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
) {
    companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_FACE_MATCH_THRESHOLD = "face_match_threshold"
        const val KEY_GPS_TIMEOUT_MS = "gps_timeout_ms"
        const val KEY_API_BASE_URL = "api_base_url"
        const val KEY_MODEL_VERSION = "face_model_version"
        const val KEY_ADMIN_PIN_HASH = "admin_pin_hash"
    }
}
