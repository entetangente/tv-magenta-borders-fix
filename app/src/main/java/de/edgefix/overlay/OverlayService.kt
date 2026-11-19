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
import de.edgefix.core.EdgeMaskRenderer
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Vordergrunddienst, der das Overlay-Fenster hält. Läuft, solange die Korrektur
 * eingeschaltet ist, und beendet sich selbst, wenn sie ausgeschaltet wird.
 */
class OverlayService : LifecycleService() {

    private lateinit var repo: SettingsRepository
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
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        goForeground()

        if (!OverlayPermission.granted(this)) {
            Log.w(TAG, "Keine Overlay-Berechtigung, Dienst wird beendet")
            _running.value = false
            stopSelf()
            return
        }
        _running.value = true

        ContextCompat.registerReceiver(
            this, screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        attach()

        // Ausschalten beendet den Dienst.
        lifecycleScope.launch {
            repo.settings.map { it.enabled }.distinctUntilChanged().collect { enabled ->
                if (!enabled) stopSelf()
            }
        }
        // Vorher/Nachher: Fenster nur ausblenden, Maske bleibt erhalten.
        lifecycleScope.launch {
            repo.bypass.collect { b -> view?.visibility = if (b) android.view.View.INVISIBLE else android.view.View.VISIBLE }
        }
        // Maske bei jeder Parameter- oder Größenänderung einmal neu berechnen.
        lifecycleScope.launch {
            combine(repo.settings.map { it.params }.distinctUntilChanged(), size.filterNotNull()) { p, s -> p to s }
                .collectLatest { (params, s) ->
                    val (w, h) = s
                    val bmp = withContext(Dispatchers.Default) {
                        val t0 = System.nanoTime()
                        val px = EdgeMaskRenderer.render(params, w, h)
                        MaskBitmap.fromPremultiplied(px, w, h).also {
                            Log.d(TAG, "Maske ${w}x$h in ${(System.nanoTime() - t0) / 1_000_000} ms")
                        }
                    }
                    view?.setBitmap(bmp) ?: bmp.recycle()
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
        val v = OverlayView(this) { w, h -> size.value = w to h }
        v.visibility = if (repo.bypass.value) android.view.View.INVISIBLE else android.view.View.VISIBLE
        try {
            wm.addView(v, layoutParams())
            view = v
        } catch (e: Exception) {
            Log.e(TAG, "Overlay-Fenster konnte nicht angelegt werden", e)
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

    companion object {
        private const val TAG = "EdgeFix"
        private const val CHANNEL = "overlay"
        private const val NOTIFICATION_ID = 1

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Startet den Dienst, wenn Korrektur an und Berechtigung vorhanden ist. */
        fun sync(context: Context) {
            val s = SettingsRepository.get(context).settings.value
            val i = Intent(context, OverlayService::class.java)
            if (s.enabled && OverlayPermission.granted(context)) {
                ContextCompat.startForegroundService(context, i)
            } else {
                context.stopService(i)
            }
        }
    }
}
