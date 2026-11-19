package de.edgefix.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Misst die Rechenzeit in Fire-TV-Auflösung und schreibt eine Vorschau
 * (Maske über Weiß und über Schwarz, PPM-Format) nach app/build/preview/.
 */
class RenderPreviewTest {
    @Test
    fun fullHdRenderIsFastAndPreviewIsWritten() {
        val w = 1920
        val h = 1080
        val p = EdgeParams(aMax = 0.3f) // kräftig, damit man in der Vorschau etwas sieht
        EdgeMaskRenderer.render(p, w, h) // Aufwärmen
        val t0 = System.nanoTime()
        val px = EdgeMaskRenderer.render(p, w, h)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("Render ${w}x$h: $ms ms")
        assertTrue("zu langsam: $ms ms", ms < 1000)

        val dir = File("build/preview").apply { mkdirs() }
        for ((name, bg) in listOf("weiss" to 255, "schwarz" to 0)) {
            val out = ByteArray(w * h * 3)
            for (i in px.indices) {
                val v = px[i]
                val a = (v ushr 24) / 255f
                fun ch(shift: Int) = ((v ushr shift and 0xff) + (1 - a) * bg).toInt().coerceIn(0, 255).toByte()
                out[i * 3] = ch(16); out[i * 3 + 1] = ch(8); out[i * 3 + 2] = ch(0)
            }
            File(dir, "maske_$name.ppm").writeBytes("P6\n$w $h\n255\n".toByteArray() + out)
        }
    }
}
