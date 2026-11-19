package de.edgefix.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gemessene Korrekturkarte (Raster gw×gh) mit mehreren Varianten für den
 * Schwarz-Schutz. Je Zelle: Deckkraft a und vormultiplizierte Farbe P = a·O.
 *
 * Dateiformat (Little Endian), erzeugt von der Web-App:
 *   "EFM1" | u16 gw | u16 gh | u8 Varianten | 3 Byte frei
 *   je Variante gw·gh × (f32 a, f32 P_r, f32 P_g, f32 P_b)
 */
class CorrectionMap(
    val gw: Int,
    val gh: Int,
    /** je Variante: 4 Werte je Zelle (a, P_r, P_g, P_b) */
    val variants: List<FloatArray>,
) {
    init {
        require(variants.isNotEmpty() && variants.all { it.size == gw * gh * 4 })
    }

    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024

        fun parse(bytes: ByteArray): CorrectionMap {
            require(bytes.size >= 12) { "Datei zu kurz" }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = String(bytes, 0, 4, Charsets.US_ASCII)
            require(magic == "EFM1") { "Unbekanntes Format" }
            val gw = b.getShort(4).toInt() and 0xffff
            val gh = b.getShort(6).toInt() and 0xffff
            val count = bytes[8].toInt() and 0xff
            require(gw in 2..1024 && gh in 2..1024 && count in 1..8) { "Ungültige Größe" }
            val n = gw * gh * 4
            require(bytes.size == 12 + count * n * 4) { "Falsche Dateilänge" }
            b.position(12)
            val variants = List(count) {
                FloatArray(n) { i ->
                    val v = b.float
                    if (v.isNaN()) 0f else v
                }.also { sanitize(it) }
            }
            return CorrectionMap(gw, gh, variants)
        }

        /** Werte begrenzen: 0 ≤ a ≤ 1, 0 ≤ P ≤ a. */
        private fun sanitize(v: FloatArray) {
            var i = 0
            while (i < v.size) {
                val a = v[i].coerceIn(0f, 1f)
                v[i] = a
                for (c in 1..3) v[i + c] = v[i + c].coerceIn(0f, a)
                i += 4
            }
        }
    }
}

/** Skaliert eine Kartenvariante bilinear auf Bildschirmgröße und quantisiert mit Dithering. */
object CorrectionMapRenderer {

    fun render(map: CorrectionMap, variant: Int, strength: Float, width: Int, height: Int): IntArray {
        val v = map.variants[variant.coerceIn(0, map.variants.size - 1)]
        val gw = map.gw
        val gh = map.gh
        val s = strength.coerceIn(0f, 2f)
        val px = IntArray(width * height)
        val zeroBelow = 0.5f / 255f
        // Spaltenweise Vorberechnung der Interpolationsgewichte
        val x0s = IntArray(width)
        val txs = FloatArray(width)
        for (x in 0 until width) {
            val fx = ((x + 0.5f) / width * gw - 0.5f).coerceIn(0f, gw - 1.0001f)
            x0s[x] = fx.toInt()
            txs[x] = fx - x0s[x]
        }
        for (y in 0 until height) {
            val fy = ((y + 0.5f) / height * gh - 0.5f).coerceIn(0f, gh - 1.0001f)
            val y0 = fy.toInt()
            val ty = fy - y0
            val r0 = y0 * gw
            val r1 = (y0 + 1) * gw
            for (x in 0 until width) {
                val x0 = x0s[x]
                val tx = txs[x]
                val i00 = (r0 + x0) * 4
                val i10 = i00 + 4
                val i01 = (r1 + x0) * 4
                val i11 = i01 + 4
                // Schneller Weg: alle vier Nachbarn 0 → Pixel exakt 0
                if (v[i00] == 0f && v[i10] == 0f && v[i01] == 0f && v[i11] == 0f) continue
                val w00 = (1 - tx) * (1 - ty)
                val w10 = tx * (1 - ty)
                val w01 = (1 - tx) * ty
                val w11 = tx * ty
                var a = (w00 * v[i00] + w10 * v[i10] + w01 * v[i01] + w11 * v[i11]) * s
                var k = s
                if (a > 1f) { k = s / a; a = 1f }
                if (a < zeroBelow) continue
                val d = EdgeMaskRenderer.ditherOffset(x, y)
                val a8 = EdgeMaskRenderer.q(a, d, 255)
                if (a8 == 0) continue
                val pr = (w00 * v[i00 + 1] + w10 * v[i10 + 1] + w01 * v[i01 + 1] + w11 * v[i11 + 1]) * k
                val pg = (w00 * v[i00 + 2] + w10 * v[i10 + 2] + w01 * v[i01 + 2] + w11 * v[i11 + 2]) * k
                val pb = (w00 * v[i00 + 3] + w10 * v[i10 + 3] + w01 * v[i01 + 3] + w11 * v[i11 + 3]) * k
                px[y * width + x] = (a8 shl 24) or
                    (EdgeMaskRenderer.q(pr, d, a8) shl 16) or
                    (EdgeMaskRenderer.q(pg, d, a8) shl 8) or
                    EdgeMaskRenderer.q(pb, d, a8)
            }
        }
        return px
    }
}
