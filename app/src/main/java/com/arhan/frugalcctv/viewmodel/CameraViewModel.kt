package com.arhan.frugalcctv.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.arhan.frugalcctv.data.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CameraUiState(
    val roomCode: String = "HOME-4827",
    val cameraHost: String = "",
    val cameraPort: Int = 47678,
    val armed: Boolean = true,
    val audible: Boolean = false,
    val torchOn: Boolean = false,
    val sirenOn: Boolean = false,
    val isFrontCamera: Boolean = false,
    val localIp: String = "127.0.0.1",
    val error: String? = null
)

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = AppPreferences(app)
    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    init {
        bootstrap()
    }

    fun bootstrap() {
        _state.value = _state.value.copy(
            cameraHost = prefs.cameraHost(),
            cameraPort = prefs.cameraPort(),
            armed = prefs.armed(),
            audible = prefs.audible()
        )
    }

    fun setRoomCode(code: String) {
        _state.value = _state.value.copy(roomCode = code.uppercase().trim())
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

    fun setTorch(on: Boolean) {
        _state.value = _state.value.copy(torchOn = on)
    }

    fun setSiren(on: Boolean) {
        _state.value = _state.value.copy(sirenOn = on)
    }

    fun setFrontCamera(isFront: Boolean) {
        _state.value = _state.value.copy(isFrontCamera = isFront)
    }

    fun setLocalIp(ip: String) {
        _state.value = _state.value.copy(localIp = ip)
    }
}
