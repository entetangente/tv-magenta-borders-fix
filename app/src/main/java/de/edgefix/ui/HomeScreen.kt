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
    val prof = settings.current
    val running by OverlayService.running.collectAsStateWithLifecycle()
    val hasPermission = remember(settings) { OverlayPermission.granted(context) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ScreenTitle("EdgeFix")

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
            Row(Modifier.padding(horizontal = 28.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
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
                            prof.mapActive && prof.lastCalibrated != 0L -> "Profil ${settings.profile.label} · gemessene Korrektur vom " +
                                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(prof.lastCalibrated))
                            prof.darken -> "Profil ${settings.profile.label} · Rand nur abdunkeln (${(prof.darkenStrength * 100).toInt()} %)"
                            prof.lastTuned == 0L -> "Profil ${settings.profile.label} · noch nicht abgestimmt"
                            else -> "Profil ${settings.profile.label} · Randmaske, abgestimmt am " +
                                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(prof.lastTuned))
                        },
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (prof.mapActive && prof.lastCalibrated != 0L &&
                        System.currentTimeMillis() - prof.lastCalibrated > THREE_MONTHS_MS
                    ) {
                        Text("Neu messen empfohlen", fontSize = 22.sp, color = Accent)
                    }
                }
                Spacer(Modifier.width(24.dp))
                Switch(checked = settings.enabled, onCheckedChange = null, modifier = Modifier.size(width = 64.dp, height = 36.dp))
            }
        }

        ProfileRow(repo)

        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxWidth()) {
            Tile("Kalibrieren", "Rand mit dem Handy vermessen", Modifier.weight(1f), onClick = onCalibrate)
            Tile("Feinabstimmung", "Stärke, Farbe, Abgleich per Auge", Modifier.weight(1f), onClick = onTuning)
            Tile("Einstellungen", "Autostart, Kalibrierungen, Hilfe", Modifier.weight(1f), onClick = onSettings)
        }

        Spacer(Modifier.weight(1f))
        KeyHints("OK auf der Statuskarte: Korrektur ein/aus · Profil mit ◀ ▶ wechseln")
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
        modifier = modifier.height(130.dp),
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
        Column(Modifier.padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(sub, fontSize = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

