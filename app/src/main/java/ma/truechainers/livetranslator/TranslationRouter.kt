package ma.truechainers.livetranslator

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Choix automatique du moteur :
 *   réseau OK + clé renseignée → DeepSeek / Qwen / Claude
 *   pas de réseau, erreur ou timeout → ML Kit hors-ligne
 * Après un échec en ligne, on reste hors-ligne 30 s pour ne pas ralentir chaque écran.
 */
class TranslationRouter(private val ctx: Context) {

    val llm = LlmTranslator()
    val offline = OfflineTranslator()
    @Volatile private var backoffUntil = 0L

    data class Result(val lines: List<String>, val online: Boolean, val engine: String, val error: String?)

    fun cached(text: String, target: String) = llm.cached(text, target)

    suspend fun translate(texts: List<String>, prefs: Prefs): Result {
        val p = prefs.provider
        var error: String? = null
        if (isOnline() && System.currentTimeMillis() >= backoffUntil && prefs.key(p).isNotBlank()) {
            try {
                return Result(llm.translate(texts, prefs), true, p.label, null)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                backoffUntil = System.currentTimeMillis() + 30_000
            }
        }
        return Result(offline.translate(texts, prefs.target), false, "Hors-ligne", error)
    }

    private fun isOnline(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        // Pas de test "VALIDATED" : en Chine, la vérification Google échoue alors que le réseau marche.
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
