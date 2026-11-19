package de.edgefix.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.edgefix.BuildConfig
import de.edgefix.overlay.OverlayPermission
import de.edgefix.overlay.OverlayService
import de.edgefix.store.SettingsRepository

/** Zeigt, ob Berechtigung, Dienst, Overlay-Fenster und Maske funktionieren. */
@Composable
fun DiagnosticsScreen(repo: SettingsRepository) {
    val context = LocalContext.current
    val d by OverlayService.diagnostics.collectAsStateWithLifecycle()
    val running by OverlayService.running.collectAsStateWithLifecycle()
    val settings by repo.settings.collectAsStateWithLifecycle()
    val testFrame by repo.testFrame.collectAsStateWithLifecycle()
    val permission = remember(d, running) { OverlayPermission.granted(context) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    DisposableEffect(Unit) {
        onDispose {
            repo.setTestFrame(false)
            OverlayService.sync(context)
        }
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ScreenTitle("Diagnose")
            ToggleRow(
                "Roten Testrahmen zeigen",
                testFrame,
                {
                    repo.setTestFrame(!testFrame)
                    OverlayService.sync(context)
                },
                description = "Muss sofort als roter Rand um das ganze Bild erscheinen",
                modifier = Modifier.focusRequester(focus),
            )
            ActionRow("Dienst neu starten", {
                context.stopService(android.content.Intent(context, OverlayService::class.java))
                OverlayService.sync(context)
            })
            KeyHints("Zurück: Einstellungen")
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            fun line(label: String, value: String, ok: Boolean? = null) = item {
                Row {
                    Text(label, fontSize = 20.sp, modifier = Modifier.width(260.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        value,
                        fontSize = 20.sp,
                        color = when (ok) {
                            true -> Accent
                            false -> androidx.compose.ui.graphics.Color(0xFFFF7A6B)
                            null -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
            line("App-Version", BuildConfig.VERSION_NAME)
            line("Android / Gerät", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MODEL}")
            line("Overlay-Berechtigung", if (permission) "ja" else "NEIN", permission)
            line("Korrektur eingeschaltet", if (settings.enabled) "ja" else "nein", settings.enabled)
            line("Dienst läuft", if (running) "ja" else "nein", running)
            line("Dienst-Starts", d.serviceStarted.toString())
            line("Overlay-Fenster", if (d.windowAdded) "angelegt" else "nicht angelegt", d.windowAdded)
            d.windowError?.let { line("Fehler Fenster", it, false) }
            line("Fenstergröße", d.size ?: "–", d.size != null)
            line("Maskenquelle", d.source ?: "–")
            line("Pixel mit Korrektur", "${d.nonZeroPixels}", d.nonZeroPixels > 0)
            line("Größte Deckkraft", "${d.maxAlpha}/255 (${d.maxAlpha * 100 / 255} %)")
            line("Rechenzeit", "${d.renderMs} ms")
            item { Text("Verlauf", fontSize = 20.sp, modifier = Modifier.padding(top = 10.dp)) }
            for (l in d.log) item { Text(l, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
