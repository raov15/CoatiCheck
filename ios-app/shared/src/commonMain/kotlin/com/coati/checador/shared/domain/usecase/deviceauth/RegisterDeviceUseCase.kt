package com.coati.checador.shared.domain.usecase.deviceauth

import com.coati.checador.shared.common.Result
import com.coati.checador.shared.domain.model.Device
import com.coati.checador.shared.domain.repository.DeviceAuthRepository

class RegisterDeviceUseCase(
    private val repository: DeviceAuthRepository
) {
    /**
     * Orquesta el registro completo:
     * 1. Si no existe dispositivo local, lo crea.
     * 2. Llama al backend para registrarlo y obtener id_remote + auth_token.
     */
    suspend operator fun invoke(
        deviceName: String,
        deviceFingerprint: String,
        apiBaseUrl: String
    ): Result<Device> {
        val existing = repository.getCurrentDevice()
        if (existing == null) {
            repository.createLocalDevice(deviceName)
        }
        return repository.registerWithBackend(deviceName, deviceFingerprint, apiBaseUrl)
    }

    suspend fun enroll(
        deviceName: String,
        deviceFingerprint: String,
        enrollmentCode: String,
        apiBaseUrl: String
    ): Result<Device> {
        val existing = repository.getCurrentDevice()
        if (existing == null) repository.createLocalDevice(deviceName)
        return repository.enrollWithBackend(deviceName, deviceFingerprint, enrollmentCode, apiBaseUrl)
    }
}
