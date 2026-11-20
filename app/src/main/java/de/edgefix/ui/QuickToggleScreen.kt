package de.edgefix.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import de.edgefix.quick.QuickToggleService

/** Anleitung zum Einschalten der Schnellumschaltung (Bedienungshilfe). */
@Composable
fun QuickToggleScreen() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var enabled by remember { mutableStateOf(QuickToggleService.isEnabled(context)) }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { enabled = QuickToggleService.isEnabled(context) }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val component = QuickToggleService.component(context).flattenToString()

    Column(
        Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("Schnellumschaltung", "Menü-Taste (≡) lange drücken: Korrektur ein/aus, auch während eines Films.")
        Text(
            if (enabled) "✓  Eingerichtet. Probier es aus: Menü-Taste eine Sekunde gedrückt halten."
            else "Noch nicht eingerichtet.",
            fontSize = 24.sp,
            color = if (enabled) Accent else androidx.compose.ui.graphics.Color(0xFFF0B35A),
        )
        if (!enabled) {
            Text(
                "Weg 1: In den Fire-TV-Einstellungen unter Bedienungshilfen „EdgeFix Schnellumschaltung“ einschalten, " +
                    "falls Fire OS sie dort anzeigt.",
                fontSize = 21.sp,
            )
            PcCommands(
                "settings put secure enabled_accessibility_services $component",
                "settings put secure accessibility_enabled 1",
                intro = "Weg 2, falls sie dort fehlt: mit dem PC (ersetzt andere aktive Bedienungshilfen):",
            )
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BigButton(
                "Bedienungshilfen öffnen",
                {
                    try {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: ActivityNotFoundException) {
                    }
                },
                Modifier.focusRequester(focus),
                primary = !enabled,
            )
        }
        KeyHints("Zurück: Einstellungen")
    }
}
