package com.solarsync.pro.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TemperatureUnit { CELSIUS, FAHRENHEIT }

data class SettingsState(
    val pollingIntervalSeconds: Int = 2,
    val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    val solisCloudKeyId: String = "",
    val solisCloudKeySecret: String = "",
    val solisInverterSerial: String = ""
)

/**
 * Holds user-configurable settings — polling interval, display units, and
 * the SolisCloud fallback credentials — and persists them to
 * SharedPreferences so they survive app restarts. No extra dependency
 * needed since SharedPreferences ships with the platform.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("solarsync_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadFromPrefs())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private fun loadFromPrefs(): SettingsState = SettingsState(
        pollingIntervalSeconds = prefs.getInt(KEY_POLLING_INTERVAL, 2),
        temperatureUnit = if (prefs.getString(KEY_TEMP_UNIT, "C") == "F") {
            TemperatureUnit.FAHRENHEIT
        } else {
            TemperatureUnit.CELSIUS
        },
        solisCloudKeyId = prefs.getString(KEY_SOLIS_KEY_ID, "") ?: "",
        solisCloudKeySecret = prefs.getString(KEY_SOLIS_KEY_SECRET, "") ?: "",
        solisInverterSerial = prefs.getString(KEY_SOLIS_SERIAL, "") ?: ""
    )

    fun updatePollingInterval(seconds: Int) {
        _state.value = _state.value.copy(pollingIntervalSeconds = seconds.coerceIn(1, 60))
    }

    fun updateTemperatureUnit(unit: TemperatureUnit) {
        _state.value = _state.value.copy(temperatureUnit = unit)
    }

    fun updateSolisCredentials(keyId: String, keySecret: String, serial: String) {
        _state.value = _state.value.copy(
            solisCloudKeyId = keyId,
            solisCloudKeySecret = keySecret,
            solisInverterSerial = serial
        )
    }

    /** Writes the current in-memory state to SharedPreferences. */
    fun save() {
        val current = _state.value
        prefs.edit()
            .putInt(KEY_POLLING_INTERVAL, current.pollingIntervalSeconds)
            .putString(KEY_TEMP_UNIT, if (current.temperatureUnit == TemperatureUnit.FAHRENHEIT) "F" else "C")
            .putString(KEY_SOLIS_KEY_ID, current.solisCloudKeyId)
            .putString(KEY_SOLIS_KEY_SECRET, current.solisCloudKeySecret)
            .putString(KEY_SOLIS_SERIAL, current.solisInverterSerial)
            .apply()
    }

    companion object {
        private const val KEY_POLLING_INTERVAL = "polling_interval_seconds"
        private const val KEY_TEMP_UNIT = "temperature_unit"
        private const val KEY_SOLIS_KEY_ID = "solis_key_id"
        private const val KEY_SOLIS_KEY_SECRET = "solis_key_secret"
        private const val KEY_SOLIS_SERIAL = "solis_serial"
    }
}
