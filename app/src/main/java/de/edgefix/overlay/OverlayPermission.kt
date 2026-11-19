package de.edgefix.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object OverlayPermission {
    fun granted(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Systemdialog für „Über anderen Apps anzeigen“, falls Fire OS ihn anbietet. */
    fun systemDialogIntent(context: Context): Intent? {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
        return if (i.resolveActivity(context.packageManager) != null) i else null
    }
}
