package vip.sazanuwu.vrgproxy

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

    /** Без разрешения на уведомления foreground-сервис работает, но молча. */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
