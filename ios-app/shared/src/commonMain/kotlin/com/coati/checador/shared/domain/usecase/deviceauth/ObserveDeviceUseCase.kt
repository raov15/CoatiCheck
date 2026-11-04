package com.coati.checador.shared.domain.usecase.deviceauth

import com.coati.checador.shared.domain.model.Device
import com.coati.checador.shared.domain.repository.DeviceAuthRepository
import kotlinx.coroutines.flow.Flow

class ObserveDeviceUseCase(
    private val repository: DeviceAuthRepository
) {
    operator fun invoke(): Flow<Device?> = repository.observeDevice()
}
