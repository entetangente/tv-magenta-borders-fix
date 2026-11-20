package de.edgefix.store

import de.edgefix.core.Edge
import de.edgefix.core.EdgeParams
import de.edgefix.core.EdgeShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Läuft mit echtem org.json (Testabhängigkeit), nicht mit dem Android-Stub. */
class SettingsJsonTest {
    @Test
    fun roundTripWithProfilesAndShape() {
        val shape = EdgeShape.from(EdgeParams()).withPoint(Edge.LEFT, 3, w = 0.012f, s = 1.2f)
            .copy(spotHue = List(8) { 100f + it }, spotGain = List(8) { 1f })
        val s = Settings(profile = ProfileId.HDR, enabled = false).let { base ->
            base.withCurrent { it.copy(params = EdgeParams(aMax = 0.2f, shape = shape), mapStrength = 1.3f, activeMapId = "x", useMap = true) }
        }
        val back = SettingsJson.decode(SettingsJson.encode(s))
        assertEquals(s.profile, back.profile)
        assertEquals(false, back.enabled)
        assertEquals(s.current.mapStrength, back.current.mapStrength, 1e-6f)
        assertEquals("x", back.current.activeMapId)
        assertEquals(0.012f, back.current.params.shape!!.width.getValue(Edge.LEFT)[3], 1e-6f)
        assertEquals(107f, back.current.params.shape!!.spotHue!![7], 1e-4f)
        // SDR-Profil unverändert
        assertNull(back.profiles.getValue(ProfileId.SDR).activeMapId)
    }

    @Test
    fun darkenModeRoundTripAndRendersOnlyBlack() {
        val s = Settings().withCurrent { it.withMode(CorrectionMode.DARKEN).copy(darkenStrength = 0.4f) }
        val back = SettingsJson.decode(SettingsJson.encode(s))
        assertEquals(CorrectionMode.DARKEN, back.current.mode)
        assertEquals(0.4f, back.current.darkenStrength, 1e-6f)
        val px = de.edgefix.core.EdgeMaskRenderer.render(back.current.darkenParams(), 320, 180)
        // Rand abgedunkelt, aber ohne jede Farbe (Schwarz bleibt schwarz)
        org.junit.Assert.assertTrue(px.any { it != 0 })
        org.junit.Assert.assertTrue(px.all { (it and 0xffffff) == 0 })
        // Mitte unverändert
        assertEquals(0, px[90 * 320 + 160])
        // Zurück zu Farbausgleich: Karte bleibt unberührt, Modus wechselt
        assertEquals(CorrectionMode.COLOR, back.current.withMode(CorrectionMode.COLOR).mode)
    }

    @Test
    fun migratesVersion1ToBothProfiles() {
        val v1 = """{"version":1,"enabled":true,"autostart":false,"setupDone":true,"lastTuned":5,
            "params":{"aMax":0.2,"k":0.4,"hueDeg":80,"curve":"LINEAR"},
            "useMap":true,"activeMapId":"20261119-120000","mapStrength":1.2,"mapVariant":0,"lastCalibrated":7}"""
        val s = SettingsJson.decode(v1)
        assertEquals(false, s.autostart)
        for (id in ProfileId.entries) {
            val p = s.profiles.getValue(id)
            assertEquals(0.2f, p.params.aMax, 1e-6f)
            assertEquals("20261119-120000", p.activeMapId)
            assertEquals(0, p.mapVariant)
            assertEquals(7L, p.lastCalibrated)
        }
    }
}
