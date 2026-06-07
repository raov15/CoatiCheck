package com.coati.checador.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey
    @ColumnInfo(name = "id_local")
    val idLocal: String,                    // UUID generado en dispositivo

    @ColumnInfo(name = "id_remote")
    val idRemote: String? = null,           // UUID asignado por servidor

    @ColumnInfo(name = "device_name")
    val deviceName: String,

    @ColumnInfo(name = "site_id")
    val siteId: String? = null,             // UUID del servidor

    @ColumnInfo(name = "auth_token")
    val authToken: String? = null,          // JWT de larga duración

    @ColumnInfo(name = "registered_at")
    val registeredAt: Long                  // epoch ms UTC
)
