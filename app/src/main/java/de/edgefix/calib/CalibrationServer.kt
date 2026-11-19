package de.edgefix.calib

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import de.edgefix.core.CorrectionMap
import de.edgefix.store.CalibrationOverlay
import de.edgefix.store.CalibrationState
import de.edgefix.store.MapStore
import de.edgefix.store.SettingsRepository
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lokaler Server für die Kalibrierung mit dem Handy: liefert die Web-App aus
 * (Assets unter web/), nimmt Befehle per WebSocket und die berechnete Karte per
 * HTTP entgegen. Läuft nur, solange der Kalibrierbildschirm offen ist. Jeder
 * Aufruf braucht das zufällige Sitzungs-Token (Parameter t oder Cookie).
 */
class CalibrationServer(
    context: Context,
    private val deviceName: String,
    port: Int = PORT,
) : NanoWSD(port) {

    private val app = context.applicationContext
    private val assets: AssetManager = app.assets
    private val repo = SettingsRepository.get(app)
    private val mapStore = MapStore(app)
    private val sockets = CopyOnWriteArrayList<Socket>()

    val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    fun startServer() {
        start(0, true) // ohne Lese-Timeout, damit WebSockets offen bleiben
    }

    fun stopServer() {
        sockets.forEach { runCatching { it.close(WebSocketFrame.CloseCode.GoingAway, "Kalibrierung beendet", false) } }
        sockets.clear()
        stop()
    }

    // ---------- Zugriffsschutz ----------

    private fun tokenOf(session: IHTTPSession): String? {
        session.parameters["t"]?.firstOrNull()?.let { return it }
        val cookie = session.headers["cookie"] ?: return null
        return cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("ef=") }?.removePrefix("ef=")
    }

    private fun authorized(session: IHTTPSession): Boolean {
        val t = tokenOf(session) ?: return false
        // Vergleich in konstanter Zeit
        if (t.length != token.length) return false
        var diff = 0
        for (i in t.indices) diff = diff or (t[i].code xor token[i].code)
        return diff == 0
    }

    override fun serve(session: IHTTPSession): Response {
        if (!authorized(session)) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Ungültiger oder fehlender Zugangscode. Bitte den QR-Code am Fernseher erneut scannen.")
        }
        return super.serve(session)
    }

    // ---------- HTTP ----------

    override fun serveHttp(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        return try {
            when {
                uri == "/api/map" && session.method == Method.POST -> receiveMap(session)
                session.method == Method.GET -> serveAsset(uri)
                else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP $uri", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Fehler")
        }
    }

    private fun serveAsset(uri: String): Response {
        val path = if (uri == "/" || uri.isEmpty()) "index.html" else uri.trimStart('/')
        if (path.contains("..")) return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "")
        val bytes = try {
            assets.open("web/$path").use { it.readBytes() }
        } catch (e: IOException) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Nicht gefunden")
        }
        val mime = when (path.substringAfterLast('.', "")) {
            "html" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
        val r = newFixedLengthResponse(Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong())
        if (path == "index.html") {
            // Token als Cookie, damit Skripte, Worker und Stylesheets ohne Parameter laden.
            r.addHeader("Set-Cookie", "ef=$token; Path=/; SameSite=Strict; HttpOnly")
            r.addHeader("Cache-Control", "no-store")
        }
        return r
    }

    private fun receiveMap(session: IHTTPSession): Response {
        val len = session.headers["content-length"]?.toIntOrNull()
            ?: return newFixedLengthResponse(Response.Status.LENGTH_REQUIRED, MIME_PLAINTEXT, "")
        if (len <= 0 || len > CorrectionMap.MAX_BYTES) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Ungültige Größe")
        }
        val bytes = ByteArray(len)
        var off = 0
        val input = session.inputStream
        while (off < len) {
            val n = input.read(bytes, off, len - off)
            if (n < 0) break
            off += n
        }
        if (off != len) return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Unvollständig")
        val map = try {
            CorrectionMap.parse(bytes)
        } catch (e: IllegalArgumentException) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, e.message ?: "Ungültig")
        }
        val meta = session.headers["x-edgefix-meta"]?.let {
            runCatching { JSONObject(URLDecoder.decode(it, "UTF-8")) }.getOrNull()
        }
        Log.i(TAG, "Karte empfangen: ${map.gw}x${map.gh}, ${map.variants.size} Varianten, meta=$meta")
        previewMeta = meta
        repo.updateCalibration { (it ?: CalibrationState()).copy(previewBytes = bytes, previewMap = map, overlay = CalibrationOverlay.PREVIEW) }
        broadcastState()
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true}")
    }

    @Volatile
    private var previewMeta: JSONObject? = null

    // ---------- WebSocket ----------

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = Socket(handshake)

    inner class Socket(handshake: IHTTPSession) : WebSocket(handshake) {
        override fun onOpen() {
            sockets += this
            repo.updateCalibration { (it ?: CalibrationState()).copy(phoneConnected = true) }
            sendState(this)
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            sockets -= this
            if (sockets.isEmpty()) repo.updateCalibration { it?.copy(phoneConnected = false) }
        }

        override fun onMessage(frame: WebSocketFrame) {
            try {
                handle(JSONObject(frame.textPayload))
            } catch (e: Exception) {
                Log.w(TAG, "WS-Nachricht", e)
            }
        }

        override fun onPong(pong: WebSocketFrame?) = Unit
        override fun onException(exception: IOException?) {
            sockets -= this
        }
    }

    private fun handle(msg: JSONObject) {
        when (msg.optString("t")) {
            "show" -> {
                val level = if (msg.isNull("level") || !msg.has("level")) null else msg.optDouble("level").toFloat().coerceIn(0f, 1f)
                val overlay = when (msg.optString("overlay")) {
                    "off" -> CalibrationOverlay.OFF
                    "preview" -> CalibrationOverlay.PREVIEW
                    else -> CalibrationOverlay.ACTIVE
                }
                val hint = msg.optString("hint", "").ifEmpty { null }
                repo.updateCalibration { (it ?: CalibrationState()).copy(level = level, hint = hint, overlay = overlay) }
            }
            "tune" -> repo.updateCalibration {
                (it ?: CalibrationState()).copy(
                    previewStrength = msg.optDouble("strength", 1.0).toFloat().coerceIn(0f, 1.5f),
                    previewVariant = msg.optInt("variant", 1).coerceIn(0, 2),
                )
            }
            "compare" -> repo.setBypass(msg.optBoolean("on"))
            "step" -> repo.updateCalibration { (it ?: CalibrationState()).copy(stepText = msg.optString("text")) }
            "apply" -> apply()
        }
        broadcastState()
    }

    /** Vorschau-Karte speichern und als aktive Korrektur übernehmen. */
    private fun apply() {
        val cal = repo.calibration.value ?: return
        val bytes = cal.previewBytes ?: return
        val id = mapStore.save(bytes, previewMeta)
        val now = System.currentTimeMillis()
        repo.update {
            it.copy(
                enabled = true,
                useMap = true,
                activeMapId = id,
                mapStrength = cal.previewStrength,
                mapVariant = cal.previewVariant,
                lastCalibrated = now,
                lastTuned = now,
            )
        }
        repo.setBypass(false)
        repo.updateCalibration { it?.copy(overlay = CalibrationOverlay.ACTIVE, stepText = "Übernommen ✓") }
    }

    private fun stateJson(): String {
        val cal = repo.calibration.value
        return JSONObject()
            .put("t", "state")
            .put("device", deviceName)
            .put("hasPreview", cal?.previewMap != null)
            .put("strength", (cal?.previewStrength ?: 1f).toDouble())
            .put("variant", cal?.previewVariant ?: 1)
            .toString()
    }

    private fun sendState(s: Socket) {
        runCatching { s.send(stateJson()) }
    }

    private fun broadcastState() {
        val json = stateJson()
        sockets.forEach { s -> runCatching { s.send(json) }.onFailure { sockets -= s } }
    }

    companion object {
        const val PORT = 8765
        private const val TAG = "EdgeFix"
    }
}
