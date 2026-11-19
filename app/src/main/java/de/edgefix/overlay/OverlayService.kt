package de.edgefix.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import de.edgefix.R
import de.edgefix.core.CorrectionMap
import de.edgefix.core.CorrectionMapRenderer
import de.edgefix.core.EdgeMaskRenderer
import de.edgefix.core.EdgeParams
import de.edgefix.store.CalibrationOverlay
import de.edgefix.store.CalibrationState
import de.edgefix.store.MapStore
import de.edgefix.store.Settings
import de.edgefix.store.SettingsRepository
import de.edgefix.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Vordergrunddienst, der das Overlay-Fenster hält. Läuft, solange die Korrektur
 * eingeschaltet ist, und beendet sich selbst, wenn sie ausgeschaltet wird.
 */
class OverlayService : LifecycleService() {

    private lateinit var repo: SettingsRepository
    private lateinit var mapStore: MapStore
    private lateinit var wm: WindowManager
    private var view: OverlayView? = null
    private val size = MutableStateFlow<Pair<Int, Int>?>(null)

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Nach dem Standby das Fenster neu anlegen, damit es sicher wieder oben liegt.
            reattach()
        }
    }

    override fun onCreate() {
        super.onCreate()
        repo = SettingsRepository.get(this)
        mapStore = MapStore(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        goForeground()

        diag("Dienst gestartet") { it.copy(serviceStarted = it.serviceStarted + 1) }
        if (!OverlayPermission.granted(this)) {
            Log.w(TAG, "Keine Overlay-Berechtigung, Dienst wird beendet")
            diag("Keine Overlay-Berechtigung, Dienst beendet") { it }
            _running.value = false
            stopSelf()
            return
        }
        _running.value = true

        ContextCompat.registerReceiver(
            this, screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        attach()

        // Ausschalten beendet den Dienst (außer während der Kalibrierung).
        lifecycleScope.launch {
            combine(repo.settings.map { it.enabled }, repo.calibration.map { it != null }, repo.testFrame) { e, c, t -> e || c || t }
                .distinctUntilChanged()
                .collect { keep -> if (!keep) stopSelf() }
        }
        // Vorher/Nachher: Fenster nur ausblenden, Maske bleibt erhalten.
        lifecycleScope.launch {
            repo.bypass.collect { b -> view?.visibility = if (b) android.view.View.INVISIBLE else android.view.View.VISIBLE }
        }
        // Gespeicherte Karte laden, wenn sich die aktive Karte ändert.
        val activeMap = repo.settings.map { it.activeMapId }.distinctUntilChanged().map { id ->
            id?.let { withContext(Dispatchers.IO) { mapStore.load(it) } }
        }
        val source = combine(repo.settings, repo.calibration, activeMap, repo.testFrame) { s, cal, map, test ->
            if (test) MaskSource.TestFrame else maskSource(s, cal, map)
        }.distinctUntilChanged()
        // Maske bei jeder Änderung von Quelle oder Größe einmal neu berechnen.
        lifecycleScope.launch {
            combine(source, size.filterNotNull()) { src, sz -> src to sz }
                .collectLatest { (src, sz) ->
                    val (w, h) = sz
                    val bmp = try {
                        withContext(Dispatchers.Default) {
                            val t0 = System.nanoTime()
                            val px = when (src) {
                                MaskSource.None -> null
                                MaskSource.TestFrame -> testFrame(w, h)
                                is MaskSource.Param -> EdgeMaskRenderer.render(src.params, w, h)
                                is MaskSource.Map -> CorrectionMapRenderer.render(src.map, src.variant, src.strength, w, h)
                            }
                            var nonZero = 0
                            var maxA = 0
                            px?.forEach { v -> if (v != 0) { nonZero++; maxA = maxOf(maxA, v ushr 24) } }
                            val ms = (System.nanoTime() - t0) / 1_000_000
                            diag("Maske berechnet: $src") {
                                it.copy(size = "${w}×$h", source = src.toString(), nonZeroPixels = nonZero, maxAlpha = maxA, renderMs = ms)
                            }
                            px?.let { MaskBitmap.fromPremultiplied(it, w, h) }
                        }
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e(TAG, "Maske", e)
                        diag("Fehler beim Berechnen: ${e.javaClass.simpleName}: ${e.message}") { it }
                        null
                    }
                    val v = view
                    if (v != null) {
                        v.setBitmap(bmp)
                        diag("Bitmap gesetzt (${if (bmp == null) "leer" else "${bmp.width}×${bmp.height}"}), sichtbar=${v.visibility == android.view.View.VISIBLE}, angehängt=${v.isAttachedToWindow}") { it }
                    } else {
                        bmp?.recycle()
                        diag("Kein Overlay-Fenster vorhanden, Maske verworfen") { it }
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Neue Auflösung: Fenster neu anlegen, onSizeChanged liefert die neue Größe.
        reattach()
    }

    override fun onDestroy() {
        _running.value = false
        diag("Dienst beendet") { it.copy(windowAdded = false) }
        runCatching { unregisterReceiver(screenOnReceiver) }
        detach()
        super.onDestroy()
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "EdgeFix"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun attach() {
        if (view != null) return
        val v = OverlayView(this) { w, h ->
            diag("Fenstergröße gemeldet: ${w}×$h") { it }
            size.value = w to h
        }
        v.visibility = if (repo.bypass.value) android.view.View.INVISIBLE else android.view.View.VISIBLE
        try {
            wm.addView(v, layoutParams())
            view = v
            diag("Overlay-Fenster angelegt") { it.copy(windowAdded = true, windowError = null) }
        } catch (e: Exception) {
            Log.e(TAG, "Overlay-Fenster konnte nicht angelegt werden", e)
            diag("Overlay-Fenster FEHLER: ${e.javaClass.simpleName}: ${e.message}") {
                it.copy(windowAdded = false, windowError = "${e.javaClass.simpleName}: ${e.message}")
            }
            stopSelf()
        }
    }

    private fun detach() {
        val v = view ?: return
        view = null
        runCatching { wm.removeViewImmediate(v) }
        v.setBitmap(null)
    }

    private fun reattach() {
        val old = view ?: return
        // Bitmap behalten, damit nach dem Wiederanlegen sofort die Maske zu sehen ist.
        // Bleibt die Größe gleich, wird nicht neu gerechnet; ändert sie sich,
        // löst onSizeChanged eine neue Berechnung aus.
        val bmp = old.takeBitmap()
        view = null
        runCatching { wm.removeViewImmediate(old) }
        attach()
        view?.setBitmap(bmp) ?: bmp?.recycle()
    }

    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Randkorrektur", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                },
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("EdgeFix")
            .setContentText("Randkorrektur aktiv")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pi)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    private sealed interface MaskSource {
        data object None : MaskSource
        data object TestFrame : MaskSource
        data class Param(val params: EdgeParams) : MaskSource
        data class Map(val map: CorrectionMap, val variant: Int, val strength: Float) : MaskSource {
            override fun toString() = "Karte v$variant s$strength"
        }
    }

    private fun maskSource(s: Settings, cal: CalibrationState?, active: CorrectionMap?): MaskSource {
        if (cal != null) {
            when (cal.overlay) {
                CalibrationOverlay.OFF -> return MaskSource.None
                CalibrationOverlay.PREVIEW -> cal.previewMap?.let {
                    return MaskSource.Map(it, cal.previewVariant, cal.previewStrength)
                }
                CalibrationOverlay.ACTIVE -> Unit
            }
        }
        if (!s.enabled) return MaskSource.None
        return if (s.useMap && active != null) {
            MaskSource.Map(active, s.mapVariant, s.mapStrength)
        } else {
            MaskSource.Param(s.params)
        }
    }

    companion object {
        private const val TAG = "EdgeFix"
        private const val CHANNEL = "overlay"
        private const val NOTIFICATION_ID = 1

        /** Knallroter, deckender Rahmen (24 px) zur Prüfung, ob das Overlay überhaupt erscheint. */
        fun testFrame(w: Int, h: Int, t: Int = 24): IntArray {
            val px = IntArray(w * h)
            val red = 0xFFFF0000.toInt()
            for (y in 0 until h) for (x in 0 until w) {
                if (x < t || y < t || x >= w - t || y >= h - t) px[y * w + x] = red
            }
            return px
        }

        data class Diag(
            val serviceStarted: Int = 0,
            val windowAdded: Boolean = false,
            val windowError: String? = null,
            val size: String? = null,
            val source: String? = null,
            val nonZeroPixels: Int = 0,
            val maxAlpha: Int = 0,
            val renderMs: Long = 0,
            val log: List<String> = emptyList(),
        )

        private val _diag = MutableStateFlow(Diag())
        val diagnostics: StateFlow<Diag> = _diag.asStateFlow()

        private fun diag(event: String, transform: (Diag) -> Diag) {
            Log.i(TAG, event)
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMANY).format(java.util.Date())
            _diag.update { transform(it).copy(log = (listOf("$t  $event") + it.log).take(12)) }
        }

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Startet den Dienst, wenn Korrektur an (oder Kalibrierung läuft) und Berechtigung vorhanden ist. */
        fun sync(context: Context) {
            val repo = SettingsRepository.get(context)
            val s = repo.settings.value
            val i = Intent(context, OverlayService::class.java)
            if ((s.enabled || repo.calibration.value != null || repo.testFrame.value) && OverlayPermission.granted(context)) {
                ContextCompat.startForegroundService(context, i)
            } else {
                context.stopService(i)
            }
        }
    }
}
