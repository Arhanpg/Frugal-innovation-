package com.arhan.frugalcctv.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.arhan.frugalcctv.domain.CameraHealth
import com.arhan.frugalcctv.domain.SecurityEvent
import com.arhan.frugalcctv.domain.SecurityEventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ViewerUiState(
    val connectionState: String = "Searching local network…",
    val roomCode: String = "HOME-4827",
    val manualIp: String = "",
    val cameraHealth: CameraHealth = CameraHealth(),
    val eventLogs: List<SecurityEventLog> = emptyList(),
    val torchOn: Boolean = false,
    val sirenOn: Boolean = false,
    val armed: Boolean = true,
    val error: String? = null
)

class ViewerViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    fun updateConnectionState(status: String) {
        _state.value = _state.value.copy(connectionState = status)
    }

    fun setRoomCode(code: String) {
        _state.value = _state.value.copy(roomCode = code.uppercase().trim())
    }

    fun setManualIp(ip: String) {
        _state.value = _state.value.copy(manualIp = ip.trim())
    }

    fun updateCameraHealth(health: CameraHealth) {
        _state.value = _state.value.copy(
            cameraHealth = health,
            armed = health.isArmed,
            torchOn = health.torchOn,
            sirenOn = health.sirenOn
        )
    }

    fun addSecurityEvent(event: SecurityEvent) {
        val newLog = SecurityEventLog(event = event)
        val updated = (listOf(newLog) + _state.value.eventLogs).take(50)
        _state.value = _state.value.copy(eventLogs = updated)
    }

    fun clearEventLogs() {
        _state.value = _state.value.copy(eventLogs = emptyList())
    }

    fun toggleArmed() {
        _state.value = _state.value.copy(armed = !_state.value.armed)
    }

    fun toggleTorch() {
        _state.value = _state.value.copy(torchOn = !_state.value.torchOn)
    }

    fun toggleSiren() {
        _state.value = _state.value.copy(sirenOn = !_state.value.sirenOn)
    }
}
