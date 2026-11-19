package de.edgefix.ui

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import de.edgefix.core.Curve
import de.edgefix.core.Edge
import de.edgefix.core.EdgeParams
import de.edgefix.core.get
import de.edgefix.core.with
import de.edgefix.store.SettingsRepository
import kotlin.math.roundToInt

private fun hueName(h: Float): String = when (((h % 360) + 360) % 360) {
    in 0f..15f, in 345f..360f -> "Rot"
    in 15f..45f -> "Orange"
    in 45f..65f -> "Gelb"
    in 65f..100f -> "Gelbgrün"
    in 100f..150f -> "Grün"
    in 150f..195f -> "Türkis"
    in 195f..255f -> "Blau"
    in 255f..300f -> "Violett"
    else -> "Magenta"
}

private fun pct(v: Float, decimals: Int = 0): String =
    if (decimals == 0) "${(v * 100).roundToInt()} %" else "%.1f %%".format(v * 100)

private fun Float.stepTo(d: Int, step: Float, min: Float, max: Float): Float =
    ((this / step).roundToInt() + d).times(step).coerceIn(min, max)

@Composable
fun TuningScreen(repo: SettingsRepository) {
    val settings by repo.settings.collectAsStateWithLifecycle()
    val bypass by repo.bypass.collectAsStateWithLifecycle()
    val p = settings.params
    val useMap = settings.useMap && settings.activeMapId != null
    var pattern by rememberSaveable { mutableStateOf(TestPattern.WHITE) }
    var expert by rememberSaveable { mutableStateOf(false) }
    var panelHidden by rememberSaveable { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    val hiddenFocus = remember { FocusRequester() }

    fun set(transform: (EdgeParams) -> EdgeParams) =
        repo.update { it.copy(params = transform(it.params).clamped(), lastTuned = System.currentTimeMillis()) }

    LaunchedEffect(panelHidden) {
        runCatching { if (panelHidden) hiddenFocus.requestFocus() else firstFocus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (!isOk) return@onPreviewKeyEvent false
                // OK gedrückt halten = Vorher/Nachher
                when {
                    e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 -> {
                        if (!repo.bypass.value) repo.setBypass(true)
                        true
                    }
                    e.type == KeyEventType.KeyUp && repo.bypass.value -> {
                        repo.setBypass(false)
                        true
                    }
                    else -> false
                }
            },
    ) {
        TestPatternView(pattern)

        if (bypass) {
            Text(
                "VORHER (ohne Korrektur)",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 120.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        if (panelHidden) {
            // Unsichtbares, fokussierbares Element: jede Taste außer OK holt das Bedienfeld zurück.
            Box(
                Modifier
                    .focusRequester(hiddenFocus)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyUp &&
                            e.nativeKeyEvent.keyCode != NativeKeyEvent.KEYCODE_DPAD_CENTER &&
                            e.nativeKeyEvent.keyCode != NativeKeyEvent.KEYCODE_ENTER &&
                            e.nativeKeyEvent.keyCode != NativeKeyEvent.KEYCODE_BACK
                        ) {
                            panelHidden = false
                            true
                        } else {
                            false
                        }
                    }
                    .focusable(),
            )
            return@Box
        }

        Column(
            Modifier
                .align(Alignment.Center)
                .width(600.dp)
                .fillMaxHeight(0.78f)
                .background(Color(0xE6121416), RoundedCornerShape(20.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Feinabstimmung", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            if (!de.edgefix.overlay.OverlayPermission.granted(androidx.compose.ui.platform.LocalContext.current)) {
                Text(
                    "Berechtigung fehlt: Die Korrektur wird nicht angezeigt. Einstellungen → Berechtigung einrichten.",
                    fontSize = 20.sp,
                    color = Color(0xFFFF7A6B),
                )
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (settings.activeMapId != null) {
                    item {
                        ChoiceRow(
                            "Korrektur", listOf(true, false), settings.useMap,
                            { if (it) "Gemessen" else "Einfache Randmaske" },
                            { v -> repo.update { it.copy(useMap = v) } },
                        )
                    }
                }
                if (useMap) {
                    item {
                        ValueRow(
                            "Stärke", pct(settings.mapStrength), settings.mapStrength / 1.5f,
                            { d -> repo.update { it.copy(mapStrength = it.mapStrength.stepTo(d, 0.05f, 0f, 1.5f)) } },
                            Modifier.focusRequester(firstFocus),
                        )
                    }
                    item {
                        ChoiceRow(
                            "Schutz für dunkle Szenen", listOf(0, 1, 2), settings.mapVariant,
                            { listOf("niedrig", "mittel", "hoch")[it] },
                            { v -> repo.update { it.copy(mapVariant = v) } },
                        )
                    }
                } else {
                    item {
                        ValueRow(
                            "Stärke", pct(p.aMax), p.aMax / EdgeParams.MAX_A,
                            { d -> set { it.copy(aMax = it.aMax.stepTo(d, 0.01f, 0f, EdgeParams.MAX_A)) } },
                            Modifier.focusRequester(firstFocus),
                        )
                    }
                    item {
                        ValueRow(
                            "Schutz für dunkle Szenen", pct(1f - p.k), 1f - p.k,
                            { d -> set { it.copy(k = (1f - (1f - it.k).stepTo(d, 0.05f, 0f, 1f))) } },
                        )
                    }
                }
                item {
                    ChoiceRow("Testbild", TestPattern.entries, pattern, { it.label }, { pattern = it })
                }
                item {
                    ActionRow("Bedienfeld ausblenden", { panelHidden = true }, description = "Beliebige Taste blendet es wieder ein")
                }
                if (!useMap) item {
                    ToggleRow("Experte", expert, { expert = !expert }, description = "Farbton, Randbreiten, Verlauf")
                }
                if (expert && !useMap) {
                    item {
                        ValueRow(
                            "Farbton", "${p.hueDeg.roundToInt()}° ${hueName(p.hueDeg)}", p.hueDeg / 360f,
                            { d -> set { it.copy(hueDeg = ((it.hueDeg.roundToInt() + d * 5 + 360) % 360).toFloat()) } },
                        )
                    }
                    item {
                        ChoiceRow("Verlauf", Curve.entries, p.curve, { it.label }, { c -> set { it.copy(curve = c) } })
                    }
                    for (e in Edge.entries) {
                        item(key = "w$e") {
                            val v = p.width.get(e)
                            ValueRow(
                                "Breite ${e.label}", pct(v, 1), v / EdgeParams.MAX_WIDTH,
                                { d -> set { it.copy(width = it.width.with(e, it.width.get(e).stepTo(d, 0.005f, 0f, EdgeParams.MAX_WIDTH))) } },
                            )
                        }
                    }
                    for (e in Edge.entries) {
                        item(key = "s$e") {
                            val v = p.strength.get(e)
                            ValueRow(
                                "Stärke ${e.label}", pct(v), v / EdgeParams.MAX_STRENGTH,
                                { d -> set { it.copy(strength = it.strength.with(e, it.strength.get(e).stepTo(d, 0.05f, 0f, EdgeParams.MAX_STRENGTH))) } },
                            )
                        }
                    }
                    item {
                        ActionRow("Auf Startwerte zurücksetzen", { set { EdgeParams() } })
                    }
                }
            }
            KeyHints("◀ ▶ ändern · OK halten: Vorher/Nachher · Zurück: fertig")
        }
    }
}
