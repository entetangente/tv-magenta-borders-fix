package de.edgefix.store

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Gemeinsamer Zustand für Dienst und Oberfläche. Jede Änderung wird sofort als
 * JSON in den SharedPreferences gespeichert.
 */
class SettingsRepository private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("edgefix", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(SettingsJson.decode(prefs.getString(KEY, null)))
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    /** Vorübergehend ausblenden (Vorher/Nachher-Vergleich). Wird nicht gespeichert. */
    private val _bypass = MutableStateFlow(false)
    val bypass: StateFlow<Boolean> = _bypass.asStateFlow()

    fun update(transform: (Settings) -> Settings) {
        _settings.update(transform)
        prefs.edit().putString(KEY, SettingsJson.encode(_settings.value)).apply()
    }

    fun setBypass(on: Boolean) {
        _bypass.value = on
    }

    companion object {
        private const val KEY = "settings"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
    }
}
