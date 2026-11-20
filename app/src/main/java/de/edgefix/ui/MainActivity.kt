package de.edgefix.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import de.edgefix.overlay.OverlayService
import de.edgefix.store.SettingsRepository

enum class Screen(val parent: Screen?) {
    SETUP(null), HOME(null), TUNING(HOME), SETTINGS(HOME), CALIBRATION(HOME),
    DIAGNOSTICS(SETTINGS), QUICK_TOGGLE(SETTINGS), SHAPE(TUNING), HUE(TUNING),
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = SettingsRepository.get(this)
        setContent {
            EdgeFixTheme {
                val settings by repo.settings.collectAsStateWithLifecycle()
                var screen by rememberSaveable {
                    mutableStateOf(if (settings.setupDone) Screen.HOME else Screen.SETUP)
                }
                var setupStartStep by rememberSaveable { mutableStateOf(0) }
                BackHandler(enabled = screen.parent != null) {
                    screen = screen.parent ?: Screen.HOME
                }
                // Surface setzt die Textfarbe für alles darin. Ohne sie zeichnet
                // tv-material Text schwarz (auf dunklem Hintergrund unsichtbar).
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    colors = SurfaceDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    when (screen) {
                        Screen.SETUP -> SetupScreen(
                            repo = repo,
                            startStep = setupStartStep,
                            onDone = {
                                repo.update { it.copy(setupDone = true) }
                                OverlayService.sync(this@MainActivity)
                                screen = Screen.HOME
                            },
                            onExit = if (settings.setupDone) ({ screen = Screen.SETTINGS }) else null,
                        )
                        Screen.HOME -> HomeScreen(
                            repo = repo,
                            onTuning = { screen = Screen.TUNING },
                            onCalibrate = { screen = Screen.CALIBRATION },
                            onSettings = { screen = Screen.SETTINGS },
                        )
                        Screen.TUNING -> TuningScreen(
                            repo = repo,
                            onShape = { screen = Screen.SHAPE },
                            onHue = { screen = Screen.HUE },
                        )
                        Screen.SHAPE -> ShapeEditorScreen(repo = repo)
                        Screen.HUE -> HueMatchScreen(repo = repo, onDone = { screen = Screen.TUNING })
                        Screen.QUICK_TOGGLE -> QuickToggleScreen()
                        Screen.CALIBRATION -> CalibrationScreen(repo = repo)
                        Screen.DIAGNOSTICS -> DiagnosticsScreen(repo = repo)
                        Screen.SETTINGS -> SettingsScreen(
                            repo = repo,
                            onPermission = { setupStartStep = 1; screen = Screen.SETUP },
                            onRestartSetup = { setupStartStep = 0; screen = Screen.SETUP },
                            onDiagnostics = { screen = Screen.DIAGNOSTICS },
                            onQuickToggle = { screen = Screen.QUICK_TOGGLE },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Nach Rückkehr aus den Systemeinstellungen: Dienst passend starten.
        if (SettingsRepository.get(this).settings.value.setupDone) OverlayService.sync(this)
    }

    override fun onPause() {
        super.onPause()
        SettingsRepository.get(this).setBypass(false)
    }
}
