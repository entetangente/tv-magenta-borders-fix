package de.edgefix.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.edgefix.store.SettingsRepository

/** Startet den Dienst nach dem Einschalten und nach einem App-Update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                val s = SettingsRepository.get(context).settings.value
                if (s.autostart && s.setupDone) OverlayService.sync(context)
            }
        }
    }
}
