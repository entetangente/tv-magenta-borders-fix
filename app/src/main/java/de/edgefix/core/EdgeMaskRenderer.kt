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

    /** Deckkraft-Profil entlang einer Achse für eine Kante. Index = Abstand in Pixeln. */
    private fun edgeProfile(length: Int, widthPx: Float, gain: Float, curve: Curve): FloatArray {
        val out = FloatArray(length)
        if (widthPx <= 0f || gain <= 0f) return out
        for (i in 0 until length) {
            val d = i + 0.5f
            val t = 1f - d / widthPx
            if (t <= 0f) break
            out[i] = (gain * curve.apply(t)).coerceAtMost(1f)
        }
        return out
    }

    /**
     * Liefert nur die Deckkraft a (0–1) je Pixel, ohne Quantisierung. Für Tests
     * und für spätere Kombination mit einer Korrekturkarte.
     */
    fun alpha(p: EdgeParams, width: Int, height: Int): FloatArray {
        val params = p.clamped()
        val rowKeep = FloatArray(height) { 1f }
        val colKeep = FloatArray(width) { 1f }
        val hPx = height.toFloat()
        val top = edgeProfile(height, params.width.top * hPx, params.aMax * params.strength.top, params.curve)
        val bottom = edgeProfile(height, params.width.bottom * hPx, params.aMax * params.strength.bottom, params.curve)
        val left = edgeProfile(width, params.width.left * hPx, params.aMax * params.strength.left, params.curve)
        val right = edgeProfile(width, params.width.right * hPx, params.aMax * params.strength.right, params.curve)
        for (y in 0 until height) rowKeep[y] = (1f - top[y]) * (1f - bottom[height - 1 - y])
        for (x in 0 until width) colKeep[x] = (1f - left[x]) * (1f - right[width - 1 - x])
        val a = FloatArray(width * height)
        for (y in 0 until height) {
            val rk = rowKeep[y]
            val base = y * width
            // Außerhalb aller Randbereiche ist rk = ck = 1 und damit a exakt 0.
            for (x in 0 until width) a[base + x] = 1f - rk * colKeep[x]
        }
        return a
    }

    /**
     * Fertige Maske als ARGB-Ints mit vormultipliziertem Alpha (0xAARRGGBB).
     */
    fun render(p: EdgeParams, width: Int, height: Int): IntArray {
        val params = p.clamped()
        val a = alpha(params, width, height)
        val hc = hueColor(params.hueDeg)
        val kr = params.k * hc[0]
        val kg = params.k * hc[1]
        val kb = params.k * hc[2]
        return quantize(a, width, height) { _, alpha, out ->
            out[0] = alpha * kr; out[1] = alpha * kg; out[2] = alpha * kb
        }
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
