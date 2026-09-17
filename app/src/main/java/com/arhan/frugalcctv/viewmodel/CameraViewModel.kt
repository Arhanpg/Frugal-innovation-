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

    fun configure(url: String, key: String) {
        val cleanUrl = url.trim().trimEnd('/')
        val cleanKey = key.trim()
        prefs.saveConnection(cleanUrl, cleanKey)
        _state.value = _state.value.copy(configured = cleanUrl.isNotBlank() && cleanKey.isNotBlank(), error = null)
    }

    fun bootstrap() {
        _state.value = _state.value.copy(
            configured = prefs.supabaseUrl().isNotBlank() && prefs.supabaseKey().isNotBlank(),
            armed = prefs.armed(),
            audible = prefs.audible()
        )
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
    val configured: Boolean = false,
    val armed: Boolean = false,
    val audible: Boolean = false,
    val error: String? = null
)
