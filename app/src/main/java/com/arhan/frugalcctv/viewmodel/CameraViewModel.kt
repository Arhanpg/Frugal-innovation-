package com.arhan.frugalcctv.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.arhan.frugalcctv.data.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = AppPreferences(app)
    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    fun bootstrap() {
        _state.value = CameraUiState(
            cameraHost = prefs.cameraHost(),
            cameraPort = prefs.cameraPort(),
            armed = prefs.armed(),
            audible = prefs.audible()
        )
    }

    fun configureEndpoint(host: String, port: Int) {
        prefs.saveCameraEndpoint(host, port)
        _state.value = _state.value.copy(cameraHost = host, cameraPort = port, error = null)
    }

    fun toggleArmed() {
        val next = !_state.value.armed
        prefs.saveArmed(next)
        _state.value = _state.value.copy(armed = next)
    }

    fun toggleAudible() {
        val next = !_state.value.audible
        prefs.saveAudible(next)
        _state.value = _state.value.copy(audible = next)
    }
}

data class CameraUiState(
    val cameraHost: String = "",
    val cameraPort: Int = 47678,
    val armed: Boolean = true,
    val audible: Boolean = false,
    val error: String? = null
)
