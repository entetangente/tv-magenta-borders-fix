package de.edgefix.core

import kotlin.math.floor

/**
 * Berechnet die Randmaske als Pixelfeld. Reine Funktion ohne Android-Abhängigkeit.
 *
 * Deckkraft je Kante e mit Abstand d_e (Pixelmitte zur Kante), Breite w_e und Stärke s_e:
 *
 *     a_e = min(1, a_max · s_e · f(max(0, 1 − d_e / w_e)))
 *
 * Die Kanten werden wie übereinanderliegende Folien verbunden:
 *
 *     a = 1 − (1 − a_oben)(1 − a_unten)(1 − a_links)(1 − a_rechts)
 *
 * Mit Detailform (Abgleich per Auge) hängen w_e, s_e und die Gegenfarbe H von
 * der Position entlang der Kante ab; die Farbe ist dann der mit a_e gewichtete
 * Mittelwert der Kantenfarben.
 *
 * Die Overlay-Farbe ist O = k · H mit der Gegenfarbe H. Ausgabe ist ARGB mit
 * vormultipliziertem Alpha, also (a, a·k·H_r, a·k·H_g, a·k·H_b).
 *
 * Leitprinzipien: Außerhalb der Randbereiche ist jeder Pixel exakt 0. Werte unter
 * einem halben Quantisierungsschritt werden zu 0. Dithering (Bayer 8×8) gibt es
 * nur auf Pixeln mit a > 0.
 */
object EdgeMaskRenderer {

    /** Sättigung der Gegenfarbe. 0.75 ergibt bei 75° etwa (0.81, 1.0, 0.25). */
    const val HUE_SATURATION = 0.75f

    private val BAYER8 = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42,
        48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38,
        60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41,
        51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37,
        63, 31, 55, 23, 61, 29, 53, 21,
    )

    /** Schwellwert im Bereich [−0.5, 0.5) für Pixel (x, y). */
    fun ditherOffset(x: Int, y: Int): Float = (BAYER8[(y and 7) * 8 + (x and 7)] + 0.5f) / 64f - 0.5f

    /** Gegenfarbe H als (r, g, b) im Bereich 0–1. */
    fun hueColor(hueDeg: Float, saturation: Float = HUE_SATURATION): FloatArray {
        val h = (((hueDeg % 360f) + 360f) % 360f) / 60f
        val c = saturation
        val x = c * (1f - kotlin.math.abs(h % 2f - 1f))
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = 1f - c
        return floatArrayOf(r + m, g + m, b + m)
    }

    /**
     * Werte einer Kante entlang ihrer Länge: Breite in Pixeln, Verstärkung
     * (a_max · Stärke · Spot-Verstärkung) und Gegenfarbe H je Position.
     */
    private class EdgeLine(val widthPx: FloatArray, val gain: FloatArray, val r: FloatArray, val g: FloatArray, val b: FloatArray) {
        val maxWidth = widthPx.maxOrNull() ?: 0f
    }

    private fun edgeLine(p: EdgeParams, e: Edge, length: Int, heightPx: Float): EdgeLine {
        val shape = p.shape
        val wArr = FloatArray(length)
        val gArr = FloatArray(length)
        val rArr = FloatArray(length)
        val gnArr = FloatArray(length)
        val bArr = FloatArray(length)
        val (s0, s1, s2) = Spot.along(e)
        val hues = shape?.spotHue
        val gains = shape?.spotGain
        val fixed = hueColor(p.hueDeg)
        for (i in 0 until length) {
            val t = (i + 0.5f) / length
            val width = shape?.width?.get(e)?.let { EdgeShape.spline(it, t) } ?: p.width.get(e)
            val strength = shape?.strength?.get(e)?.let { EdgeShape.spline(it, t) } ?: p.strength.get(e)
            val spotGain = gains?.let {
                if (t < 0.5f) it[s0.ordinal] + (it[s1.ordinal] - it[s0.ordinal]) * t * 2
                else it[s1.ordinal] + (it[s2.ordinal] - it[s1.ordinal]) * (t - 0.5f) * 2
            } ?: 1f
            wArr[i] = width * heightPx
            gArr[i] = p.aMax * strength * spotGain
            val c = hues?.let {
                val h = if (t < 0.5f) lerpHue(it[s0.ordinal], it[s1.ordinal], t * 2)
                else lerpHue(it[s1.ordinal], it[s2.ordinal], (t - 0.5f) * 2)
                hueColor(h)
            } ?: fixed
            rArr[i] = c[0]; gnArr[i] = c[1]; bArr[i] = c[2]
        }
        return EdgeLine(wArr, gArr, rArr, gnArr, bArr)
    }

    /** Deckkraft einer Kante bei Abstand d (Pixelmitte) an Position i. */
    private fun edgeAlpha(l: EdgeLine, i: Int, d: Float, curve: Curve): Float {
        val w = l.widthPx[i]
        if (w <= 0f) return 0f
        val t = 1f - d / w
        if (t <= 0f) return 0f
        return (l.gain[i] * curve.apply(t)).coerceAtMost(1f)
    }

    /**
     * Berechnet je Pixel Deckkraft und (optional) die gemischte Gegenfarbe.
     * [out] erhält für jeden Pixel mit a > 0: Index, a, H_r, H_g, H_b.
     */
    private inline fun compute(p0: EdgeParams, width: Int, height: Int, out: (i: Int, a: Float, r: Float, g: Float, b: Float) -> Unit) {
        val p = p0.clamped()
        val hPx = height.toFloat()
        val top = edgeLine(p, Edge.TOP, width, hPx)
        val bottom = edgeLine(p, Edge.BOTTOM, width, hPx)
        val left = edgeLine(p, Edge.LEFT, height, hPx)
        val right = edgeLine(p, Edge.RIGHT, height, hPx)
        val c = p.curve
        val lw = kotlin.math.ceil(left.maxWidth).toInt()
        val rw = kotlin.math.ceil(right.maxWidth).toInt()
        val tw = kotlin.math.ceil(top.maxWidth).toInt()
        val bw = kotlin.math.ceil(bottom.maxWidth).toInt()
        for (y in 0 until height) {
            val fullRow = y < tw || y >= height - bw
            var x = 0
            while (x < width) {
                // Außerhalb aller Randbereiche ist a exakt 0: diese Pixel überspringen.
                if (!fullRow && x >= lw && x < width - rw) { x = width - rw; continue }
                val aT = edgeAlpha(top, x, y + 0.5f, c)
                val aB = edgeAlpha(bottom, x, height - y - 0.5f, c)
                val aL = edgeAlpha(left, y, x + 0.5f, c)
                val aR = edgeAlpha(right, y, width - x - 0.5f, c)
                val rk = (1f - aT) * (1f - aB)
                val ck = (1f - aL) * (1f - aR)
                val a = 1f - rk * ck
                if (a > 0f) {
                    val s = aT + aB + aL + aR
                    val r = (aT * top.r[x] + aB * bottom.r[x] + aL * left.r[y] + aR * right.r[y]) / s
                    val g = (aT * top.g[x] + aB * bottom.g[x] + aL * left.g[y] + aR * right.g[y]) / s
                    val b = (aT * top.b[x] + aB * bottom.b[x] + aL * left.b[y] + aR * right.b[y]) / s
                    out(y * width + x, a, r, g, b)
                }
                x++
            }
        }
    }

    /**
     * Liefert nur die Deckkraft a (0–1) je Pixel, ohne Quantisierung (für Tests).
     */
    fun alpha(p: EdgeParams, width: Int, height: Int): FloatArray {
        val a = FloatArray(width * height)
        compute(p, width, height) { i, al, _, _, _ -> a[i] = al }
        return a
    }

    /**
     * Fertige Maske als ARGB-Ints mit vormultipliziertem Alpha (0xAARRGGBB).
     */
    fun render(p: EdgeParams, width: Int, height: Int): IntArray {
        val k = p.clamped().k
        val px = IntArray(width * height)
        val zeroBelow = 0.5f / 255f
        compute(p, width, height) { i, a, r, g, b ->
            if (a < zeroBelow) return@compute
            val x = i % width
            val y = i / width
            val d = ditherOffset(x, y)
            val a8 = q(a, d, 255)
            if (a8 == 0) return@compute
            px[i] = (a8 shl 24) or (q(a * k * r, d, a8) shl 16) or (q(a * k * g, d, a8) shl 8) or q(a * k * b, d, a8)
        }
        return px
    }

    /**
     * Quantisiert Deckkraft und vormultiplizierte Farbe auf 8 Bit mit Dithering.
     * [color] schreibt für Pixelindex i und Deckkraft a die vormultiplizierten
     * Kanäle (a·O_r, a·O_g, a·O_b) in out.
     */
    inline fun quantize(
        a: FloatArray,
        width: Int,
        height: Int,
        color: (index: Int, alpha: Float, out: FloatArray) -> Unit,
    ): IntArray {
        val px = IntArray(width * height)
        val c = FloatArray(3)
        val zeroBelow = 0.5f / 255f
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                val i = base + x
                val alpha = a[i]
                if (alpha < zeroBelow) continue // exakt 0: Bild bleibt unverändert
                val d = ditherOffset(x, y)
                val a8 = q(alpha, d, 255)
                if (a8 == 0) continue
                color(i, alpha, c)
                val r8 = q(c[0], d, a8)
                val g8 = q(c[1], d, a8)
                val b8 = q(c[2], d, a8)
                px[i] = (a8 shl 24) or (r8 shl 16) or (g8 shl 8) or b8
            }
        }
        return px
    }

    fun q(v: Float, d: Float, max: Int): Int {
        val r = floor(v * 255f + 0.5f + d).toInt()
        return if (r < 0) 0 else if (r > max) max else r
    }
}
