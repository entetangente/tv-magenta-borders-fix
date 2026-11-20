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
import androidx.compose.runtime.mutableIntStateOf
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
import de.edgefix.quick.QuickToggleService
import de.edgefix.store.MapStore
import de.edgefix.store.ProfileId
import de.edgefix.store.SettingsRepository

@Composable
fun SettingsScreen(
    repo: SettingsRepository,
    onPermission: () -> Unit,
    onRestartSetup: () -> Unit,
    onDiagnostics: () -> Unit,
    onQuickToggle: () -> Unit,
) {
    val context = LocalContext.current
    val settings by repo.settings.collectAsStateWithLifecycle()
    val prof = settings.current
    val granted = remember { OverlayPermission.granted(context) }
    val quickOn = remember { QuickToggleService.isEnabled(context) }
    val store = remember { MapStore(context) }
    var mapsVersion by remember { mutableIntStateOf(0) }
    val maps = remember(mapsVersion) { store.list() }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenTitle("Einstellungen")
        LazyColumn(Modifier.width(820.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                ProfileRow(repo, Modifier.focusRequester(focus))
            }
            item {
                ToggleRow(
                    "Autostart",
                    settings.autostart,
                    { repo.update { it.copy(autostart = !it.autostart) } },
                    description = "Korrektur beim Einschalten des Fire TV starten",
                )
            }
            item {
                ActionRow(
                    if (quickOn) "Schnellumschaltung: eingerichtet ✓" else "Schnellumschaltung einrichten",
                    onQuickToggle,
                    description = "Menü-Taste lange drücken: Korrektur ein/aus, ohne die App zu verlassen",
                )
            }

            // Kalibrierungen (gelten für das aktive Profil)
            if (maps.isNotEmpty()) {
                item {
                    ChoiceRow(
                        "Kalibrierung (${settings.profile.label})",
                        maps.map { it.id },
                        prof.activeMapId?.takeIf { id -> maps.any { it.id == id } } ?: maps.first().id,
                        { id -> maps.firstOrNull { it.id == id }?.label ?: id },
                        { id -> repo.update { s -> s.withCurrent { it.copy(activeMapId = id, useMap = true) } } },
                    )
                }
                item {
                    ToggleRow(
                        "Gemessene Korrektur verwenden",
                        prof.useMap,
                        { repo.update { s -> s.withCurrent { it.copy(useMap = !it.useMap) } } },
                        description = "Aus: Randmaske aus Feinabstimmung bzw. Abgleich per Auge",
                    )
                }
                item {
                    val id = prof.activeMapId?.takeIf { i -> maps.any { it.id == i } }
                    ActionRow(
                        if (confirmDelete) "Wirklich löschen? OK zum Bestätigen" else "Gewählte Kalibrierung löschen",
                        {
                            if (id == null) return@ActionRow
                            if (!confirmDelete) { confirmDelete = true; return@ActionRow }
                            store.delete(id)
                            // Karte in allen Profilen abmelden, die sie verwenden
                            repo.update { s ->
                                s.copy(profiles = s.profiles.mapValues { (_, p) ->
                                    if (p.activeMapId == id) p.copy(activeMapId = null, useMap = false) else p
                                })
                            }
                            confirmDelete = false
                            mapsVersion++
                        },
                        enabled = id != null,
                        description = maps.firstOrNull { it.id == id }?.label,
                    )
                }
            }
            item {
                val other = if (settings.profile == ProfileId.SDR) ProfileId.HDR else ProfileId.SDR
                ActionRow(
                    "Werte nach ${other.label} kopieren",
                    { repo.update { it.copy(profiles = it.profiles + (other to it.current)) } },
                    description = "Ausgangspunkt für die Abstimmung des anderen Profils",
                )
            }
            item {
                ActionRow(
                    if (confirmReset) "Wirklich zurücksetzen? OK zum Bestätigen" else "Abstimmung zurücksetzen (${settings.profile.label})",
                    {
                        if (confirmReset) {
                            repo.update { s ->
                                s.withCurrent { it.copy(params = EdgeParams(), lastTuned = 0L, mapStrength = 1f, mapVariant = 1) }
                            }
                            OverlayService.sync(context)
                            confirmReset = false
                        } else {
                            confirmReset = true
                        }
                    },
                    description = "Feinabstimmung und Abgleich per Auge auf Startwerte",
                )
            }
            item {
                ActionRow(
                    if (granted) "Berechtigung: eingerichtet ✓" else "Berechtigung einrichten",
                    onPermission,
                    description = "Über anderen Apps anzeigen",
                )
            }
            item { ActionRow("Diagnose", onDiagnostics, description = "Prüfen, ob das Overlay angezeigt wird") }
            item { ActionRow("Ersteinrichtung erneut starten", onRestartSetup) }
            item { ActionRow("Version ${BuildConfig.VERSION_NAME}", {}) }
        }
        KeyHints("OK: auswählen · ◀ ▶ umschalten · Zurück: Startbildschirm")
    }
}

/** Profil SDR / HDR / Aus. „Aus“ pausiert das Overlay, die App läuft weiter. */
@Composable
fun ProfileRow(repo: SettingsRepository, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings by repo.settings.collectAsStateWithLifecycle()
    val options = ProfileId.entries.map { it as ProfileId? } + listOf(null)
    ChoiceRow(
        "Profil",
        options,
        if (settings.enabled) settings.profile else null,
        { it?.label ?: "Aus" },
        { choice ->
            repo.update { s -> if (choice == null) s.copy(enabled = false) else s.copy(enabled = true, profile = choice) }
            OverlayService.sync(context)
        },
        modifier = modifier,
    )
}
