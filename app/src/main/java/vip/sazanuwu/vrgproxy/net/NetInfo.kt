package vip.sazanuwu.vrgproxy.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** Определение адреса, который надо вбить в клиенте (шлем Quest / ноутбуку). */
object NetInfo {

    private val WIFI_PREFIXES = listOf("wlan", "eth", "ap", "swlan", "rndis", "usb")

    data class Address(val ip: String, val iface: String, val isHotspot: Boolean)

    /**
     * Возвращает локальные IPv4 адреса телефона. Первым идёт тот, который
     * почти наверняка нужен пользователю: Wi-Fi, потом точка доступа, потом остальное.
     */
    fun localAddresses(): List<Address> {
        val result = mutableListOf<Address>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }

        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val name = iface.name.lowercase()
            if (WIFI_PREFIXES.none { name.startsWith(it) }) continue

            for (addr in iface.inetAddresses) {
                if (addr !is Inet4Address || addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                val hotspot = name.startsWith("ap") || name.startsWith("swlan") ||
                        name.startsWith("rndis") || name.startsWith("usb")
                result += Address(addr.hostAddress ?: continue, iface.name, hotspot)
            }
        }

        return result.sortedBy { addr ->
            when {
                addr.iface.startsWith("wlan") && !addr.isHotspot -> 0
                addr.isHotspot -> 1
                else -> 2
            }
        }
    }

    fun primaryIp(): String? = localAddresses().firstOrNull()?.ip
}
