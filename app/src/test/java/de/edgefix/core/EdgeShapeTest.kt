package de.edgefix.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeShapeTest {
    private val w = 320
    private val h = 180

    @Test
    fun uniformShapeRendersExactlyLikeSimpleMask() {
        val p = EdgeParams(aMax = 0.2f, width = Edges(0.05f, 0.04f, 0.03f, 0.06f))
        val plain = EdgeMaskRenderer.render(p, w, h)
        val shaped = EdgeMaskRenderer.render(p.copy(shape = EdgeShape.from(p)), w, h)
        assertArrayEquals(plain, shaped)
    }

    @Test
    fun splineHitsControlPointsAndDoesNotOvershoot() {
        val pts = listOf(0f, 0f, 1f, 0f, 0f)
        assertEquals(1f, EdgeShape.spline(pts, 0.5f), 1e-6f)
        assertEquals(0f, EdgeShape.spline(pts, 0f), 1e-6f)
        for (i in 0..100) {
            val v = EdgeShape.spline(pts, i / 100f)
            assertTrue(v in 0f..1f)
        }
    }

    @Test
    fun widerPointMakesMaskWiderOnlyThere() {
        val p = EdgeParams(aMax = 0.2f, width = Edges.all(0.02f))
        val wide = EdgeShape.from(p).withPoint(Edge.TOP, 8, w = 0.08f)
        val a = EdgeMaskRenderer.alpha(p.copy(shape = wide), w, h)
        val depth = (0.05f * h).toInt() // tiefer als 2 %, aber innerhalb von 8 %
        val nearPoint = a[depth * w + (8f / 15f * w).toInt()]
        val farFromPoint = a[depth * w + 5]
        assertTrue("nahe am Punkt: $nearPoint", nearPoint > 0f)
        assertEquals(0f, farFromPoint, 0f)
    }

    @Test
    fun spotHuesChangeColorAlongTheEdge() {
        val p = EdgeParams(aMax = 0.3f, k = 1f, width = Edges.all(0.05f))
        val hues = listOf(120f, 120f, 120f, 120f, 120f, 120f, 120f, 120f).toMutableList()
        hues[Spot.TOP_RIGHT.ordinal] = 0f // rechts oben rot
        val px = EdgeMaskRenderer.render(p.copy(shape = EdgeShape.from(p).copy(spotHue = hues)), w, h)
        fun rg(x: Int, y: Int) = px[y * w + x].let { (it ushr 16 and 0xff) to (it ushr 8 and 0xff) }
        val (rLeft, gLeft) = rg(w / 4, 1)
        val (rRight, gRight) = rg(w - 3, 1)
        assertTrue("links grün", gLeft > rLeft)
        assertTrue("rechts rot", rRight > gRight)
    }

    @Test
    fun hueInterpolationTakesShortestWay() {
        assertEquals(0f, lerpHue(350f, 10f, 0.5f), 1e-4f)
        assertEquals(180f, lerpHue(170f, 190f, 0.5f), 1e-4f)
    }
}
