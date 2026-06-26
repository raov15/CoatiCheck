package com.coati.checador.feature.deviceauth.domain.usecase

import com.coati.checador.core.common.Result
import com.coati.checador.feature.deviceauth.domain.repository.DeviceAuthRepository
import javax.inject.Inject

class VerifyDeviceTokenUseCase @Inject constructor(
    private val repository: DeviceAuthRepository
) {
    suspend operator fun invoke(apiBaseUrl: String): Result<Boolean> =
        repository.verifyToken(apiBaseUrl)
}
