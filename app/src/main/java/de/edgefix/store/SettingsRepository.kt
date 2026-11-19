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

    /** Zustand während der Kalibrierung mit dem Handy, sonst null. Wird nicht gespeichert. */
    private val _calibration = MutableStateFlow<CalibrationState?>(null)
    val calibration: StateFlow<CalibrationState?> = _calibration.asStateFlow()

    fun updateCalibration(transform: (CalibrationState?) -> CalibrationState?) {
        _calibration.update(transform)
    }

    fun update(transform: (Settings) -> Settings) {
        _settings.update(transform)
        prefs.edit().putString(KEY, SettingsJson.encode(_settings.value)).apply()
    }

    /** Diagnose: knallroten Testrahmen statt der Maske zeigen. Wird nicht gespeichert. */
    private val _testFrame = MutableStateFlow(false)
    val testFrame: StateFlow<Boolean> = _testFrame.asStateFlow()

    fun setTestFrame(on: Boolean) {
        _testFrame.value = on
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

/** Was das Overlay während der Kalibrierung zeigt. */
enum class CalibrationOverlay {
    /** gar nichts (Rohmessung) */
    OFF,
    /** die gerade berechnete, noch nicht übernommene Karte */
    PREVIEW,
    /** die normale, gespeicherte Korrektur */
    ACTIVE,
}

data class CalibrationState(
    val overlay: CalibrationOverlay = CalibrationOverlay.ACTIVE,
    /** Grauwert des Messbilds (0–1) oder null = kein Messbild */
    val level: Float? = null,
    val hint: String? = null,
    val phoneConnected: Boolean = false,
    val stepText: String? = null,
    val previewBytes: ByteArray? = null,
    val previewMap: de.edgefix.core.CorrectionMap? = null,
    val previewStrength: Float = 1f,
    val previewVariant: Int = 1,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
