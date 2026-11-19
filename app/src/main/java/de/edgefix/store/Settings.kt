package de.edgefix.store

import de.edgefix.core.Curve
import de.edgefix.core.EdgeParams
import de.edgefix.core.Edges
import org.json.JSONObject

data class Settings(
    /** Korrektur eingeschaltet. */
    val enabled: Boolean = true,
    /** Dienst beim Einschalten des Fire TV starten. */
    val autostart: Boolean = true,
    /** Ersteinrichtung abgeschlossen. */
    val setupDone: Boolean = false,
    /** Zeitpunkt der letzten Kalibrierung oder Feinabstimmung (ms), 0 = nie. */
    val lastTuned: Long = 0L,
    val params: EdgeParams = EdgeParams(),
)

object SettingsJson {
    fun encode(s: Settings): String = JSONObject().apply {
        put("version", 1)
        put("enabled", s.enabled)
        put("autostart", s.autostart)
        put("setupDone", s.setupDone)
        put("lastTuned", s.lastTuned)
        put("params", encodeParams(s.params))
    }.toString()

    fun decode(json: String?): Settings {
        if (json.isNullOrBlank()) return Settings()
        return try {
            val o = JSONObject(json)
            Settings(
                enabled = o.optBoolean("enabled", true),
                autostart = o.optBoolean("autostart", true),
                setupDone = o.optBoolean("setupDone", false),
                lastTuned = o.optLong("lastTuned", 0L),
                params = o.optJSONObject("params")?.let(::decodeParams) ?: EdgeParams(),
            )
        } catch (e: Exception) {
            Settings()
        }
    }

    fun encodeParams(p: EdgeParams): JSONObject = JSONObject().apply {
        put("aMax", p.aMax.toDouble())
        put("k", p.k.toDouble())
        put("hueDeg", p.hueDeg.toDouble())
        put("width", encodeEdges(p.width))
        put("strength", encodeEdges(p.strength))
        put("curve", p.curve.name)
    }

    fun decodeParams(o: JSONObject): EdgeParams {
        val d = EdgeParams()
        return EdgeParams(
            aMax = o.optDouble("aMax", d.aMax.toDouble()).toFloat(),
            k = o.optDouble("k", d.k.toDouble()).toFloat(),
            hueDeg = o.optDouble("hueDeg", d.hueDeg.toDouble()).toFloat(),
            width = o.optJSONObject("width")?.let { decodeEdges(it, d.width) } ?: d.width,
            strength = o.optJSONObject("strength")?.let { decodeEdges(it, d.strength) } ?: d.strength,
            curve = runCatching { Curve.valueOf(o.optString("curve")) }.getOrDefault(d.curve),
        ).clamped()
    }

    private fun encodeEdges(e: Edges) = JSONObject().apply {
        put("top", e.top.toDouble()); put("bottom", e.bottom.toDouble())
        put("left", e.left.toDouble()); put("right", e.right.toDouble())
    }

    private fun decodeEdges(o: JSONObject, d: Edges) = Edges(
        o.optDouble("top", d.top.toDouble()).toFloat(),
        o.optDouble("bottom", d.bottom.toDouble()).toFloat(),
        o.optDouble("left", d.left.toDouble()).toFloat(),
        o.optDouble("right", d.right.toDouble()).toFloat(),
    )
}
