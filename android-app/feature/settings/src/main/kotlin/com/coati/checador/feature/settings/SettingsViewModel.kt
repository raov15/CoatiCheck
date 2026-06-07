package com.coati.checador.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coati.checador.core.database.dao.AppSettingDao
import com.coati.checador.core.database.entity.AppSettingEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appSettingDao: AppSettingDao
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        loadSettings()
    }

    fun loadSettings() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, successMessage = null, errorMessage = null) }
            runCatching {
                SettingsUiState(
                    isLoading = false,
                    apiBaseUrl = appSettingDao.getValue(AppSettingEntity.KEY_API_BASE_URL).orEmpty(),
                    gpsTimeoutMs = appSettingDao.getValue(AppSettingEntity.KEY_GPS_TIMEOUT_MS) ?: "10000",
                    faceMatchThreshold = appSettingDao.getValue(AppSettingEntity.KEY_FACE_MATCH_THRESHOLD) ?: "0.4",
                    adminPin = "",
                    successMessage = null,
                    errorMessage = null
                )
            }.onSuccess { loaded ->
                _state.value = loaded
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "No se pudo cargar la configuración"
                    )
                }
            }
        }
    }

    fun updateApiBaseUrl(value: String) {
        _state.update { it.copy(apiBaseUrl = value, successMessage = null, errorMessage = null) }
    }

    fun updateGpsTimeout(value: String) {
        _state.update { it.copy(gpsTimeoutMs = value, successMessage = null, errorMessage = null) }
    }

    fun updateFaceThreshold(value: String) {
        _state.update { it.copy(faceMatchThreshold = value, successMessage = null, errorMessage = null) }
    }

    fun updateAdminPin(value: String) {
        _state.update { it.copy(adminPin = value, successMessage = null, errorMessage = null) }
    }

    fun saveSettings() {
        val current = _state.value
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, successMessage = null, errorMessage = null) }
            val now = System.currentTimeMillis()
            runCatching {
                appSettingDao.upsert(
                    AppSettingEntity(
                        key = AppSettingEntity.KEY_API_BASE_URL,
                        value = current.apiBaseUrl.ifBlank { null },
                        updatedAt = now
                    )
                )
                appSettingDao.upsert(
                    AppSettingEntity(
                        key = AppSettingEntity.KEY_GPS_TIMEOUT_MS,
                        value = current.gpsTimeoutMs.ifBlank { "10000" },
                        updatedAt = now
                    )
                )
                appSettingDao.upsert(
                    AppSettingEntity(
                        key = AppSettingEntity.KEY_FACE_MATCH_THRESHOLD,
                        value = current.faceMatchThreshold.ifBlank { "0.4" },
                        updatedAt = now
                    )
                )
                if (current.adminPin.isNotBlank()) {
                    appSettingDao.upsert(
                        AppSettingEntity(
                            key = AppSettingEntity.KEY_ADMIN_PIN_HASH,
                            value = current.adminPin,
                            updatedAt = now
                        )
                    )
                }
            }.onSuccess {
                _state.update {
                    it.copy(
                        isSaving = false,
                        successMessage = "Configuración guardada localmente",
                        errorMessage = null
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isSaving = false,
                        successMessage = null,
                        errorMessage = error.message ?: "No se pudo guardar la configuración"
                    )
                }
            }
        }
    }
}

data class SettingsUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val apiBaseUrl: String = "",
    val gpsTimeoutMs: String = "10000",
    val faceMatchThreshold: String = "0.4",
    val adminPin: String = "",
    val successMessage: String? = null,
    val errorMessage: String? = null
)