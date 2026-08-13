package vip.sazanuwu.vrgproxy.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** Определение адреса, который надо вбить в клиенте (шлем Quest / ноутбуку). */
object NetInfo {

    /**
     * Интерфейсы, на которых клиента быть не может.
     *
     * Раньше здесь был белый список имён (wlan, ap, swlan...), и это оказалось
     * ненадёжно: точка доступа называется по-разному у разных производителей,
     * и на неизвестном имени приложение писало «нет сети», хотя раздача шла.
     * Поэтому теперь наоборот — берём всё, кроме заведомо лишнего.
     */
    private val SKIP_PREFIXES = listOf(
        "rmnet", "ccmni", "pdp", "clat",   // мобильный интернет
        "tun", "ppp", "ipsec", "utun",     // VPN-туннели
        "dummy", "sit", "ip6tnl", "lo"     // служебные
    )

    enum class Kind { WIFI, HOTSPOT, OTHER }

    data class Address(val ip: String, val iface: String, val kind: Kind) {
        val label: String
            get() = when (kind) {
                Kind.WIFI -> "Wi-Fi"
                Kind.HOTSPOT -> "Точка доступа"
                Kind.OTHER -> iface
            }
    }

    /**
     * Локальные IPv4-адреса телефона, по которым к нему может подключиться клиент.
     * Первым идёт точка доступа: если она поднята, клиент почти наверняка в ней.
     */
    fun localAddresses(): List<Address> {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }

        val result = mutableListOf<Address>()
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val name = iface.name.lowercase()
            if (SKIP_PREFIXES.any { name.startsWith(it) }) continue

            val kind = when {
                name.startsWith("ap") || name.startsWith("swlan") ||
                        name.startsWith("softap") || name == "wlan1" ||
                        name.startsWith("rndis") || name.startsWith("usb") -> Kind.HOTSPOT

                name.startsWith("wlan") || name.startsWith("eth") -> Kind.WIFI
                else -> Kind.OTHER
            }

            for (addr in iface.inetAddresses) {
                if (addr !is Inet4Address || addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                result += Address(addr.hostAddress ?: continue, iface.name, kind)
            }
        }

        return result.sortedBy { addr ->
            when (addr.kind) {
                Kind.HOTSPOT -> 0
                Kind.WIFI -> 1
                Kind.OTHER -> 2
            }
        }
    }

    fun primaryIp(): String? = localAddresses().firstOrNull()?.ip

    /**
     * Что вообще видит система: имя интерфейса и его адреса.
     *
     * Нужно, когда адрес не нашёлся. Иначе «нет сети» — тупик: непонятно,
     * телефон правда не в сети или мы не распознали интерфейс.
     */
    fun describeInterfaces(): String {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Exception) {
            return "не удалось прочитать список интерфейсов: ${e.message}"
        }

        val lines = interfaces.mapNotNull { iface ->
            if (iface.isLoopback) return@mapNotNull null
            val ips = iface.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .joinToString(", ") { it.hostAddress.orEmpty() }
                .ifEmpty { "без IPv4" }
            "${iface.name}: $ips${if (iface.isUp) "" else " (выключен)"}"
        }

        return lines.joinToString("\n").ifEmpty { "интерфейсов не найдено" }
    }
}
