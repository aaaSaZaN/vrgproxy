package vip.sazanuwu.vrgproxy.core

import org.json.JSONObject
import vip.sazanuwu.vrgproxy.store.Prefs
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder

/** Тонкая обёртка над RESTful API ядра (external-controller). */
class ClashApi(private val secret: String, private val apiPort: Int) {

    private val base = "http://127.0.0.1:$apiPort"

    /**
     * Почему последний запрос не удался. Нужно, чтобы «ядро не отвечает»
     * не оставалось загадкой: в сообщение об ошибке попадает конкретная причина.
     */
    @Volatile
    var lastError: String? = null
        private set

    data class Traffic(val up: Long, val down: Long)

    /** Ядро поднялось и отвечает? */
    fun isAlive(): Boolean = request("GET", "/version") != null

    /**
     * Кодирование имени прокси для подстановки в путь URL.
     *
     * URLEncoder кодирует пробел как "+", но в пути это буквальный плюс, а не пробел,
     * поэтому ядро не находит ноду вроде "Finland - TCP" и отвечает 404.
     */
    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Переключить группу на конкретный сервер. */
    fun selectNode(group: String, node: String): Boolean {
        val path = "/proxies/" + encodePathSegment(group)
        val body = JSONObject().put("name", node).toString()
        return request("PUT", path, body) != null
    }

    /**
     * Серверы, которые ядро вытащило из подписки.
     *
     * Спрашиваем именно провайдер, а не группу: пока подписка не скачана,
     * группа пуста и ядро подставляет в неё заглушку COMPATIBLE, которую легко
     * принять за настоящий сервер.
     */
    fun providerNodes(provider: String): List<String> {
        val body = request("GET", "/providers/proxies/" + encodePathSegment(provider))
            ?: return emptyList()
        return runCatching {
            val proxies = JSONObject(body).optJSONArray("proxies") ?: return emptyList()
            (0 until proxies.length())
                .mapNotNull { proxies.optJSONObject(it)?.optString("name") }
                .filter { it.isNotEmpty() && it !in PLACEHOLDERS }
        }.getOrDefault(emptyList())
    }

    private companion object {
        /** Служебные псевдо-прокси ядра — настоящими серверами не являются. */
        val PLACEHOLDERS = setOf("COMPATIBLE", "DIRECT", "REJECT", "PASS", "REJECT-DROP")
    }

    /** Текущий выбранный сервер в группе, либо null. */
    fun currentNode(group: String): String? {
        val body = request("GET", "/proxies/" + encodePathSegment(group)) ?: return null
        return runCatching { JSONObject(body).optString("now").ifEmpty { null } }.getOrNull()
    }

    /** Суммарный трафик с момента запуска ядра. */
    fun traffic(): Traffic? {
        val body = request("GET", "/connections") ?: return null
        return runCatching {
            val json = JSONObject(body)
            Traffic(json.optLong("uploadTotal"), json.optLong("downloadTotal"))
        }.getOrNull()
    }

    /**
     * Адреса устройств, у которых прямо сейчас есть открытые соединения.
     *
     * Считать сами соединения нельзя: короткий запрос успевает закрыться между
     * опросами, и пользователь видит «клиентов: 0» при работающем клиенте.
     * Свои же адреса телефона отбрасываем — ядро ходит через себя за rule-провайдерами.
     */
    fun connectedClients(): Set<String> {
        val body = request("GET", "/connections") ?: return emptySet()
        return runCatching {
            val array = JSONObject(body).optJSONArray("connections") ?: return emptySet()
            (0 until array.length())
                .mapNotNull { array.optJSONObject(it)?.optJSONObject("metadata")?.optString("sourceIP") }
                .filter { it.isNotEmpty() && !it.startsWith("127.") && it != "::1" }
                .toSet()
        }.getOrDefault(emptySet())
    }

    /**
     * Проверяет разом все серверы группы. Возвращает задержку по каждому,
     * который ответил; не ответившие в ответе просто отсутствуют.
     */
    fun groupDelays(
        group: String,
        testUrl: String = "https://cp.cloudflare.com/generate_204"
    ): Map<String, Int> {
        val path = "/group/" + encodePathSegment(group) +
                "/delay?timeout=5000&url=" + URLEncoder.encode(testUrl, "UTF-8")
        val body = request("GET", path, timeoutMs = 15_000) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(body)
            json.keys().asSequence()
                .mapNotNull { key -> json.optInt(key).takeIf { it > 0 }?.let { key to it } }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    /** Задержка до сервера, мс. null — не ответил. */
    fun delay(node: String, testUrl: String = "https://cp.cloudflare.com/generate_204"): Int? {
        val path = "/proxies/" + encodePathSegment(node) +
                "/delay?timeout=3000&url=" + URLEncoder.encode(testUrl, "UTF-8")
        val body = request("GET", path, timeoutMs = 6000) ?: return null
        return runCatching { JSONObject(body).optInt("delay").takeIf { it > 0 } }.getOrNull()
    }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        timeoutMs: Int = 3000
    ): String? = try {
        // Proxy.NO_PROXY обязателен: HttpURLConnection по умолчанию уважает
        // системный прокси, и если в Wi-Fi телефона прописан наш же прокси,
        // запрос к 127.0.0.1 уходит через него — а он в этот момент ещё
        // не поднялся. Ядро при этом работает, но выглядит как не отвечающее.
        val conn = (URL(base + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Authorization", "Bearer $secret")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            body?.let { conn.outputStream.use { out -> out.write(it.toByteArray()) } }
            val code = conn.responseCode
            when {
                code == 204 -> {
                    lastError = null
                    ""
                }

                code in 200..299 -> {
                    lastError = null
                    conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                }

                else -> {
                    lastError = "$method $path вернул HTTP $code"
                    null
                }
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        lastError = "$method $path: ${e.javaClass.simpleName}: ${e.message}"
        null
    }
}
