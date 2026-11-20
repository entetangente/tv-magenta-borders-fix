package de.edgefix.overlay

import android.content.Context
import android.provider.Settings

object OverlayPermission {
    /**
     * „Über anderen Apps anzeigen“. Fire OS bietet dafür keinen funktionierenden
     * Einstellungsdialog; die Berechtigung kommt per ADB (appops).
     */
    fun granted(context: Context): Boolean = Settings.canDrawOverlays(context)
}
