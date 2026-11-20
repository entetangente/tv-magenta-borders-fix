package de.edgefix.store

import de.edgefix.core.Curve
import de.edgefix.core.Edge
import de.edgefix.core.EdgeParams
import de.edgefix.core.EdgeShape
import de.edgefix.core.Edges
import org.json.JSONArray
import org.json.JSONObject

/** Profile: HDR-Inhalte brauchen andere Werte, weil das Overlay dort anders wirkt. */
enum class ProfileId(val label: String) {
    SDR("SDR"),
    HDR("HDR"),
}

/** Alle Werte eines Profils. */
data class Profile(
    /** parametrische Randmaske (Feinabstimmung, Abgleich per Auge) */
    val params: EdgeParams = EdgeParams(),
    /** Gemessene Karte statt parametrischer Randmaske verwenden. */
    val useMap: Boolean = false,
    /** Kennung der aktiven gespeicherten Karte. */
    val activeMapId: String? = null,
    /** Stärke der Karte (1 = wie berechnet), 0–1.5. */
    val mapStrength: Float = 1f,
    /** Schutz für dunkle Szenen: 0 niedrig, 1 mittel, 2 hoch. */
    val mapVariant: Int = 1,
    /** Zeitpunkt der letzten Feinabstimmung (ms), 0 = nie. */
    val lastTuned: Long = 0L,
    /** Zeitpunkt der letzten Kalibrierung mit dem Handy (ms), 0 = nie. */
    val lastCalibrated: Long = 0L,
) {
    /** Karte wird tatsächlich verwendet. */
    val mapActive: Boolean get() = useMap && activeMapId != null
}

data class Settings(
    /** Korrektur eingeschaltet. Aus = Profil „Aus“. */
    val enabled: Boolean = true,
    /** Dienst beim Einschalten des Fire TV starten. */
    val autostart: Boolean = true,
    /** Ersteinrichtung abgeschlossen. */
    val setupDone: Boolean = false,
    /** aktives Profil (wenn eingeschaltet) */
    val profile: ProfileId = ProfileId.SDR,
    val profiles: Map<ProfileId, Profile> = ProfileId.entries.associateWith { Profile() },
) {
    val current: Profile get() = profiles[profile] ?: Profile()

    /** Ändert das aktive Profil. */
    fun withCurrent(transform: (Profile) -> Profile): Settings =
        copy(profiles = profiles + (profile to transform(current)))
}

object SettingsJson {
    fun encode(s: Settings): String = JSONObject().apply {
        put("version", 2)
        put("enabled", s.enabled)
        put("autostart", s.autostart)
        put("setupDone", s.setupDone)
        put("profile", s.profile.name)
        put("profiles", JSONObject().apply {
            for ((id, p) in s.profiles) put(id.name, encodeProfile(p))
        })
    }.toString()

    fun decode(json: String?): Settings {
        if (json.isNullOrBlank()) return Settings()
        return try {
            val o = JSONObject(json)
            val profiles: Map<ProfileId, Profile> = if (o.has("profiles")) {
                val po = o.getJSONObject("profiles")
                ProfileId.entries.associateWith { id -> po.optJSONObject(id.name)?.let(::decodeProfile) ?: Profile() }
            } else {
                // Version 1: Werte lagen direkt in den Einstellungen → für beide Profile übernehmen.
                val p = decodeProfile(o)
                ProfileId.entries.associateWith { p }
            }
            Settings(
                enabled = o.optBoolean("enabled", true),
                autostart = o.optBoolean("autostart", true),
                setupDone = o.optBoolean("setupDone", false),
                profile = runCatching { ProfileId.valueOf(o.optString("profile")) }.getOrDefault(ProfileId.SDR),
                profiles = profiles,
            )
        } catch (e: Exception) {
            Settings()
        }
    }

    private fun encodeProfile(p: Profile) = JSONObject().apply {
        put("params", encodeParams(p.params))
        put("useMap", p.useMap)
        put("activeMapId", p.activeMapId ?: JSONObject.NULL)
        put("mapStrength", p.mapStrength.toDouble())
        put("mapVariant", p.mapVariant)
        put("lastTuned", p.lastTuned)
        put("lastCalibrated", p.lastCalibrated)
    }

    private fun decodeProfile(o: JSONObject) = Profile(
        params = o.optJSONObject("params")?.let(::decodeParams) ?: EdgeParams(),
        useMap = o.optBoolean("useMap", false),
        activeMapId = if (o.isNull("activeMapId")) null else o.optString("activeMapId", "").ifEmpty { null },
        mapStrength = o.optDouble("mapStrength", 1.0).toFloat().coerceIn(0f, 1.5f),
        mapVariant = o.optInt("mapVariant", 1).coerceIn(0, 2),
        lastTuned = o.optLong("lastTuned", 0L),
        lastCalibrated = o.optLong("lastCalibrated", 0L),
    )

    fun encodeParams(p: EdgeParams): JSONObject = JSONObject().apply {
        put("aMax", p.aMax.toDouble())
        put("k", p.k.toDouble())
        put("hueDeg", p.hueDeg.toDouble())
        put("width", encodeEdges(p.width))
        put("strength", encodeEdges(p.strength))
        put("curve", p.curve.name)
        p.shape?.let { put("shape", encodeShape(it)) }
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
            shape = o.optJSONObject("shape")?.let { runCatching { decodeShape(it) }.getOrNull() },
        ).clamped()
    }

    private fun floats(a: JSONArray?): List<Float>? = a?.let { arr -> List(arr.length()) { arr.getDouble(it).toFloat() } }
    private fun jsonArray(v: List<Float>) = JSONArray().apply { v.forEach { put(it.toDouble()) } }

    private fun encodeShape(s: EdgeShape) = JSONObject().apply {
        put("width", JSONObject().apply { s.width.forEach { (e, v) -> put(e.name, jsonArray(v)) } })
        put("strength", JSONObject().apply { s.strength.forEach { (e, v) -> put(e.name, jsonArray(v)) } })
        s.spotHue?.let { put("spotHue", jsonArray(it)) }
        s.spotGain?.let { put("spotGain", jsonArray(it)) }
    }

    private fun decodeShape(o: JSONObject): EdgeShape {
        fun perEdge(key: String) = Edge.entries.associateWith { e ->
            val v = floats(o.getJSONObject(key).getJSONArray(e.name))!!
            require(v.size == EdgeShape.POINTS)
            v
        }
        return EdgeShape(
            width = perEdge("width"),
            strength = perEdge("strength"),
            spotHue = floats(o.optJSONArray("spotHue"))?.takeIf { it.size == 8 },
            spotGain = floats(o.optJSONArray("spotGain"))?.takeIf { it.size == 8 },
        )
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
