package de.edgefix.ui

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import de.edgefix.core.Edge
import de.edgefix.core.EdgeMaskRenderer

import de.edgefix.core.EdgeShape
import de.edgefix.core.Spot
import de.edgefix.store.SettingsRepository
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Rand nachzeichnen: 16 Stützpunkte je Kante, Breite und Stärke per Fernbedienung
// ---------------------------------------------------------------------------

/** Umlauf im Uhrzeigersinn: oben (l→r), rechts (o→u), unten (r→l), links (u→o). */
private fun perimeterPoint(index: Int): Pair<Edge, Int> {
    val n = EdgeShape.POINTS
    val i = ((index % (4 * n)) + 4 * n) % (4 * n)
    return when (i / n) {
        0 -> Edge.TOP to (i % n)
        1 -> Edge.RIGHT to (i % n)
        2 -> Edge.BOTTOM to (n - 1 - i % n)
        else -> Edge.LEFT to (n - 1 - i % n)
    }
}

/** Bildschirmposition (0–1) des Stützpunkts und Richtung nach innen. */
private fun pointGeometry(e: Edge, i: Int): Pair<Offset, Offset> {
    val t = i / (EdgeShape.POINTS - 1f)
    return when (e) {
        Edge.TOP -> Offset(t, 0f) to Offset(0f, 1f)
        Edge.BOTTOM -> Offset(t, 1f) to Offset(0f, -1f)
        Edge.LEFT -> Offset(0f, t) to Offset(1f, 0f)
        Edge.RIGHT -> Offset(1f, t) to Offset(-1f, 0f)
    }
}

private enum class ShapeParam(val label: String) { WIDTH("Breite"), STRENGTH("Stärke") }

@Composable
fun ShapeEditorScreen(repo: SettingsRepository) {
    val settings by repo.settings.collectAsStateWithLifecycle()
    val p = settings.current.params
    // Beim ersten Öffnen die Detailform aus den einfachen Werten anlegen.
    LaunchedEffect(Unit) {
        if (repo.settings.value.current.params.shape == null) {
            repo.update { s -> s.withCurrent { it.copy(params = it.params.copy(shape = EdgeShape.from(it.params))) } }
        }
    }
    // Bis die Detailform gespeichert ist, die gleichwertige Startform anzeigen.
    val shape = p.shape ?: EdgeShape.from(p)
    var index by remember { mutableIntStateOf(EdgeShape.POINTS / 2) }
    var param by remember { mutableStateOf(ShapeParam.WIDTH) }
    var pattern by remember { mutableStateOf(TestPattern.WHITE) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val (edge, point) = perimeterPoint(index)
    val w = shape.width.getValue(edge)[point]
    val st = shape.strength.getValue(edge)[point]

    fun change(d: Int) {
        repo.update { s ->
            s.withCurrent { prof ->
                val sh = prof.params.shape ?: return@withCurrent prof
                val newShape = when (param) {
                    ShapeParam.WIDTH -> sh.withPoint(edge, point, w = (w + d * 0.001f).coerceAtLeast(0f))
                    ShapeParam.STRENGTH -> sh.withPoint(edge, point, s = (st + d * 0.05f).coerceAtLeast(0f))
                }
                prof.copy(params = prof.params.copy(shape = newShape), lastTuned = System.currentTimeMillis())
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> { index++; true }
                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> { index--; true }
                    NativeKeyEvent.KEYCODE_DPAD_UP -> { change(+1); true }
                    NativeKeyEvent.KEYCODE_DPAD_DOWN -> { change(-1); true }
                    NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER -> {
                        if (e.nativeKeyEvent.repeatCount == 0) {
                            param = if (param == ShapeParam.WIDTH) ShapeParam.STRENGTH else ShapeParam.WIDTH
                        }
                        true
                    }
                    NativeKeyEvent.KEYCODE_MEDIA_FAST_FORWARD, NativeKeyEvent.KEYCODE_MEDIA_REWIND,
                    NativeKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        val list = listOf(TestPattern.WHITE, TestPattern.GRID, TestPattern.GRAY50, TestPattern.GRAY20)
                        pattern = list[(list.indexOf(pattern) + 1) % list.size]
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        TestPatternView(pattern)
        Canvas(Modifier.fillMaxSize()) {
            val hPx = size.height
            for (k in 0 until 4 * EdgeShape.POINTS) {
                val (e2, i2) = perimeterPoint(k)
                val (pos, dir) = pointGeometry(e2, i2)
                val base = Offset(pos.x * size.width, pos.y * size.height)
                val len = shape.width.getValue(e2)[i2] * hPx
                val selected = k == ((index % 64) + 64) % 64
                val color = if (selected) Color(0xFFFF2D2D) else Color(0x99505050)
                val end = base + dir * len
                drawLine(color, base, end, strokeWidth = if (selected) 5f else 2f)
                drawCircle(color, radius = if (selected) 9f else 4f, center = end)
            }
        }
        Column(
            Modifier
                .align(Alignment.Center)
                .background(Color(0xE6121416), RoundedCornerShape(16.dp))
                .padding(horizontal = 28.dp, vertical = 18.dp),
        ) {
            Text("Rand nachzeichnen · ${settings.profile.label}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                "Kante ${edge.label}, Punkt ${point + 1}/${EdgeShape.POINTS}",
                fontSize = 22.sp, color = Color.White,
            )
            Text(
                "Breite ${"%.1f".format(w * 100)} %   ·   Stärke ${(st * 100).roundToInt()} %   ·   ändert: ${param.label}",
                fontSize = 22.sp, color = Accent,
            )
            Text(
                "◀ ▶ Punkt wählen · ▲ ▼ ${param.label} ändern · OK: Breite/Stärke · ⏯ Testbild · Zurück: fertig",
                fontSize = 17.sp, color = Color(0xFFB8BEC6),
            )
            Text(
                "Gesamtstärke ${(p.aMax * 100).roundToInt()} % (in der Feinabstimmung)",
                fontSize = 17.sp, color = Color(0xFFB8BEC6),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Farbton angleichen: Vergleichsfeld so tönen, dass es wie der Rand aussieht
// ---------------------------------------------------------------------------

private data class Tint(val hue: Float = 300f, val amount: Float = 0.10f)

/** Weiß, um amount in Richtung Farbton hue getönt. */
private fun tintColor(t: Tint): Color {
    val c = EdgeMaskRenderer.hueColor(t.hue, 1f)
    return Color(1f - t.amount * (1f - c[0]), 1f - t.amount * (1f - c[1]), 1f - t.amount * (1f - c[2]))
}

@Composable
fun HueMatchScreen(repo: SettingsRepository, onDone: () -> Unit) {
    val settings by repo.settings.collectAsStateWithLifecycle()
    val existing = settings.current.params.shape
    // Startwerte: frühere Abgleiche (Gegenfarbe zurückgerechnet) oder Magenta
    val tints = remember {
        mutableStateListOf<Tint>().apply {
            val hues = existing?.spotHue
            val gains = existing?.spotGain
            for (i in Spot.entries.indices) {
                add(
                    if (hues != null) Tint((hues[i] + 180f) % 360f, 0.10f * (gains?.get(i) ?: 1f))
                    else Tint(),
                )
            }
        }
    }
    var spot by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Rohes Bild ohne Korrektur zeigen, sonst vergleicht man mit der Korrektur.
    DisposableEffect(Unit) {
        repo.setBypass(true)
        onDispose { repo.setBypass(false) }
    }

    fun save() {
        val mean = tints.map { it.amount }.average().toFloat().takeIf { it > 0.001f } ?: 1f
        repo.update { s ->
            s.withCurrent { prof ->
                val base = prof.params.shape ?: EdgeShape.from(prof.params)
                val sh = base.copy(
                    spotHue = tints.map { (it.hue + 180f) % 360f },
                    spotGain = tints.map { it.amount / mean },
                )
                prof.copy(params = prof.params.copy(shape = sh).clamped(), lastTuned = System.currentTimeMillis())
            }
        }
    }

    val current = tints[spot]
    val s = Spot.entries[spot]

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.White)
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> { tints[spot] = current.copy(hue = (current.hue + 5f) % 360f); true }
                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> { tints[spot] = current.copy(hue = (current.hue + 355f) % 360f); true }
                    NativeKeyEvent.KEYCODE_DPAD_UP -> { tints[spot] = current.copy(amount = (current.amount + 0.01f).coerceAtMost(0.6f)); true }
                    NativeKeyEvent.KEYCODE_DPAD_DOWN -> { tints[spot] = current.copy(amount = (current.amount - 0.01f).coerceAtLeast(0f)); true }
                    NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER -> {
                        if (e.nativeKeyEvent.repeatCount == 0) {
                            save()
                            if (spot < Spot.entries.size - 1) {
                                // nächste Stelle startet mit dem eben gefundenen Ton
                                tints[spot + 1] = tints[spot + 1].copy(hue = current.hue)
                                spot++
                            } else {
                                onDone()
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val side = size.height * 0.14f
            // Vergleichsfeld nach innen versetzt, damit es selbst nicht im Randbereich liegt
            val cx = size.width * (0.5f + (s.x - 0.5f) * 0.70f)
            val cy = size.height * (0.5f + (s.y - 0.5f) * 0.58f)
            drawRect(tintColor(current), topLeft = Offset(cx - side / 2, cy - side / 2), size = Size(side, side))
            drawRect(Color(0xFF9AA0A6), topLeft = Offset(cx - side / 2, cy - side / 2), size = Size(side, side), style = Stroke(2f))
            // Pfeil zur Stelle am Rand (endet mit Abstand, um den Rand nicht zu verdecken)
            val target = Offset(s.x * size.width, s.y * size.height)
            val from = Offset(cx, cy)
            val dir = (target - from) / (target - from).getDistance()
            val start = from + dir * (side * 0.75f)
            val end = target - dir * (size.height * 0.06f)
            drawLine(Color(0xFF606468), start, end, strokeWidth = 3f)
            drawCircle(Color(0xFF606468), radius = 6f, center = end)
        }
        Column(
            Modifier
                .align(Alignment.Center)
                .background(Color(0xE6121416), RoundedCornerShape(16.dp))
                .padding(horizontal = 28.dp, vertical = 18.dp),
        ) {
            Text("Farbton angleichen · ${settings.profile.label}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("${s.label} (${spot + 1} von 8)", fontSize = 22.sp, color = Color.White)
            Text(
                "Feld so einstellen, dass es genauso lila aussieht wie der Rand an dieser Stelle.",
                fontSize = 19.sp, color = Color(0xFFB8BEC6),
            )
            Text(
                "Farbton ${current.hue.roundToInt()}°   ·   Tönung ${(current.amount * 100).roundToInt()} %",
                fontSize = 22.sp, color = Accent,
            )
            Text("◀ ▶ Farbton · ▲ ▼ Tönung · OK: übernehmen und weiter · Zurück: abbrechen", fontSize = 17.sp, color = Color(0xFFB8BEC6))
        }
    }
}

