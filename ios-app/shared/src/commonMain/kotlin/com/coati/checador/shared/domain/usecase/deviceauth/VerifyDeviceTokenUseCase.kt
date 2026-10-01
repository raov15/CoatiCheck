package com.coati.checador.shared.domain.usecase.deviceauth

import com.coati.checador.shared.common.Result
import com.coati.checador.shared.domain.repository.DeviceAuthRepository

class VerifyDeviceTokenUseCase(
    private val repository: DeviceAuthRepository
) {
    suspend operator fun invoke(apiBaseUrl: String): Result<Boolean> =
        repository.verifyToken(apiBaseUrl)
}
