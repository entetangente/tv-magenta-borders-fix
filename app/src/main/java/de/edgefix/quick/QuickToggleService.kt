package de.edgefix.quick

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import de.edgefix.overlay.OverlayService
import de.edgefix.store.SettingsRepository

/**
 * Schnellumschaltung: Langes Drücken der Menü-Taste (≡) schaltet die Korrektur
 * ein und aus, ohne die laufende App zu verlassen. Kurze Drücke gehen
 * unverändert an die App.
 */
class QuickToggleService : AccessibilityService() {

    private var downAt = 0L
    private var handled = false

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_MENU) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    downAt = event.eventTime
                    handled = false
                    return false
                }
                if (!handled && event.eventTime - downAt >= LONG_PRESS_MS) {
                    handled = true
                    toggle()
                }
                // Nach dem Umschalten weitere Wiederholungen nicht an die App geben
                return handled
            }
            KeyEvent.ACTION_UP -> {
                val consume = handled
                handled = false
                return consume
            }
        }
        return false
    }

    private fun toggle() {
        val repo = SettingsRepository.get(this)
        repo.update { it.copy(enabled = !it.enabled) }
        OverlayService.sync(this)
        val s = repo.settings.value
        Toast.makeText(
            this,
            if (s.enabled) "EdgeFix: Korrektur an (${s.profile.label})" else "EdgeFix: Korrektur aus",
            Toast.LENGTH_SHORT,
        ).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        private const val LONG_PRESS_MS = 700L

        fun component(context: Context) = ComponentName(context, QuickToggleService::class.java)

        fun isEnabled(context: Context): Boolean {
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = component(context)
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
