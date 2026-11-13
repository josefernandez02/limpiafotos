package dev.catalogo.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dev.catalogo.core.Article
import dev.catalogo.core.Desk
import dev.catalogo.ui.MainActivity

/**
 * Keeps the app alive while articles are on their way to the PC (AI and upload),
 * so leaving the app, locking the phone or opening the camera does not cut
 * Gemini or the upload. It only shows a quiet notification with the queue, and
 * stops by itself as soon as nothing is left on this phone. The work itself is
 * done by [Drafts]; this only stops Android from freezing the app meanwhile.
 */
class DraftsService : Service() {

    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Preparando artículos"
        val notification = notification(this, text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notification)
        }
        running = true
        // The CPU stays awake for the AI answer and the upload even with the screen off (max. 30 min per start).
        if (wake?.isHeld != true) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "catalogo:borradores")
                .apply { acquire(30 * 60_000L) }
        }
        return START_NOT_STICKY
    }

    /** Android 15 limits background data work: when the time is up, stop quietly (it resumes next time). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        wake?.takeIf { it.isHeld }?.release()
        wake = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "borradores"
        private const val ID = 41
        private const val EXTRA_TEXT = "text"

        @Volatile
        private var running = false

        /**
         * Starts, updates or stops the service for [queue] (articles still on this phone).
         * Android only lets it start while the app is on screen; from the background a
         * failed start is ignored and the work simply goes on while the app is alive.
         */
        fun sync(context: Context, queue: List<Article>) {
            val app = context.applicationContext
            if (queue.isEmpty()) {
                if (running) app.stopService(Intent(app, DraftsService::class.java))
                return
            }
            val text = describe(queue)
            if (running) {
                (app.getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notification(app, text))
                return
            }
            runCatching {
                app.startForegroundService(Intent(app, DraftsService::class.java).putExtra(EXTRA_TEXT, text))
            }
        }

        private fun describe(queue: List<Article>): String {
            val ai = queue.count { it.desk?.status == Desk.PREPARING }
            val up = queue.size - ai
            return listOfNotNull(
                ai.takeIf { it > 0 }?.let { if (it == 1) "1 con la IA" else "$it con la IA" },
                up.takeIf { it > 0 }?.let { if (it == 1) "1 enviándose al PC" else "$it enviándose al PC" },
            ).joinToString(", ")
        }

        private fun notification(context: Context, text: String): Notification {
            val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Borradores en curso", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Mientras la IA y el envío al PC trabajan en segundo plano"
                        setShowBadge(false)
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(dev.catalogo.R.drawable.ic_launcher_monochrome)
                .setContentTitle("Preparando borradores")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .build()
        }
    }
}
