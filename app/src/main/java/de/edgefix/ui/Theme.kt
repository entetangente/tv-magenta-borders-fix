package de.edgefix.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val Accent = Color(0xFF8BD17C)

@Composable
fun EdgeFixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color(0xFF0E1A0B),
            background = Color(0xFF101214),
            surface = Color(0xFF1B1E22),
            onSurface = Color(0xFFE8EAED),
            surfaceVariant = Color(0xFF262A2F),
            onSurfaceVariant = Color(0xFFB8BEC6),
        ),
        content = content,
    )
}
