package de.edgefix.adb

import android.content.Context
import android.util.Log
import dadb.Dadb
import de.edgefix.overlay.OverlayPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * Richtet die Overlay-Berechtigung ohne PC ein: Die App verbindet sich mit dem
 * ADB-Dienst des eigenen Geräts (127.0.0.1:5555) und führt dort
 * `appops set <paket> SYSTEM_ALERT_WINDOW allow` aus.
 */
object AdbSelfGrant {
    private const val TAG = "EdgeFix"

    sealed interface Result {
        data object Success : Result
        data object AdbOff : Result
        data object NotAuthorized : Result
        data class Failed(val message: String) : Result
    }

    /**
     * [onWaitingForConfirm] wird aufgerufen, sobald die Verbindung steht und der
     * Fire TV ggf. „Debugging zulassen?“ anzeigt.
     */
    suspend fun run(context: Context, onWaitingForConfirm: () -> Unit = {}): Result = withContext(Dispatchers.IO) {
        val keys = try {
            AdbKeys.loadOrCreate(context.filesDir)
        } catch (e: Exception) {
            Log.e(TAG, "ADB-Schlüssel", e)
            return@withContext Result.Failed("Schlüssel konnte nicht erzeugt werden: ${e.message}")
        }
        // Erst prüfen, ob überhaupt jemand auf Port 5555 lauscht.
        try {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", 5555), 2000) }
        } catch (e: Exception) {
            return@withContext Result.AdbOff
        }
        onWaitingForConfirm()
        val cmd = "appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow"
        try {
            // Großzügiger Socket-Timeout: Der Nutzer muss am Fernseher erst bestätigen.
            Dadb.create("127.0.0.1", 5555, keys, 5_000, 120_000).use { dadb ->
                val r = dadb.shell(cmd)
                Log.i(TAG, "appops: exit=${r.exitCode} out=${r.allOutput}")
                if (r.exitCode != 0) {
                    return@withContext Result.Failed("Befehl fehlgeschlagen (${r.exitCode}): ${r.allOutput.trim()}")
                }
            }
        } catch (e: ConnectException) {
            return@withContext Result.AdbOff
        } catch (e: SocketTimeoutException) {
            return@withContext Result.NotAuthorized
        } catch (e: Exception) {
            Log.e(TAG, "ADB", e)
            val msg = e.message ?: e.javaClass.simpleName
            return@withContext if (msg.contains("auth", ignoreCase = true)) Result.NotAuthorized else Result.Failed(msg)
        }
        // Die Änderung kann einen Moment brauchen, bis canDrawOverlays sie meldet.
        repeat(10) {
            if (OverlayPermission.granted(context)) return@withContext Result.Success
            delay(300)
        }
        Result.Failed("Der Befehl lief durch, die Berechtigung ist aber noch nicht aktiv. Bitte die App einmal neu starten.")
    }
}
