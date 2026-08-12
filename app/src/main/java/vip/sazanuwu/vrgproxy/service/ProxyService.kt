package vip.sazanuwu.vrgproxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import vip.sazanuwu.vrgproxy.MainActivity
import vip.sazanuwu.vrgproxy.R

/**
 * Держит ядро живым, пока приложение свёрнуто. Без foreground-сервиса Android
 * убивает процесс через несколько минут и раздача обрывается на середине загрузки.
 */
class ProxyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ProxyController.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ProxyController.stopInternal()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                startForeground(NOTIFICATION_ID, buildNotification("Запускаю…", null))
                scope.launch { ProxyController.startInternal() }
                observeStatus()
            }
        }
        return START_STICKY
    }

    private fun observeStatus() {
        scope.launch {
            // Первое значение потока — ещё STOPPED, запуск только уходит в работу.
            // Без этого флага сервис принимал его за «раздачу выключили», убивал
            // себя и вместе с собой отменял корутину запуска.
            var everLeftStopped = false

            ProxyController.status.collectLatest { status ->
                if (status.state != ProxyController.State.STOPPED) everLeftStopped = true
                val manager = getSystemService(NotificationManager::class.java)
                val (title, text) = when (status.state) {
                    ProxyController.State.RUNNING ->
                        "Раздача включена" to "${status.proxyLine}  ·  клиентов: ${status.clients}"

                    ProxyController.State.STARTING ->
                        "Запускаю…" to status.progress

                    ProxyController.State.ERROR ->
                        "Ошибка" to (status.error?.lineSequence()?.firstOrNull() ?: "")

                    ProxyController.State.STOPPED ->
                        "Остановлено" to ""
                }
                manager.notify(NOTIFICATION_ID, buildNotification(title, text))

                if (status.state == ProxyController.State.STOPPED && everLeftStopped) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun buildNotification(title: String, text: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Выключить", stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Раздача прокси",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Показывает адрес прокси, пока раздача включена"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "vip.sazanuwu.vrgproxy.START"
        const val ACTION_STOP = "vip.sazanuwu.vrgproxy.STOP"
        private const val CHANNEL_ID = "proxy"
        private const val NOTIFICATION_ID = 1
    }
}
