package vip.sazanuwu.vrgproxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import vip.sazanuwu.vrgproxy.MainActivity
import vip.sazanuwu.vrgproxy.R
import java.io.File

/**
 * Foreground-сервис и VpnService в одном лице:
 * 1. Держит ядро mihomo живым, пока приложение свёрнуто.
 * 2. Раздаёт HTTP/SOCKS5-прокси в локальную сеть для Quest 3, ПК и других устройств.
 * 3. Если включено в настройках, поднимает локальный VPN-туннель (tun2socks),
 *    чтобы трафик приложений самого телефона тоже прозрачно шёл через прокси.
 */
class ProxyService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        ProxyController.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                ProxyController.stopInternal()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                startForeground(NOTIFICATION_ID, buildNotification("Запускаю…", null))
                scope.launch {
                    ProxyController.startInternal(
                        onCoreReady = { port ->
                            startTunnelIfEnabled(port)
                        }
                    )
                }
                observeStatus()
            }
        }
        return START_STICKY
    }

    private fun startTunnelIfEnabled(port: Int) {
        val prefs = ProxyController.prefs()
        if (!prefs.useVpnOnDevice) {
            Log.i(TAG, "Local VPN on device is disabled in settings")
            ProxyController.setVpnRunning(false)
            return
        }

        // Проверяем, дано ли разрешение на VPN
        if (prepare(this) != null) {
            Log.w(TAG, "VPN permission not granted, running only LAN proxy")
            ProxyController.setVpnRunning(false)
            return
        }

        try {
            stopTunnel()

            val builder = Builder()
                .setSession("VRG Прокси")
                .setMtu(TUNNEL_MTU)
                .addAddress(TUNNEL_IPV4, 30)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)

            // Исключаем собственное приложение и процесс ядра из VPN,
            // чтобы избежать зацикливания исходящего трафика прокси.
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "Failed to exclude own package from VPN", e)
            }

            builder.allowBypass()

            val pfd = builder.establish() ?: run {
                Log.e(TAG, "VpnService.Builder.establish() returned null")
                ProxyController.setVpnRunning(false)
                return
            }
            vpnInterface = pfd

            val configFile = writeTunnelConfig(port)
            val started = TProxyService.TProxyStartService(configFile.absolutePath, pfd.fd)
            if (!started) {
                Log.e(TAG, "TProxyStartService failed to start")
                stopTunnel()
            } else {
                Log.i(TAG, "Local VPN tunnel started successfully")
                ProxyController.setVpnRunning(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local VPN tunnel", e)
            stopTunnel()
        }
    }

    private fun stopTunnel() {
        try {
            if (TProxyService.TProxyIsRunning()) {
                TProxyService.TProxyStopService()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TProxy", e)
        }
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null
        ProxyController.setVpnRunning(false)
    }

    private fun writeTunnelConfig(port: Int): File {
        val dir = File(filesDir, "tunnel").apply { mkdirs() }
        val file = File(dir, "tunnel.yaml")
        file.writeText(
            """
            tunnel:
              name: tun0
              mtu: $TUNNEL_MTU
              ipv4: $TUNNEL_IPV4
              ipv6: '$TUNNEL_IPV6'

            socks5:
              port: $port
              address: 127.0.0.1
              udp: 'udp'
            """.trimIndent()
        )
        return file
    }

    override fun onRevoke() {
        stopTunnel()
        ProxyController.requestStop(this)
        super.onRevoke()
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
                    ProxyController.State.RUNNING -> {
                        val sub = if (status.vpnRunning) "Телефон + LAN" else "Раздача"
                        "$sub: ${status.proxyLine}" to "клиентов: ${status.clients}"
                    }

                    ProxyController.State.STARTING ->
                        "Запускаю…" to status.progress

                    ProxyController.State.ERROR ->
                        "Ошибка" to (status.error?.lineSequence()?.firstOrNull() ?: "")

                    ProxyController.State.STOPPED ->
                        "Остановлено" to ""
                }
                manager.notify(NOTIFICATION_ID, buildNotification(title, text))

                if (status.state == ProxyController.State.STOPPED && everLeftStopped) {
                    stopTunnel()
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
            description = "Показывает адрес прокси и состояние VPN, пока раздача включена"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopTunnel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ProxyService"
        const val ACTION_START = "vip.sazanuwu.vrgproxy.START"
        const val ACTION_STOP = "vip.sazanuwu.vrgproxy.STOP"
        private const val CHANNEL_ID = "proxy"
        private const val NOTIFICATION_ID = 1

        private const val TUNNEL_MTU = 8500
        private const val TUNNEL_IPV4 = "172.19.0.1"
        private const val TUNNEL_IPV6 = "fc00::1"
    }
}
