package de.edgefix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.edgefix.BuildConfig
import de.edgefix.core.EdgeParams
import de.edgefix.overlay.OverlayPermission
import de.edgefix.overlay.OverlayService
import de.edgefix.store.SettingsRepository

@Composable
fun SettingsScreen(repo: SettingsRepository, onPermission: () -> Unit, onRestartSetup: () -> Unit) {
    val context = LocalContext.current
    val settings by repo.settings.collectAsStateWithLifecycle()
    val granted = remember { OverlayPermission.granted(context) }
    var confirmReset by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenTitle("Einstellungen")
        LazyColumn(Modifier.width(820.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                ToggleRow(
                    "Autostart",
                    settings.autostart,
                    { repo.update { it.copy(autostart = !it.autostart) } },
                    description = "Korrektur beim Einschalten des Fire TV starten",
                    modifier = Modifier.focusRequester(focus),
                )
            }
            item {
                ActionRow(
                    if (granted) "Berechtigung: eingerichtet ✓" else "Berechtigung einrichten",
                    onPermission,
                    description = "Über anderen Apps anzeigen",
                )
            }
            item {
                ActionRow(
                    if (confirmReset) "Wirklich zurücksetzen? OK zum Bestätigen" else "Abstimmung zurücksetzen",
                    {
                        if (confirmReset) {
                            repo.update { it.copy(params = EdgeParams(), lastTuned = 0L) }
                            OverlayService.sync(context)
                            confirmReset = false
                        } else {
                            confirmReset = true
                        }
                    },
                    description = "Alle Werte der Feinabstimmung auf Startwerte",
                )
            }
            item { ActionRow("Ersteinrichtung erneut starten", onRestartSetup) }
            item {
                ActionRow("Version ${BuildConfig.VERSION_NAME}", {}, description = "Profile (SDR/HDR) folgen in einer späteren Version")
            }
        }
        KeyHints("OK: auswählen · Zurück: Startbildschirm")
    }
}
