package vip.sazanuwu.vrgproxy.core

import android.content.Context
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import vip.sazanuwu.vrgproxy.store.Prefs
import java.io.ByteArrayInputStream

/**
 * Готовит конфиг для ядра.
 *
 * Правила, DNS и маршрутизация лежат в assets/base_config.yaml и пользователю
 * не видны — это часть приложения. Из ссылки берутся только серверы, причём
 * скачивает и разбирает её само ядро через proxy-provider: приложению не надо
 * знать ни про YAML, ни про base64, ни про формат конкретной панели.
 *
 * Базовый конфиг пересобирается из шаблона скриптом tools/build_base_config.py.
 */
object ConfigBuilder {

    private const val ASSET = "base_config.yaml"
    private const val SUBSCRIPTION_PLACEHOLDER = "__SUBSCRIPTION_URL__"
    /** Имя proxy-provider'а в базовом конфиге. */
    const val PROVIDER = "subscription"

    /** Имя основной группы. Ноды в ней появляются после того, как ядро скачает подписку. */
    const val MAIN_GROUP = "VrgProxy"

    class BuildException(message: String, cause: Throwable? = null) : Exception(message, cause)

    @Suppress("UNCHECKED_CAST")
    fun build(context: Context, prefs: Prefs, apiPort: Int): String {
        if (prefs.subscriptionUrl.isBlank()) {
            throw BuildException("Ссылка не указана")
        }

        val raw = try {
            context.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            throw BuildException("Базовый конфиг не найден в сборке", e)
        }

        val loader = LoaderOptions().apply {
            codePointLimit = 32 * 1024 * 1024
            maxAliasesForCollections = 1000
        }
        val root = Yaml(SafeConstructor(loader))
            .load<Any>(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))
                as? MutableMap<String, Any?>
            ?: throw BuildException("Базовый конфиг повреждён")

        // Подписка -> провайдер. Ядро само сходит по ссылке и разберёт её.
        val providers = root["proxy-providers"] as? MutableMap<String, Any?>
            ?: throw BuildException("В базовом конфиге нет proxy-providers")
        val provider = providers[PROVIDER] as? MutableMap<String, Any?>
            ?: throw BuildException("В базовом конфиге нет провайдера $PROVIDER")
        require(provider["url"] == SUBSCRIPTION_PLACEHOLDER || provider["url"] is String)
        provider["url"] = prefs.subscriptionUrl

        root["mixed-port"] = prefs.port
        root["external-controller"] = "127.0.0.1:$apiPort"
        root["secret"] = prefs.apiSecret

        if (prefs.authUser.isNotEmpty()) {
            root["authentication"] = listOf("${prefs.authUser}:${prefs.authPass}")
        } else {
            root.remove("authentication")
        }

        // Всё, что не попало в правила, тоже идёт через сервер.
        //
        // Выбора здесь намеренно нет. Конфиг заточен под VR и магазин Meta, и
        // если сервис заведёт новый домен, которого нет в списке, при обходе
        // мимо сервера он упрётся в блокировку — а человек решит, что сломалось
        // приложение. Выигрыш был бы только в скорости для трафика, которого у
        // шлема почти не бывает.
        val rules = (root["rules"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        root["rules"] = rules.filterNot {
            it.substringBefore(',').trim().uppercase() == "MATCH"
        } + "MATCH,$MAIN_GROUP"

        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
            width = 4096
        }
        return Yaml(options).dump(root)
    }
}
