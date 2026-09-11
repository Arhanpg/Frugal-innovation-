package com.arhan.frugalcctv.data

import android.content.Context

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("frugal_cctv", Context.MODE_PRIVATE)
    fun supabaseUrl(default: String = "") = prefs.getString("supabase_url", default).orEmpty()
    fun supabaseKey(default: String = "") = prefs.getString("supabase_key", default).orEmpty()
    fun turnUrls() = prefs.getString("turn_urls", "").orEmpty().split(',').map(String::trim).filter(String::isNotBlank)
    fun turnUser() = prefs.getString("turn_user", "").orEmpty()
    fun turnPassword() = prefs.getString("turn_password", "").orEmpty()
    fun saveConnection(url: String, key: String) = prefs.edit().putString("supabase_url", url.trimEnd('/')).putString("supabase_key", key.trim()).apply()
    fun saveTurn(urls: String, username: String, password: String) = prefs.edit().putString("turn_urls", urls).putString("turn_user", username).putString("turn_password", password).apply()
    fun saveArmed(armed: Boolean) = prefs.edit().putBoolean("armed", armed).apply()
    fun armed() = prefs.getBoolean("armed", false)
    fun saveAudible(value: Boolean) = prefs.edit().putBoolean("audible", value).apply()
    fun audible() = prefs.getBoolean("audible", false)
}
