package de.edgefix.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/** Zeichnet nur das fertige Masken-Bitmap. Kein Fokus, keine Eingaben. */
@SuppressLint("ViewConstructor")
class OverlayView(
    context: Context,
    private val onSize: (width: Int, height: Int) -> Unit,
) : View(context) {

    private val paint = Paint().apply { isFilterBitmap = true; isDither = false }
    private val dst = Rect()
    private var bitmap: Bitmap? = null

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setBitmap(b: Bitmap?) {
        val old = bitmap
        bitmap = b
        invalidate()
        if (old != null && old !== b) post { old.recycle() }
    }

    /** Gibt das Bitmap ab, ohne es freizugeben (für das Neuanlegen des Fensters). */
    fun takeBitmap(): Bitmap? = bitmap.also { bitmap = null }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w > 0 && h > 0) onSize(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        dst.set(0, 0, width, height)
        canvas.drawBitmap(b, null, dst, paint)
    }
}
