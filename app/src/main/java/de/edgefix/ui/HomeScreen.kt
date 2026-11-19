package de.edgefix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import de.edgefix.overlay.OverlayPermission
import de.edgefix.overlay.OverlayService
import de.edgefix.store.SettingsRepository
import java.text.DateFormat
import java.util.Date

private const val THREE_MONTHS_MS = 90L * 24 * 60 * 60 * 1000

@Composable
fun HomeScreen(repo: SettingsRepository, onTuning: () -> Unit, onCalibrate: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val settings by repo.settings.collectAsStateWithLifecycle()
    val running by OverlayService.running.collectAsStateWithLifecycle()
    val hasPermission = remember(settings) { OverlayPermission.granted(context) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        ScreenTitle("EdgeFix", "Gleicht lila Bildränder aus")

        // Statuskarte
        val shape = RoundedCornerShape(18.dp)
        Surface(
            onClick = {
                repo.update { it.copy(enabled = !it.enabled) }
                OverlayService.sync(context)
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
                focusedContentColor = MaterialTheme.colorScheme.onSurface,
            ),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Accent), shape = shape)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        ) {
            Row(Modifier.padding(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val active = settings.enabled && running
                    Text(
                        when {
                            !hasPermission -> "Berechtigung fehlt"
                            active -> "Korrektur aktiv"
                            settings.enabled -> "Korrektur startet …"
                            else -> "Korrektur aus"
                        },
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when {
                            !hasPermission -> "Unter Einstellungen → Berechtigung einrichten"
                            settings.useMap && settings.lastCalibrated != 0L -> "Gemessene Korrektur vom " +
                                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(settings.lastCalibrated))
                            settings.lastTuned == 0L -> "Noch nicht abgestimmt. Starte mit der Kalibrierung."
                            else -> "Einfache Randmaske, zuletzt abgestimmt: " +
                                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(settings.lastTuned))
                        },
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (settings.useMap && settings.lastCalibrated != 0L &&
                        System.currentTimeMillis() - settings.lastCalibrated > THREE_MONTHS_MS
                    ) {
                        Text("Neu messen empfohlen", fontSize = 22.sp, color = Accent)
                    }
                }
                Spacer(Modifier.width(24.dp))
                Switch(checked = settings.enabled, onCheckedChange = null, modifier = Modifier.size(width = 64.dp, height = 36.dp))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxWidth()) {
            Tile("Mit Handy kalibrieren", "Rand messen und genau ausgleichen", Modifier.weight(1f), onClick = onCalibrate)
            Tile("Feinabstimmung", "Stärke und Farbe am Fernseher einstellen", Modifier.weight(1f), onClick = onTuning)
            Tile("Einstellungen", "Autostart, Berechtigung, Zurücksetzen", Modifier.weight(1f), onClick = onSettings)
        }

        Spacer(Modifier.weight(1f))
        KeyHints("OK auf der Statuskarte: Korrektur ein/aus · ◀ ▶ ▲ ▼ wählen")
    }
}

@Composable
private fun Tile(
    title: String,
    sub: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(170.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Accent), shape = shape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

