package com.coati.checador.feature.deviceauth.domain.model

data class Device(
    val idLocal: String,
    val idRemote: String?,
    val deviceName: String,
    val siteId: String?,
    val authToken: String?,
    val registeredAt: Long,
    val isRegistered: Boolean = idRemote != null && authToken != null
)
