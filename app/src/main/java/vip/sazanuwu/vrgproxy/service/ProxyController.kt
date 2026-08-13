package vip.sazanuwu.vrgproxy.service

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vip.sazanuwu.vrgproxy.core.ClashApi
import vip.sazanuwu.vrgproxy.core.ConfigBuilder
import vip.sazanuwu.vrgproxy.core.MihomoCore
import vip.sazanuwu.vrgproxy.net.NetInfo
import vip.sazanuwu.vrgproxy.net.VpnDetector
import vip.sazanuwu.vrgproxy.store.Prefs

/**
 * Единая точка правды о состоянии раздачи. Живёт на весь процесс: и экран,
 * и foreground-сервис смотрят сюда, чтобы не расходиться в показаниях.
 */
object ProxyController {

    enum class State { STOPPED, STARTING, RUNNING, ERROR }

    data class Status(
        val state: State = State.STOPPED,
        val ip: String? = null,
        /** Все адреса, по которым телефон доступен: Wi-Fi, точка доступа, USB. */
        val addresses: List<NetInfo.Address> = emptyList(),
        val port: Int = Prefs.DEFAULT_PORT,
        val nodes: List<String> = emptyList(),
        val currentNode: String = "",
        val mainGroup: String = "",
        val clients: Int = 0,
        val up: Long = 0,
        val down: Long = 0,
        val ping: Int? = null,
        /** Результат проверки серверов: имя -> задержка в мс, null — не ответил. */
        val pings: Map<String, Int?> = emptyMap(),
        val checking: Boolean = false,
        /** На телефоне поднят сторонний VPN — клиенты снаружи не подключатся. */
        val vpnActive: Boolean = false,
        val error: String? = null,
        val progress: String = ""
    ) {
        val proxyLine: String get() = "${ip ?: "—"}:$port"
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private lateinit var appContext: Context
    private lateinit var prefs: Prefs
    private lateinit var core: MihomoCore
    private lateinit var api: ClashApi

    private val scope = CoroutineScope(Dispatchers.IO)
    private var pollJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Адрес клиента -> когда его соединения видели в последний раз. */
    private val recentClients = mutableMapOf<String, Long>()
    private const val CLIENT_TTL_MS = 60_000L

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        prefs = Prefs(appContext)
        core = MihomoCore(appContext)
        // Порт управления выбирается при каждом старте — до тех пор ходим
        // на стандартный, чтобы поле не было пустым.
        api = ClashApi(prefs.apiSecret, Prefs.API_PORT)
        refreshEnvironment()
    }

    /** Перечитать то, что меняется без нашего участия: адрес в сети и наличие VPN. */
    fun refreshEnvironment() {
        if (!::appContext.isInitialized) return
        _status.update {
            it.copy(
                port = prefs.port,
                ip = NetInfo.primaryIp(),
                addresses = NetInfo.localAddresses(),
                vpnActive = VpnDetector.isVpnActive(appContext)
            )
        }
    }

    fun prefs(): Prefs = prefs

    fun coreLog(): String = core.recentLog()

    // ------------------------------------------------------------------ запуск

    /** Точка входа с экрана: поднимает сервис, тот уже дёргает [startInternal]. */
    fun requestStart(context: Context) {
        val intent = Intent(context, ProxyService::class.java).setAction(ProxyService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun requestStop(context: Context) {
        context.startService(
            Intent(context, ProxyService::class.java).setAction(ProxyService.ACTION_STOP)
        )
    }

    internal suspend fun startInternal() = withContext(Dispatchers.IO) {
        if (_status.value.state == State.RUNNING || _status.value.state == State.STARTING) return@withContext

        _status.update {
            it.copy(state = State.STARTING, error = null, progress = "Запускаю…")
        }

        try {
            // Стандартный 9090 может держать другой клиент — берём свободный.
            val apiPort = core.pickApiPort()
            api = ClashApi(prefs.apiSecret, apiPort)
            val yaml = ConfigBuilder.build(appContext, prefs, apiPort)

            core.start(yaml, api, prefs.port, apiPort)

            _status.update { it.copy(progress = "Получаю список серверов…") }
            val group = ConfigBuilder.MAIN_GROUP
            val nodes = awaitNodes()
            if (nodes.isEmpty()) {
                throw ConfigBuilder.BuildException(
                    "По этой ссылке не нашлось ни одного сервера. Проверь ссылку."
                )
            }

            // Восстанавливаем ранее выбранный сервер, если он ещё есть.
            val desired = prefs.selectedNode.takeIf { it in nodes } ?: nodes.first()
            api.selectNode(group, desired)
            prefs.selectedNode = desired

            acquireLocks()
            _status.update {
                it.copy(
                    state = State.RUNNING,
                    ip = NetInfo.primaryIp(),
                    addresses = NetInfo.localAddresses(),
                    port = prefs.port,
                    nodes = nodes,
                    currentNode = desired,
                    mainGroup = group,
                    vpnActive = VpnDetector.isVpnActive(appContext),
                    error = null,
                    progress = ""
                )
            }
            startPolling()
        } catch (e: CancellationException) {
            // Отмена — не ошибка пользователя, показывать её на экране нечего.
            core.stop()
            releaseLocks()
            _status.update { it.copy(state = State.STOPPED, progress = "") }
            throw e
        } catch (e: Exception) {
            core.stop()
            releaseLocks()
            _status.update {
                it.copy(
                    state = State.ERROR,
                    progress = "",
                    error = e.message ?: "Неизвестная ошибка"
                )
            }
        }
    }

    internal fun stopInternal() {
        pollJob?.cancel()
        pollJob = null
        core.stop()
        releaseLocks()
        recentClients.clear()
        _status.update {
            it.copy(state = State.STOPPED, clients = 0, up = 0, down = 0, ping = null, progress = "")
        }
    }

    /**
     * Ждёт, пока ядро скачает подписку и разберёт её.
     * На холодном старте это занимает несколько секунд.
     */
    private suspend fun awaitNodes(): List<String> {
        val deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline) {
            val nodes = api.providerNodes(ConfigBuilder.PROVIDER)
            if (nodes.isNotEmpty()) return nodes
            delay(500)
        }
        return emptyList()
    }

    // ------------------------------------------------------------------ опрос

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var tick = 0
            while (isActive) {
                if (!core.isRunning) {
                    _status.update {
                        it.copy(
                            state = State.ERROR,
                            error = "Ядро неожиданно остановилось.\n${core.recentLog().takeLast(400)}"
                        )
                    }
                    releaseLocks()
                    break
                }
                api.traffic()?.let { t ->
                    _status.update { it.copy(up = t.up, down = t.down) }
                }
                val now = System.currentTimeMillis()
                api.connectedClients().forEach { ip -> recentClients[ip] = now }
                // Клиент, который минуту не открывал соединений, считается ушедшим.
                recentClients.entries.removeAll { now - it.value > CLIENT_TTL_MS }
                _status.update {
                    it.copy(
                        clients = recentClients.size,
                        ip = NetInfo.primaryIp(),
                        addresses = NetInfo.localAddresses(),
                        vpnActive = VpnDetector.isVpnActive(appContext)
                    )
                }
                // Пинг заметно дороже остальных запросов — раз в 30 секунд достаточно.
                if (tick % 15 == 0) {
                    val node = _status.value.currentNode
                    if (node.isNotEmpty()) {
                        val ping = api.delay(node)
                        _status.update { it.copy(ping = ping) }
                    }
                    // Провайдер сам обновляет подписку по расписанию — подхватываем.
                    val nodes = api.providerNodes(ConfigBuilder.PROVIDER)
                    if (nodes.isNotEmpty() && nodes != _status.value.nodes) {
                        _status.update { it.copy(nodes = nodes) }
                    }
                }
                tick++
                delay(2000)
            }
        }
    }

    // ------------------------------------------------------------- управление

    fun selectNode(node: String) {
        scope.launch {
            val group = _status.value.mainGroup
            if (group.isEmpty()) return@launch
            if (api.selectNode(group, node)) {
                prefs.selectedNode = node
                _status.update { it.copy(currentNode = node, ping = null) }
                // Замеряем сразу: иначе пинг нового сервера появится только
                // на следующем круге опроса, через полминуты.
                val ping = api.delay(node)
                _status.update { it.copy(ping = ping) }
            }
        }
    }

    /**
     * Проверяет все серверы разом и показывает задержку на каждом.
     * Не ответившие остаются в списке с прочерком — так видно, что сервер лежит,
     * а не что его просто не проверяли.
     */
    fun checkServers() {
        if (_status.value.checking || _status.value.state != State.RUNNING) return
        scope.launch {
            _status.update { it.copy(checking = true) }
            val measured = api.groupDelays(ConfigBuilder.MAIN_GROUP)
            val nodes = _status.value.nodes
            _status.update { current ->
                current.copy(
                    checking = false,
                    pings = nodes.associateWith { measured[it] },
                    ping = measured[current.currentNode] ?: current.ping
                )
            }
        }
    }

    /** Смена порта/режима/пароля требует перезапуска ядра с новым конфигом. */
    fun applySettingsAndRestart(context: Context) {
        scope.launch {
            val wasRunning = _status.value.state == State.RUNNING
            stopInternal()
            _status.update { it.copy(port = prefs.port) }
            if (wasRunning) startInternal()
        }
    }

    // ---------------------------------------------------------------- локи

    private fun acquireLocks() {
        val wifi = appContext.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "vrgproxy:wifi")
            .apply { setReferenceCounted(false); acquire() }

        val power = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vrgproxy:cpu")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        wakeLock = null
    }
}
