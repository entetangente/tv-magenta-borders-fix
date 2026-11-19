package de.edgefix.adb

import android.content.Context
import android.util.Log
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
     * [log] erhält jeden Protokollschritt als kurze Zeile (für die Anzeige am Fernseher).
     */
    suspend fun run(context: Context, log: (String) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        val key = try {
            AdbKeys.loadOrCreate(context.filesDir)
        } catch (e: Exception) {
            Log.e(TAG, "ADB-Schlüssel", e)
            return@withContext Result.Failed("Schlüssel konnte nicht erzeugt werden: ${e.message}")
        }
        val logger: (String) -> Unit = { Log.i(TAG, "ADB: $it"); log(it) }
        val cmd = "appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow"
        // Bis zu drei Versuche: Manche adbd-Versionen trennen nach der Bestätigung
        // und erwarten eine neue Verbindung (dann ist der Schlüssel bekannt).
        var last: Exception? = null
        for (attempt in 1..3) {
            if (attempt > 1) logger("— neuer Versuch $attempt —")
            try {
                AdbClient("127.0.0.1", 5555, key, logger).use { c ->
                    c.connect()
                    val out = c.shell(cmd).trim()
                    if (out.isNotEmpty()) {
                        return@withContext Result.Failed("Befehl meldet: $out")
                    }
                }
                last = null
                break
            } catch (e: ConnectException) {
                logger("Port 5555 nicht erreichbar: ${e.message}")
                return@withContext Result.AdbOff
            } catch (e: SocketTimeoutException) {
                logger("Zeitüberschreitung")
                return@withContext Result.NotAuthorized
            } catch (e: Exception) {
                Log.w(TAG, "ADB Versuch $attempt", e)
                logger("✗ ${e.message ?: e.javaClass.simpleName}")
                last = e
                delay(1500)
            }
        }
        if (last != null) return@withContext Result.Failed(last.message ?: last.javaClass.simpleName)
        // Die Änderung kann einen Moment brauchen, bis canDrawOverlays sie meldet.
        repeat(10) {
            if (OverlayPermission.granted(context)) return@withContext Result.Success
            delay(300)
        }
        Result.Failed("Der Befehl lief durch, die Berechtigung ist aber noch nicht aktiv. Bitte die App einmal neu starten.")
    }
}
