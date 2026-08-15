package vip.sazanuwu.vrgproxy.service

/**
 * JNI-обёртка над hev-socks5-tunnel (легковесный C-модуль tun2socks).
 * Принимает файловый дескриптор TUN-интерфейса от [android.net.VpnService]
 * и перенаправляет весь трафик телефона в локальный SOCKS5-порт ядра mihomo (127.0.0.1:порт).
 */
object TProxyService {

    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    @JvmStatic
    external fun TProxyStopService(): Boolean

    @JvmStatic
    external fun TProxyIsRunning(): Boolean

    @JvmStatic
    external fun TProxyGetStats(): LongArray

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }
}
