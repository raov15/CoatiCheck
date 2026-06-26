package com.coati.checador.feature.deviceauth.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coati.checador.core.common.Result
import com.coati.checador.feature.deviceauth.domain.model.Device
import com.coati.checador.feature.deviceauth.domain.usecase.ObserveDeviceUseCase
import com.coati.checador.feature.deviceauth.domain.usecase.RegisterDeviceUseCase
import com.coati.checador.feature.deviceauth.domain.usecase.VerifyDeviceTokenUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeviceAuthViewModel @Inject constructor(
    private val observeDevice: ObserveDeviceUseCase,
    private val registerDevice: RegisterDeviceUseCase,
    private val verifyToken: VerifyDeviceTokenUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(DeviceAuthUiState())
    val state: StateFlow<DeviceAuthUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            observeDevice().collect { device ->
                _state.update { it.copy(device = device) }
            }
        }
    }

    fun updateDeviceName(value: String) {
        _state.update { it.copy(deviceName = value, successMessage = null, errorMessage = null) }
    }

    fun updateApiBaseUrl(value: String) {
        _state.update { it.copy(apiBaseUrl = value, successMessage = null, errorMessage = null) }
    }

    fun register() {
        val current = _state.value
        if (current.deviceName.isBlank()) {
            _state.update { it.copy(errorMessage = "El nombre del dispositivo es obligatorio") }
            return
        }
        if (current.apiBaseUrl.isBlank()) {
            _state.update { it.copy(errorMessage = "La URL del servidor es obligatoria") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, successMessage = null, errorMessage = null) }
            val fingerprint = android.os.Build.FINGERPRINT
                .take(64)
                .ifBlank { android.os.Build.MODEL }
            when (val result = registerDevice(current.deviceName, fingerprint, current.apiBaseUrl)) {
                is Result.Success -> _state.update {
                    it.copy(
                        isLoading = false,
                        device = result.data,
                        successMessage = "Dispositivo registrado correctamente"
                    )
                }
                is Result.Error -> _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.message ?: "Error al registrar el dispositivo"
                    )
                }
                is Result.Loading -> Unit
            }
        }
    }

    fun verifyCurrentToken() {
        val apiUrl = _state.value.apiBaseUrl
        if (apiUrl.isBlank()) {
            _state.update { it.copy(errorMessage = "Ingresa la URL del servidor para verificar") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, successMessage = null, errorMessage = null) }
            when (val result = verifyToken(apiUrl)) {
                is Result.Success -> _state.update {
                    it.copy(
                        isLoading = false,
                        successMessage = if (result.data) "Token valido" else "Token invalido o expirado"
                    )
                }
                is Result.Error -> _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.message ?: "No se pudo verificar el token"
                    )
                }
                is Result.Loading -> Unit
            }
        }
    }
}

data class DeviceAuthUiState(
    val isLoading: Boolean = false,
    val device: Device? = null,
    val deviceName: String = "",
    val apiBaseUrl: String = "",
    val successMessage: String? = null,
    val errorMessage: String? = null
)
