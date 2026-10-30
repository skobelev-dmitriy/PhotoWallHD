package app.photowall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Keeps the upload web server alive (so you can add photos any time, even when the
 * slideshow is not on screen) and starts the show at boot / when the schedule says so.
 */
class ServerService : Service() {
    private var server: HttpServer? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var wasActive = true

    private val tick = object : Runnable {
        override fun run() {
            val active = prefs.inActiveWindow()
            if (prefs.scheduleEnabled && prefs.autostart && active && !wasActive) launchSlideshow(this@ServerService)
            wasActive = active
            handler.postDelayed(this, 20_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        wasActive = prefs.inActiveWindow()
        goForeground()
        val s = HttpServer(WebApi(this)::handle)
        if (s.start(DEFAULT_PORT)) {
            server = s
            port = s.port
        }
        handler.postDelayed(tick, 20_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_BOOT, false) == true && prefs.autostart && prefs.inActiveWindow()) {
            // give the launcher time to come up before we put a window on top of it
            handler.postDelayed({ launchSlideshow(this) }, 15_000)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "PhotoWall HD", NotificationManager.IMPORTANCE_MIN))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val n = builder
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("PhotoWall HD")
            .setContentText(tr("Загрузка фото по сети включена", "Photo upload server is running"))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID, n)
        } catch (e: Exception) {
            // not allowed to be foreground right now; the server keeps running as a plain service
        }
    }

    companion object {
        private const val DEFAULT_PORT = 8080
        private const val CHANNEL = "photowall"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_BOOT = "boot"

        /** The port the web server actually bound to. */
        @Volatile
        var port = DEFAULT_PORT

        fun start(ctx: Context, boot: Boolean) {
            val i = Intent(ctx, ServerService::class.java).putExtra(EXTRA_BOOT, boot)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                // background start not permitted; the service will start next time the app is opened
            }
        }

        fun launchSlideshow(ctx: Context) {
            if (Remote.active) return
            try {
                ctx.startActivity(Intent(ctx, SlideshowActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                // blocked by the system (needs the "display over other apps" permission)
            }
        }
    }
}
