package de.edgefix.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

enum class TestPattern(val label: String) {
    WHITE("Weiß"),
    GRAY75("Grau 75 %"),
    GRAY50("Grau 50 %"),
    GRAY20("Grau 20 %"),
    BLACK("Schwarz"),
    RAMP("Graukeil"),
    GRID("Weiß mit Raster"),
}

private fun gray(v: Float) = Color(v, v, v)

@Composable
fun TestPatternView(pattern: TestPattern, modifier: Modifier = Modifier.fillMaxSize()) {
    Canvas(modifier) {
        when (pattern) {
            TestPattern.WHITE -> drawRect(Color.White)
            TestPattern.GRAY75 -> drawRect(gray(0.75f))
            TestPattern.GRAY50 -> drawRect(gray(0.5f))
            TestPattern.GRAY20 -> drawRect(gray(0.2f))
            TestPattern.BLACK -> drawRect(Color.Black)
            TestPattern.RAMP -> {
                // 16 senkrechte Streifen von Schwarz bis Weiß
                val n = 16
                val w = size.width / n
                for (i in 0 until n) {
                    drawRect(gray(i / (n - 1f)), topLeft = Offset(i * w, 0f), size = Size(w + 1f, size.height))
                }
            }
            TestPattern.GRID -> {
                drawRect(Color.White)
                // Linien alle 2 % der Bildhöhe in Randnähe, dazwischen alle 10 %
                val h = size.height
                val line = Color(0xFF808080)
                val stroke = 2f
                var p = 0f
                while (p <= 0.2f + 1e-4f) {
                    val d = p * h
                    drawLine(line, Offset(0f, d), Offset(size.width, d), stroke)
                    drawLine(line, Offset(0f, h - d), Offset(size.width, h - d), stroke)
                    drawLine(line, Offset(d, 0f), Offset(d, h), stroke)
                    drawLine(line, Offset(size.width - d, 0f), Offset(size.width - d, h), stroke)
                    p += 0.02f
                }
            }
        }
    }
}
