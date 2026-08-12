package vip.sazanuwu.vrgproxy.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Определяет, поднят ли на телефоне сторонний VPN.
 *
 * Это важно для раздачи: пока активен VPN, маршрут по умолчанию уходит в
 * туннель. Запрос клиента приходит на wlan0, а ответ уезжает в tun0 и до
 * клиента не доходит — снаружи порт выглядит закрытым, хотя ядро слушает и
 * с самого телефона всё работает. Проверено: с включённым FlClash соединение
 * с ноутбука не устанавливается, при выключенном — устанавливается.
 */
object VpnDetector {

    fun isVpnActive(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return false

        return try {
            manager.allNetworks.any { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (e: Exception) {
            false
        }
    }
}
