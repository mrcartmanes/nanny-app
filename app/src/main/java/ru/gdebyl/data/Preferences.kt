package ru.gdebyl.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(val recording: Boolean = false, val radius: Int = 50, val minutes: Int = 5, val onboarded: Boolean = false, val error: String? = null)
class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun read() = Settings(prefs.getBoolean("recording", false), prefs.getInt("radius", 50), prefs.getInt("minutes", 5), prefs.getBoolean("onboarded", false), prefs.getString("error", null))
    private val mutable = MutableStateFlow(read())
    val flow = mutable.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> mutable.value = read() }
    init { prefs.registerOnSharedPreferenceChangeListener(listener) }
    val value get() = read()
    fun recording(value: Boolean) { prefs.edit().putBoolean("recording", value).commit() }
    fun configure(radius: Int, minutes: Int) { prefs.edit().putInt("radius", radius).putInt("minutes", minutes).apply() }
    fun onboarded() { prefs.edit().putBoolean("onboarded", true).apply() }
    fun error(message: String?) { prefs.edit().putString("error", message).apply() }
}
