package ma.truechainers.livetranslator

import android.app.Application
import android.content.Context

class App : Application() {
    val router by lazy { TranslationRouter(this) }
}

enum class Provider(val label: String, val defaultEndpoint: String, val defaultModel: String) {
    // DeepSeek et Qwen : format OpenAI, accessibles en Chine sans VPN.
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/chat/completions", "deepseek-chat"),
    // Compte Alibaba Cloud international. Compte Chine continentale :
    // https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
    QWEN("Qwen", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-plus"),
    CLAUDE("Claude", "https://api.anthropic.com/v1/messages", "claude-haiku-5-5"),
}

object Languages {
    val all = listOf(
        "fr" to "Français", "en" to "English", "ar" to "العربية", "zh" to "中文",
        "es" to "Español", "de" to "Deutsch", "it" to "Italiano", "ja" to "日本語", "ko" to "한국어",
    )
    fun name(code: String) = all.firstOrNull { it.first == code }?.second ?: code
}

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("live_translator", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var provider: Provider
        get() = runCatching { Provider.valueOf(sp.getString("provider", null) ?: "DEEPSEEK") }
            .getOrDefault(Provider.DEEPSEEK)
        set(v) = sp.edit().putString("provider", v.name).apply()

    var target: String
        get() = sp.getString("target", "fr") ?: "fr"
        set(v) = sp.edit().putString("target", v).apply()

    var offlineSources: Set<String>
        get() = sp.getStringSet("offline_sources", setOf("zh", "en")) ?: setOf("zh", "en")
        set(v) = sp.edit().putStringSet("offline_sources", v).apply()

    fun endpoint(p: Provider) = sp.getString("endpoint_${p.name}", null)?.takeIf { it.isNotBlank() } ?: p.defaultEndpoint
    fun key(p: Provider) = sp.getString("key_${p.name}", "") ?: ""
    fun model(p: Provider) = sp.getString("model_${p.name}", null)?.takeIf { it.isNotBlank() } ?: p.defaultModel

    fun save(p: Provider, endpoint: String, key: String, model: String) {
        sp.edit()
            .putString("endpoint_${p.name}", endpoint.trim())
            .putString("key_${p.name}", key.trim())
            .putString("model_${p.name}", model.trim())
            .apply()
    }
}
