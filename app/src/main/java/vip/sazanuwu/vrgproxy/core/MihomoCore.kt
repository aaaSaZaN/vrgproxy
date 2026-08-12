package vip.sazanuwu.vrgproxy.core

import android.content.Context
import android.util.Log
import vip.sazanuwu.vrgproxy.store.Prefs
import java.io.File
import java.util.concurrent.ArrayBlockingQueue

/**
 * Запуск ядра mihomo отдельным процессом.
 *
 * Ядро лежит в jniLibs как libmihomo.so — это единственный каталог, из которого
 * Android разрешает исполнять файлы приложения (W^X). Копировать его в filesDir
 * и делать chmod +x бессмысленно: с Android 10 такие файлы запускать нельзя.
 */
class MihomoCore(private val context: Context) {

    private var process: Process? = null
    private var logThread: Thread? = null
    private val logBuffer = ArrayBlockingQueue<String>(400)

    val isRunning: Boolean
        get() = process?.isAlive == true

    val workDir: File
        get() = File(context.filesDir, "mihomo").apply { mkdirs() }

    private val executable: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libmihomo.so")

    class StartException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Пишет конфиг и поднимает ядро. Возвращает управление, когда RESTful API
     * начал отвечать, — то есть порт уже слушается и клиента можно подключать.
     */
    fun start(configYaml: String, api: ClashApi, port: Int) {
        stop()

        if (!executable.exists()) {
            throw StartException("Ядро не найдено в сборке (${executable.name})")
        }

        // Проверяем порты до запуска. Другой клиент (FlClash, Clash Meta,
        // Hiddify) занимает те же 7890 и 9090, и тогда наше ядро не стартует,
        // а запросы к API попадают в чужое приложение — со стороны это выглядит
        // как «ядро не отвечает» или «серверов не нашлось».
        if (!isPortFree(port)) {
            throw StartException(
                "Порт $port уже занят другим приложением — скорее всего, это " +
                    "другой VPN-клиент (FlClash, Clash, Hiddify). Закрой его " +
                    "полностью или смени порт в «Дополнительно»."
            )
        }

        val configFile = File(workDir, "config.yaml")
        configFile.writeText(configYaml)

        val builder = ProcessBuilder(
            executable.absolutePath,
            "-d", workDir.absolutePath,
            "-f", configFile.absolutePath
        ).apply {
            redirectErrorStream(true)
            directory(workDir)
            environment()["HOME"] = workDir.absolutePath
        }

        val proc = try {
            builder.start()
        } catch (e: Exception) {
            throw StartException("Не удалось запустить ядро", e)
        }
        process = proc
        startLogPump(proc)

        // На холодном старте ядро тянет два десятка rule-provider'ов, и делает
        // это через прокси — на медленной сети минуты не хватает с запасом.
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                val tail = recentLog().takeLast(600)
                throw StartException("Ядро завершилось при старте.\n$tail")
            }
            if (api.isAlive()) return
            Thread.sleep(300)
        }

        val reason = api.lastError ?: "причина неизвестна"
        stop()
        throw StartException(
            "Ядро запустилось, но не отвечает.\n" +
                "Последняя попытка: $reason\n\n${recentLog().takeLast(600)}"
        )
    }

    fun stop() {
        process?.let { proc ->
            runCatching { proc.destroy() }
            if (!proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                runCatching { proc.destroyForcibly() }
            }
        }
        process = null
        logThread?.interrupt()
        logThread = null
    }

    fun recentLog(): String = logBuffer.toList().joinToString("\n")

    /**
     * Свободен ли порт. Проверяем попыткой слушать: чужой сокет может висеть
     * на другом интерфейсе, и «подключиться и посмотреть» — ненадёжно.
     */
    private fun isPortFree(port: Int): Boolean = try {
        java.net.ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(java.net.InetSocketAddress(port))
        }
        true
    } catch (e: java.io.IOException) {
        false
    }

    /**
     * Свободный порт для управления ядром, начиная со стандартного.
     *
     * Другие клиенты (FlClash, Clash Meta, Hiddify) держат 9090 под свой
     * external-controller. Пользователю этот порт не виден и не важен, поэтому
     * молча берём соседний, а не заставляем разбираться.
     */
    fun pickApiPort(): Int =
        (Prefs.API_PORT until Prefs.API_PORT + 50).firstOrNull { isPortFree(it) }
            ?: Prefs.API_PORT

    private fun startLogPump(proc: Process) {
        logThread = Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    if (!logBuffer.offer(line)) {
                        logBuffer.poll()
                        logBuffer.offer(line)
                    }
                    Log.d(TAG, line)
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private companion object {
        const val TAG = "MihomoCore"
        const val START_TIMEOUT_MS = 60_000L
    }
}
