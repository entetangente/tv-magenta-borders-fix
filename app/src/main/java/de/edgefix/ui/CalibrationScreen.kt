package de.edgefix.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import de.edgefix.calib.CalibrationServer
import de.edgefix.calib.Network
import de.edgefix.overlay.OverlayPermission
import de.edgefix.overlay.OverlayService
import de.edgefix.store.CalibrationState
import de.edgefix.store.SettingsRepository
import kotlinx.coroutines.delay

private fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

/**
 * Kalibrierung mit dem Handy: startet den lokalen Server, zeigt den QR-Code und
 * danach die vom Handy angeforderten Messbilder.
 */
@Composable
fun CalibrationScreen(repo: SettingsRepository) {
    val context = LocalContext.current
    val cal by repo.calibration.collectAsStateWithLifecycle()
    var server by remember { mutableStateOf<CalibrationServer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val ip = remember { Network.localIp() }

    DisposableEffect(Unit) {
        repo.updateCalibration { CalibrationState() }
        if (OverlayPermission.granted(context)) OverlayService.sync(context)
        val s = CalibrationServer(context, Network.deviceName(context))
        try {
            s.startServer()
            server = s
        } catch (e: Exception) {
            error = "Server konnte nicht starten: ${e.message}"
        }
        onDispose {
            runCatching { s.stopServer() }
            repo.setBypass(false)
            repo.updateCalibration { null }
            OverlayService.sync(context)
        }
    }

    val state = cal
    val level = state?.level
    if (level != null) {
        PatternView(level, state.hint)
        return
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Row(
        Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ScreenTitle("Mit Handy kalibrieren")
            when {
                !OverlayPermission.granted(context) -> Text(
                    "Die Berechtigung „Über anderen Apps anzeigen“ fehlt. Ohne sie kann EdgeFix keine Korrektur anzeigen. " +
                        "Bitte zuerst unter Einstellungen → Berechtigung einrichten.",
                    fontSize = 24.sp, color = Color(0xFFFF7A6B),
                )
                error != null -> Text(error!!, fontSize = 24.sp, color = Color(0xFFFF7A6B))
                ip == null -> Text(
                    "Keine Netzwerkverbindung gefunden. Der Fire TV muss im selben WLAN sein wie das Handy.",
                    fontSize = 24.sp, color = Color(0xFFFF7A6B),
                )
                state?.phoneConnected == true -> {
                    Text("✓  Handy verbunden", fontSize = 30.sp, color = Accent, fontWeight = FontWeight.Bold)
                    Text(state.stepText ?: "Folge den Schritten auf dem Handy.", fontSize = 24.sp)
                }
                else -> {
                    Step(1, "Kamera des Handys öffnen und den QR-Code scannen.")
                    Step(2, "Den Link öffnen. Handy und Fire TV müssen im selben WLAN sein.")
                    Step(3, "Den Anweisungen auf dem Handy folgen. Dauert etwa 5 Minuten.")
                }
            }
            Spacer(Modifier.size(12.dp))
            if (ip != null && server != null) {
                Text(
                    "Oder im Browser eingeben: http://$ip:${CalibrationServer.PORT}/?t=${server!!.token}",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // fokussierbares Element, damit Zurück zuverlässig ankommt
            BigButton("Fertig", { (context as? android.app.Activity)?.onBackPressed() }, Modifier.focusRequester(focus), primary = false)
            KeyHints("Zurück: Kalibrierung beenden")
        }
        if (ip != null && server != null && OverlayPermission.granted(context)) {
            val qr = remember(server) { qrBitmap("http://$ip:${CalibrationServer.PORT}/?t=${server!!.token}").asImageBitmap() }
            Box(Modifier.background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp)) {
                Image(qr, contentDescription = "QR-Code", modifier = Modifier.size(360.dp), filterQuality = FilterQuality.None)
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row {
        Text("$n.", fontSize = 26.sp, color = Accent, modifier = Modifier.width(44.dp))
        Text(text, fontSize = 26.sp)
    }
}

/** Vollflächiges Messbild. Der Hinweis verschwindet nach 2 s, damit er nicht mitgemessen wird. */
@Composable
private fun PatternView(level: Float, hint: String?) {
    var showHint by remember(level, hint) { mutableStateOf(hint != null) }
    LaunchedEffect(level, hint) {
        delay(2000)
        showHint = false
    }
    Box(Modifier.fillMaxSize().background(Color(level, level, level))) {
        if (showHint && hint != null) {
            Text(
                hint,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 28.dp, vertical = 16.dp),
            )
        }
    }
}
