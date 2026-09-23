package com.arhan.frugalcctv.data

import android.content.Context
import java.util.UUID

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("frugal_cctv", Context.MODE_PRIVATE)
    fun cameraHost(default: String = "") = prefs.getString("camera_host", default).orEmpty()
    fun cameraPort(default: Int = 47678) = prefs.getInt("camera_port", default)
    fun saveCameraEndpoint(host: String, port: Int) = prefs.edit().putString("camera_host", host.trim()).putInt("camera_port", port).apply()
    fun saveArmed(armed: Boolean) = prefs.edit().putBoolean("armed", armed).apply()
    fun armed() = prefs.getBoolean("armed", true)
    fun saveAudible(value: Boolean) = prefs.edit().putBoolean("audible", value).apply()
    fun audible() = prefs.getBoolean("audible", false)
    fun deviceId(): String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }
}