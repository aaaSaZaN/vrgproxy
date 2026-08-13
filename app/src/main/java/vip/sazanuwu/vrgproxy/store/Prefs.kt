package vip.sazanuwu.vrgproxy.store

import android.content.Context
import vip.sazanuwu.vrgproxy.BuildConfig
import java.security.SecureRandom

/** Настройки приложения. Хранятся в обычном SharedPreferences — их немного. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("vrgproxy", Context.MODE_PRIVATE)

    /**
     * Ссылка на подписку. Из неё берутся только серверы — правила, DNS и
     * маршрутизация лежат в базовом конфиге приложения и пользователю не видны.
     */
    var subscriptionUrl: String
        get() = sp.getString(KEY_SUB, DEFAULT_SUB)!!
        set(value) = sp.edit().putString(KEY_SUB, value.trim()).apply()

    var port: Int
        get() = sp.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = sp.edit().putInt(KEY_PORT, value).apply()

    var authUser: String
        get() = sp.getString(KEY_AUTH_USER, "")!!
        set(value) = sp.edit().putString(KEY_AUTH_USER, value.trim()).apply()

    var authPass: String
        get() = sp.getString(KEY_AUTH_PASS, "")!!
        set(value) = sp.edit().putString(KEY_AUTH_PASS, value).apply()

    var selectedNode: String
        get() = sp.getString(KEY_NODE, "")!!
        set(value) = sp.edit().putString(KEY_NODE, value).apply()

    /** Секрет RESTful API ядра. Генерируется один раз на установку. */
    val apiSecret: String
        get() {
            sp.getString(KEY_SECRET, null)?.let { return it }
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            val secret = bytes.joinToString("") { "%02x".format(it) }
            sp.edit().putString(KEY_SECRET, secret).apply()
            return secret
        }

    companion object {
        const val DEFAULT_PORT = 7890
        const val API_PORT = 9090

        // Подписка по умолчанию — оставить пустой для публикации.
        /**
         * Ссылка по умолчанию. Подставляется при сборке из переменной окружения
         * SUBSCRIPTION_URL (в CI — из секрета репозитория), в исходниках её нет.
         * Если переменная не задана, поле остаётся пустым и ссылку вводят руками.
         */
        val DEFAULT_SUB: String = BuildConfig.DEFAULT_SUBSCRIPTION

        private const val KEY_SUB = "sub_url"
        private const val KEY_PORT = "port"
        private const val KEY_AUTH_USER = "auth_user"
        private const val KEY_AUTH_PASS = "auth_pass"
        private const val KEY_NODE = "selected_node"
        private const val KEY_SECRET = "api_secret"
    }
}
