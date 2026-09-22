package com.arhan.frugalcctv.domain

import kotlinx.serialization.Serializable

@Serializable
data class PresenceState(val clientId: String, val role: String)

@Serializable
data class SignalMessage(
    val type: String,
    val from: String,
    val to: String? = null,
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val armed: Boolean? = null,
    val text: String? = null
)

data class IceConfig(val turnUrls: List<String> = emptyList(), val username: String = "", val password: String = "")
data class SecuritySettings(val armed: Boolean = false, val confidenceThreshold: Float = 0.42f, val audibleAlarm: Boolean = false)
data class SecurityEvent(val message: String, val severity: Severity = Severity.WARNING)
enum class Severity { INFO, WARNING, CRITICAL }
enum class AppMode { HOME, CAMERA_SETUP, CAMERA, VIEWER_SETUP, VIEWER }
