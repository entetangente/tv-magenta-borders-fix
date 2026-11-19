package de.edgefix.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeMaskRendererTest {
    private val w = 320
    private val h = 180

    @Test
    fun centerIsExactlyZero() {
        val px = EdgeMaskRenderer.render(EdgeParams(), w, h)
        val wPx = (0.06f * h).toInt() + 1
        for (y in wPx until h - wPx) for (x in wPx until w - wPx) {
            assertEquals("Pixel $x,$y", 0, px[y * w + x])
        }
    }

    @Test
    fun zeroStrengthGivesEmptyMask() {
        val px = EdgeMaskRenderer.render(EdgeParams(aMax = 0f), w, h)
        assertTrue(px.all { it == 0 })
    }

    @Test
    fun alphaIsMonotonicTowardsEdge() {
        val a = EdgeMaskRenderer.alpha(EdgeParams(), w, h)
        val x = w / 2
        for (y in 1 until h / 2) {
            assertTrue("y=$y", a[(y - 1) * w + x] >= a[y * w + x])
        }
    }

    @Test
    fun cornersAreStrongerThanEdgeMiddles() {
        val a = EdgeMaskRenderer.alpha(EdgeParams(), w, h)
        assertTrue(a[0] > a[w / 2])
        assertTrue(a[0] > a[(h / 2) * w])
    }

    @Test
    fun maxAlphaMatchesAMaxAtEdgeMiddle() {
        val p = EdgeParams(aMax = 0.2f, curve = Curve.LINEAR)
        val a = EdgeMaskRenderer.alpha(p, w, h)
        val top = a[w / 2] // Abstand 0.5 px zur Oberkante
        assertEquals(0.2f * (1f - 0.5f / (0.06f * h)), top, 1e-4f)
    }

    @Test
    fun symmetricForSymmetricParams() {
        val a = EdgeMaskRenderer.alpha(EdgeParams(), w, h)
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals(a[y * w + x], a[y * w + (w - 1 - x)], 1e-6f)
            assertEquals(a[y * w + x], a[(h - 1 - y) * w + x], 1e-6f)
        }
    }

    @Test
    fun premultipliedChannelsNeverExceedAlpha() {
        val px = EdgeMaskRenderer.render(EdgeParams(aMax = 0.4f, k = 1f, strength = Edges.all(1.5f)), w, h)
        for (v in px) {
            val a = v ushr 24
            assertTrue((v ushr 16 and 0xff) <= a)
            assertTrue((v ushr 8 and 0xff) <= a)
            assertTrue((v and 0xff) <= a)
        }
    }

    @Test
    fun kZeroIsPureDarkening() {
        val px = EdgeMaskRenderer.render(EdgeParams(k = 0f), w, h)
        assertTrue(px.any { it != 0 })
        assertTrue(px.all { (it and 0xffffff) == 0 })
    }

    @Test
    fun ditheredAverageMatchesTarget() {
        // Fläche mit konstanter Deckkraft: Mittelwert der 8-Bit-Werte muss a·255 treffen.
        val a = FloatArray(64 * 64) { 0.05f }
        val px = EdgeMaskRenderer.quantize(a, 64, 64) { _, al, out -> out[0] = 0f; out[1] = al; out[2] = 0f }
        val mean = px.map { it ushr 24 }.average()
        assertEquals(0.05 * 255, mean, 0.05)
        // Dithering erzeugt mindestens zwei verschiedene Stufen
        assertTrue(px.map { it ushr 24 }.toSet().size >= 2)
    }

    @Test
    fun tinyAlphaBecomesExactZero() {
        val a = FloatArray(16) { 0.4f / 255f }
        val px = EdgeMaskRenderer.quantize(a, 4, 4) { _, _, out -> out.fill(0f) }
        assertTrue(px.all { it == 0 })
    }

    @Test
    fun hueColorAt75IsYellowGreen() {
        val c = EdgeMaskRenderer.hueColor(75f)
        assertEquals(1f, c[1], 1e-5f)
        assertTrue(c[0] in 0.7f..0.9f)
        assertEquals(0.25f, c[2], 1e-5f)
    }
}
