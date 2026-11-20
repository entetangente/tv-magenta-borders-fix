package de.edgefix.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.android.resources.UiMode
import de.edgefix.store.SettingsRepository
import org.junit.Rule
import org.junit.Test

/**
 * Bildschirmfotos der TV-Oberfläche in Fire-TV-Auflösung (1920×1080, xhdpi =
 * 960×540 dp). Prüft vor allem, dass nichts abgeschnitten oder unsichtbar ist.
 * Ausgabe: app/src/test/snapshots/images (mit recordPaparazziDebug).
 */
class ScreensSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 1920, screenHeight = 1080, density = Density.XHIGH, xdpi = 320, ydpi = 320,
            orientation = ScreenOrientation.LANDSCAPE, uiMode = UiMode.TELEVISION,
        ),
        showSystemUi = false,
        maxPercentDifference = 1.0,
    )

    private fun shot(content: @Composable (SettingsRepository) -> Unit) {
        paparazzi.snapshot {
            val repo = SettingsRepository.get(paparazzi.context)
            EdgeFixTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    colors = SurfaceDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) { Box(Modifier.fillMaxSize()) { content(repo) } }
            }
        }
    }

    @Test fun home() = shot { HomeScreen(it, {}, {}, {}) }
    @Test fun settings() = shot { SettingsScreen(it, {}, {}, {}, {}) }
    @Test fun tuning() = shot { TuningScreen(it, {}, {}) }
    @Test fun setup() = shot { SetupScreen(it, startStep = 1, onDone = {}, onExit = null) }
    @Test fun quickToggle() = shot { QuickToggleScreen() }
    @Test fun shapeEditor() = shot { ShapeEditorScreen(it) }
    @Test fun hueMatch() = shot { HueMatchScreen(it) {} }
    @Test fun diagnostics() = shot { DiagnosticsScreen(it) }
}
