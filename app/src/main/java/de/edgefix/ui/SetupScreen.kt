package de.edgefix.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.edgefix.adb.AdbSelfGrant
import de.edgefix.overlay.OverlayPermission
import de.edgefix.store.SettingsRepository
import kotlinx.coroutines.launch

private const val STEPS = 4

@Composable
fun SetupScreen(
    repo: SettingsRepository,
    startStep: Int,
    onDone: () -> Unit,
    onExit: (() -> Unit)?,
) {
    var step by rememberSaveable { mutableStateOf(startStep) }
    BackHandler(enabled = step > 0 || onExit != null) {
        if (step > 0 && step != startStep) step-- else onExit?.invoke()
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Einrichtung · Schritt ${step + 1} von $STEPS",
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (step) {
            0 -> WelcomeStep { step = 1 }
            1 -> PermissionStep { step = 2 }
            2 -> TvCheckStep { step = 3 }
            else -> DoneStep(onDone)
        }
    }
}

@Composable
private fun ColumnScope.WelcomeStep(onNext: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ScreenTitle("Willkommen bei EdgeFix")
    Text(
        "EdgeFix legt einen kaum sichtbaren Farbverlauf über die Bildränder. " +
            "Damit fallen lila verfärbte Ränder bei hellen Bildern weniger auf. " +
            "Die Bildmitte bleibt völlig unverändert.",
        fontSize = 26.sp,
    )
    Spacer(Modifier.weight(1f))
    BigButton("Los geht's", onNext, Modifier.focusRequester(focus))
    KeyHints("OK: weiter")
}

private fun Context.tryStart(vararg intents: Intent): Boolean {
    for (i in intents) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    return false
}

@Composable
private fun ColumnScope.PermissionStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var granted by remember { mutableStateOf(OverlayPermission.granted(context)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    // Beim Zurückkehren aus den Fire-TV-Einstellungen neu prüfen.
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            granted = OverlayPermission.granted(context)
        }
    }
    LaunchedEffect(granted) { runCatching { focus.requestFocus() } }

    ScreenTitle("Berechtigung einrichten", "EdgeFix muss über anderen Apps anzeigen dürfen.")

    if (granted) {
        Text("✓  Berechtigung ist eingerichtet.", fontSize = 28.sp, color = Accent)
        Text(
            "Du kannst das ADB-Debugging in den Entwickleroptionen jetzt wieder ausschalten. Die Berechtigung bleibt erhalten.",
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        BigButton("Weiter", onNext, Modifier.focusRequester(focus))
        KeyHints("OK: weiter")
        return
    }

    // ADB-Debugging an/aus lässt sich ohne Berechtigung auslesen.
    var adbOn by remember { mutableStateOf(adbEnabled(context)) }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                adbOn = adbEnabled(context)
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    val steps = listOf(
        "Einstellungen → Mein Fire TV → Info: 7× schnell auf den Gerätenamen klicken.",
        "Zurück → Entwickleroptionen → ADB-Debugging einschalten.",
        "Hierher zurück und „Jetzt einrichten“ wählen.",
        "„USB-Debugging zulassen?“: Haken bei „Immer zulassen“, dann OK.",
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, t ->
            Row {
                Text("${i + 1}.", fontSize = 21.sp, color = Accent, modifier = Modifier.width(34.dp))
                Text(t, fontSize = 21.sp)
            }
        }
    }
    // Statuszeilen stehen fest über den Knöpfen, damit sie immer sichtbar sind.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (adbOn) "✓  ADB-Debugging ist eingeschaltet" else "✗  ADB-Debugging ist aus (Schritte 1 und 2)",
            fontSize = 22.sp,
            color = if (adbOn) Accent else androidx.compose.ui.graphics.Color(0xFFF0B35A),
        )
        if (status != null) {
            Text(status!!, fontSize = 22.sp, color = androidx.compose.ui.graphics.Color(0xFFF0B35A))
        }
    }
    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
        BigButton(
            if (busy) "Bitte warten …" else "Jetzt einrichten",
            modifier = Modifier.focusRequester(focus),
            onClick = {
                if (busy) return@BigButton
                busy = true
                status = "Verbinde mit dem Fire TV …"
                scope.launch {
                    val r = AdbSelfGrant.run(
                        context,
                        onWaitingForConfirm = {
                            status = "Verbunden. Falls „USB-Debugging zulassen?“ erscheint: „Immer zulassen“ ankreuzen und OK."
                        },
                        onRetry = { n ->
                            status = "Verbindung wurde getrennt, neuer Versuch (${n + 1}) … Falls die Abfrage erscheint: „Immer zulassen“ und OK."
                        },
                    )
                    busy = false
                    status = when (r) {
                        AdbSelfGrant.Result.Success -> null
                        AdbSelfGrant.Result.AdbOff ->
                            if (adbEnabled(context)) "ADB ist an, aber nicht erreichbar (Port 5555). Bitte ADB-Debugging einmal aus- und wieder einschalten."
                            else "ADB-Debugging ist noch aus. Bitte Schritte 1 und 2 ausführen."
                        AdbSelfGrant.Result.NotAuthorized -> "Die Abfrage am Fernseher wurde nicht bestätigt. Bitte erneut versuchen."
                        is AdbSelfGrant.Result.Failed -> "Hat nicht geklappt: ${r.message}"
                    }
                    granted = OverlayPermission.granted(context)
                }
            },
        )
        // Kein Knopf für den Systemdialog „Über anderen Apps anzeigen“: Fire OS
        // meldet ihn zwar als vorhanden, er tut dort aber nichts.
        BigButton(
            "Einstellungen",
            primary = false,
            onClick = {
                context.tryStart(
                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                    Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
                    Intent(Settings.ACTION_SETTINGS),
                )
            },
        )
        BigButton("Überspringen", primary = false, onClick = onNext)
    }
}

private fun adbEnabled(context: Context): Boolean =
    runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)

@Composable
private fun ColumnScope.TvCheckStep(onNext: () -> Unit) {
    val items = listOf(
        "Bildgröße: 16:9 Standard" to "Samsung: Einstellungen → Bild → Bildgrößeneinstellungen",
        "„An Bildschirm anpassen“ ein" to "Gleiches Menü. Sonst schneidet der Fernseher die Ränder ab.",
        "Dynamischer Kontrast aus" to "Bild → Experteneinstellungen → Kontrastverbesserung: Aus",
        "Bildmodus nicht mehr ändern" to "Die Abstimmung gilt für den jetzt eingestellten Bildmodus.",
    )
    val checked = remember { mutableStateListOf(*Array(items.size) { false }) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ScreenTitle("Fernseher prüfen", "Jeden Punkt am Samsung einstellen und mit OK abhaken.")
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items.size) { i ->
            ToggleRow(
                items[i].first,
                checked[i],
                { checked[i] = !checked[i] },
                description = items[i].second,
                modifier = if (i == 0) Modifier.focusRequester(focus) else Modifier,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        BigButton("Weiter", onNext, enabled = true)
    }
    KeyHints("OK: abhaken · ▼ weiter")
}

@Composable
private fun ColumnScope.DoneStep(onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ScreenTitle("Fertig eingerichtet")
    Text(
        "Die Korrektur läuft jetzt mit einer einfachen Randmaske. Am genauesten wird es mit „Mit Handy kalibrieren“ " +
            "auf dem Startbildschirm. Ohne Handy passt du Stärke und Farbe unter „Feinabstimmung“ an.",
        fontSize = 26.sp,
    )
    Spacer(Modifier.weight(1f))
    BigButton("Zum Startbildschirm", onDone, Modifier.focusRequester(focus))
}
