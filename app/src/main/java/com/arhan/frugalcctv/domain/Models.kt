package com.arhan.frugalcctv.domain

import kotlinx.serialization.Serializable
import java.util.UUID

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
    val text: String? = null,
    val torch: Boolean? = null,
    val flipCamera: Boolean? = null,
    val siren: Boolean? = null,
    val battery: Int? = null,
    val charging: Boolean? = null,
    val ipAddress: String? = null,
    val timestamp: Long? = null
)

data class IceConfig(
    val turnUrls: List<String> = emptyList(),
    val username: String = "",
    val password: String = ""
)

data class SecuritySettings(
    val armed: Boolean = true,
    val confidenceThreshold: Float = 0.40f,
    val audibleAlarm: Boolean = false
)

data class SecurityEvent(
    val message: String,
    val severity: Severity = Severity.WARNING
)

data class SecurityEventLog(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val event: SecurityEvent
)

data class CameraHealth(
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val ipAddress: String = "127.0.0.1",
    val isArmed: Boolean = true,
    val torchOn: Boolean = false,
    val sirenOn: Boolean = false
)

enum class Severity { INFO, WARNING, CRITICAL }
enum class AppMode { HOME, CAMERA_SETUP, CAMERA, VIEWER_SETUP, VIEWER }
