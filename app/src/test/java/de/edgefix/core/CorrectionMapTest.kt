package de.edgefix.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionMapTest {
    /** sample.efm wird von der Web-App erzeugt (web/test/fixture.test.ts). */
    private fun sample(): CorrectionMap {
        val bytes = javaClass.classLoader!!.getResourceAsStream("sample.efm").readBytes()
        return CorrectionMap.parse(bytes)
    }

    @Test
    fun parsesWebAppFormat() {
        val m = sample()
        assertEquals(8, m.gw)
        assertEquals(5, m.gh)
        assertEquals(2, m.variants.size)
        assertEquals(0.1f, m.variants[0][0], 1e-6f)
        assertEquals(0.08f, m.variants[0][2], 1e-6f) // P_g = a·0.8
        assertEquals(0.2f, m.variants[1][0], 1e-6f)
        assertEquals(0f, m.variants[0][4], 0f) // Spalte 1: a = 0
    }

    @Test
    fun renderKeepsUnaffectedPixelsExactlyZero() {
        val m = sample()
        val w = 160
        val h = 100
        val px = CorrectionMapRenderer.render(m, 1, 1f, w, h)
        // Ab der Mitte der zweiten Zellspalte haben alle Nachbarzellen a = 0.
        val firstZeroX = (1.5f * w / m.gw).toInt() + 1
        for (y in 0 until h) for (x in firstZeroX until w) assertEquals(0, px[y * w + x])
        // Linker Rand ist korrigiert
        assertTrue(px.indices.filter { it % w == 0 }.all { px[it] != 0 })
    }

    @Test
    fun strengthScalesAlphaAndChannelsStayBelowAlpha() {
        val m = sample()
        val one = CorrectionMapRenderer.render(m, 0, 1f, 80, 50)
        val half = CorrectionMapRenderer.render(m, 0, 0.5f, 80, 50)
        val meanA = { p: IntArray -> p.filter { it != 0 }.map { it ushr 24 }.average() }
        assertEquals(meanA(one) / 2, meanA(half), 1.0)
        for (v in one) {
            val a = v ushr 24
            assertTrue((v ushr 16 and 0xff) <= a && (v ushr 8 and 0xff) <= a && (v and 0xff) <= a)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsGarbage() {
        CorrectionMap.parse(ByteArray(64))
    }
}
