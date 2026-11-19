package de.edgefix.overlay

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

object MaskBitmap {
    /**
     * Macht aus vormultiplizierten ARGB-Ints ein Bitmap, ohne dass Android die
     * Werte erneut umrechnet (Bitmap.createBitmap(int[]) erwartet nicht
     * vormultiplizierte Farben und würde die Quantisierung verfälschen).
     */
    fun fromPremultiplied(px: IntArray, width: Int, height: Int): Bitmap {
        val buf = ByteBuffer.allocateDirect(px.size * 4).order(ByteOrder.nativeOrder())
        val ints = buf.asIntBuffer()
        val little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
        for (v in px) {
            // Speicherlayout von ARGB_8888 ist R, G, B, A (Byte für Byte).
            val a = v ushr 24 and 0xff
            val r = v ushr 16 and 0xff
            val g = v ushr 8 and 0xff
            val b = v and 0xff
            ints.put(
                if (little) (a shl 24) or (b shl 16) or (g shl 8) or r
                else (r shl 24) or (g shl 16) or (b shl 8) or a,
            )
        }
        buf.rewind()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setHasAlpha(true)
        bmp.copyPixelsFromBuffer(buf)
        return bmp
    }
}
