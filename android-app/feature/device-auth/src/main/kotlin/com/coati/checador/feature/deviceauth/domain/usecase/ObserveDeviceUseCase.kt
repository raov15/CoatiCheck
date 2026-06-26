package com.coati.checador.feature.deviceauth.domain.usecase

import com.coati.checador.feature.deviceauth.domain.model.Device
import com.coati.checador.feature.deviceauth.domain.repository.DeviceAuthRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveDeviceUseCase @Inject constructor(
    private val repository: DeviceAuthRepository
) {
    operator fun invoke(): Flow<Device?> = repository.observeDevice()
}
