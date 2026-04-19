package dev.bmg.edgeclip.data

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("edgeclip_settings", Context.MODE_PRIVATE)

    var isBackgroundPollingEnabled: Boolean
        get() = prefs.getBoolean(KEY_BG_POLLING, true)
        set(value) = prefs.edit().putBoolean(KEY_BG_POLLING, value).apply()

    var pollingFrequencySeconds: Int
        get() = prefs.getInt(KEY_POLLING_FREQ, 5)
        set(value) = prefs.edit().putInt(KEY_POLLING_FREQ, value).apply()

    var edgeSide: String
        get() = prefs.getString(KEY_EDGE_SIDE, "right") ?: "right"
        set(value) = prefs.edit().putString(KEY_EDGE_SIDE, value).apply()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val KEY_BG_POLLING = "background_polling"
        const val KEY_POLLING_FREQ = "polling_frequency"
        const val KEY_EDGE_SIDE = "edge_side"
    }
}
