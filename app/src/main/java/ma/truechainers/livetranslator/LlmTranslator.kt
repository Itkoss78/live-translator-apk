package ma.truechainers.livetranslator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Traduction en ligne par lots, avec cache : seuls les textes jamais vus partent à l'API. */
class LlmTranslator {

    private val cache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 8000
    }
    private val recent = ArrayDeque<String>()

    fun cached(text: String, target: String): String? = synchronized(cache) { cache["$target|$text"] }

    suspend fun translate(texts: List<String>, prefs: Prefs): List<String> = withContext(Dispatchers.IO) {
        val target = prefs.target
        val missing = texts.filter { cached(it, target) == null }.distinct()
        if (missing.isNotEmpty()) {
            // Lots de 60 max pour garder des réponses rapides
            for (chunk in missing.chunked(60)) {
                val out = call(chunk, prefs)
                synchronized(cache) { chunk.zip(out).forEach { (s, d) -> cache["$target|$s"] = d } }
            }
            synchronized(recent) {
                missing.forEach { recent.addLast(it) }
                while (recent.size > 40) recent.removeFirst()
            }
        }
        texts.map { cached(it, target) ?: it }
    }

    private fun call(texts: List<String>, prefs: Prefs): List<String> {
        val p = prefs.provider
        val lang = Languages.name(prefs.target)
        val system = "Tu es un moteur de traduction d'interface en temps réel. On te donne des fragments " +
            "de texte affichés à l'écran d'une application mobile (boutons, menus, messages, titres, prix). " +
            "Traduis chaque fragment en $lang, de façon courte et naturelle, comme dans une interface localisée. " +
            "Laisse tel quel ce qui est déjà en $lang, les nombres, prix, codes, URL et noms propres. " +
            "Réponds UNIQUEMENT avec un tableau JSON de chaînes, même longueur et même ordre que l'entrée."
        val ctx = synchronized(recent) { recent.takeLast(15).joinToString(" | ") }
        val user = "Contexte récent : $ctx\n\nÀ traduire :\n${JSONArray(texts)}"

        val endpoint = prefs.endpoint(p)
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 10000
            doOutput = true
            setRequestProperty("content-type", "application/json")
        }

        val body = JSONObject()
            .put("model", prefs.model(p))
            .put("max_tokens", 4096)
            .put("temperature", 0)
        if (p == Provider.CLAUDE) {
            if (endpoint.contains("api.anthropic.com")) {
                conn.setRequestProperty("x-api-key", prefs.key(p))
                conn.setRequestProperty("anthropic-version", "2023-06-01")
            } else {
                conn.setRequestProperty("x-app-token", prefs.key(p)) // ton proxy
            }
            body.put("system", system)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        } else {
            conn.setRequestProperty("Authorization", "Bearer ${prefs.key(p)}")
            body.put("stream", false)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)))
        }

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code != 200) throw IOException("${p.label} HTTP $code : ${resp.take(160)}")

            val text = if (p == Provider.CLAUDE) {
                val arr = JSONObject(resp).getJSONArray("content")
                buildString { for (i in 0 until arr.length()) append(arr.getJSONObject(i).optString("text")) }
            } else {
                JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content")
            }

            val s = text.indexOf('[')
            val e = text.lastIndexOf(']')
            if (s < 0 || e <= s) throw IOException("Réponse JSON invalide")
            val arr = JSONArray(text.substring(s, e + 1))
            if (arr.length() != texts.size) throw IOException("Nombre de traductions incorrect")
            return List(arr.length()) { i -> arr.optString(i, texts[i]) }
        } finally {
            conn.disconnect()
        }
    }
}
