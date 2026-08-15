package vip.sazanuwu.vrgproxy

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import vip.sazanuwu.vrgproxy.service.ProxyController
import vip.sazanuwu.vrgproxy.ui.MainScreen
import vip.sazanuwu.vrgproxy.ui.theme.VrgTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                ProxyController.requestStart(this)
            } else {
                Toast.makeText(
                    this,
                    "Без разрешения VPN прокси работает только для других устройств (Quest)",
                    Toast.LENGTH_LONG
                ).show()
                ProxyController.requestStart(this)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ProxyController.init(this)
        askNotificationPermission()
        setContent {
            VrgTheme {
                MainScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // VPN могли включить или выключить, пока приложение было свёрнуто.
        ProxyController.refreshEnvironment()
    }

    /**
     * Запускает прокси с проверкой системного разрешения на VPN.
     * Если VPN на телефоне включён в настройках и разрешение ещё не дано,
     * открывает системный диалог подтверждения.
     */
    fun startProxyWithVpnCheck() {
        val prefs = ProxyController.prefs()
        if (prefs.useVpnOnDevice) {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
                return
            }
        }
        ProxyController.requestStart(this)
    }

    /** Без разрешения на уведомления foreground-сервис работает, но молча. */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
