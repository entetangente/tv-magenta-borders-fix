package de.edgefix.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text

private val RowShape = RoundedCornerShape(12.dp)

@Composable
private fun rowColors() = ClickableSurfaceDefaults.colors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    focusedContentColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
private fun rowBorder() = ClickableSurfaceDefaults.border(
    focusedBorder = Border(BorderStroke(3.dp, Accent), shape = RowShape),
)

/** Zeile mit Wert, der mit ◀ ▶ verstellt wird. [fraction] zeigt die Position als Balken. */
@Composable
fun ValueRow(
    label: String,
    value: String,
    fraction: Float,
    onStep: (direction: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = {},
        modifier = modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { onStep(-1); true }
                    Key.DirectionRight -> { onStep(+1); true }
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(RowShape),
        colors = rowColors(),
        border = rowBorder(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 22.sp, modifier = Modifier.weight(1f))
                Text("◀  $value  ▶", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(3.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(Accent, RoundedCornerShape(3.dp)),
                )
            }
        }
    }
}

/** Auswahl aus mehreren Optionen, mit ◀ ▶ oder OK weiterschalten. */
@Composable
fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val idx = options.indexOf(selected).coerceAtLeast(0)
    fun step(d: Int) = onSelect(options[(idx + d + options.size) % options.size])
    Surface(
        onClick = { step(+1) },
        modifier = modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { step(-1); true }
                    Key.DirectionRight -> { step(+1); true }
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(RowShape),
        colors = rowColors(),
        border = rowBorder(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 22.sp, modifier = Modifier.weight(1f))
            Text("◀  ${optionLabel(selected)}  ▶", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Zeile mit Schalter, OK schaltet um. */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    Surface(
        onClick = onToggle,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RowShape),
        colors = rowColors(),
        border = rowBorder(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 22.sp)
                if (description != null) {
                    Text(description, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

/** Einfache Aktionszeile (z. B. „Zurücksetzen“). */
@Composable
fun ActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RowShape),
        colors = rowColors(),
        border = rowBorder(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(label, fontSize = 22.sp)
            if (description != null) {
                Text(description, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Großer, fokussierbarer Knopf für Hauptaktionen. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = if (primary) {
            ClickableSurfaceDefaults.colors(
                containerColor = Accent.copy(alpha = 0.85f),
                contentColor = Color(0xFF0E1A0B),
                focusedContainerColor = Accent,
                focusedContentColor = Color(0xFF0E1A0B),
            )
        } else {
            rowColors()
        },
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Text(
            text,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
        )
    }
}

/** Zeile am unteren Rand: welche Taste was tut. */
@Composable
fun KeyHints(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 20.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun ScreenTitle(text: String, sub: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        if (sub != null) Text(sub, fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
