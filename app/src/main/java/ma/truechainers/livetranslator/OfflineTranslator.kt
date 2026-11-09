package ma.truechainers.livetranslator

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * Traduction 100 % sur le téléphone (Google ML Kit).
 * Les modèles (~30 Mo par langue) se téléchargent une fois depuis l'app.
 * ⚠️ En Chine, ce téléchargement passe par les serveurs Google → VPN pour cette étape seulement.
 */
class OfflineTranslator {

    private val manager = RemoteModelManager.getInstance()
    private val langId = LanguageIdentification.getClient()
    private val translators = mutableMapOf<String, Translator>()
    private val downloaded = mutableMapOf<String, Boolean>()
    private val cache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 8000
    }

    suspend fun translate(texts: List<String>, targetCode: String): List<String> {
        val target = TranslateLanguage.fromLanguageTag(targetCode) ?: return texts
        if (!isDownloaded(target)) return texts

        val dominant = runCatching { langId.identifyLanguage(texts.joinToString("\n")).await() }.getOrNull()

        return texts.map { t ->
            cache["$targetCode|$t"]?.let { return@map it }
            val src = sourceOf(t, dominant) ?: return@map t
            if (src == target || !isDownloaded(src)) return@map t
            val out = runCatching { translator(src, target).translate(t).await() }.getOrNull()
            if (out != null) cache["$targetCode|$t"] = out
            out ?: t
        }
    }

    /** Télécharge les modèles demandés. Retourne la liste des langues en échec. */
    suspend fun download(codes: Collection<String>): List<String> {
        val failed = mutableListOf<String>()
        for (code in codes) {
            val lang = TranslateLanguage.fromLanguageTag(code) ?: continue
            if (lang == TranslateLanguage.ENGLISH) continue
            val ok = runCatching {
                manager.download(TranslateRemoteModel.Builder(lang).build(), DownloadConditions.Builder().build()).await()
            }.isSuccess
            if (!ok) failed += code
        }
        downloaded.clear()
        return failed
    }

    suspend fun isDownloaded(lang: String): Boolean {
        if (lang == TranslateLanguage.ENGLISH) return true // l'anglais est intégré
        downloaded[lang]?.let { return it }
        val ok = runCatching {
            manager.isModelDownloaded(TranslateRemoteModel.Builder(lang).build()).await()
        }.getOrDefault(false)
        downloaded[lang] = ok
        return ok
    }

    private fun translator(src: String, tgt: String): Translator =
        translators.getOrPut("$src>$tgt") {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
        }

    /** Langue d'un fragment : par l'écriture (fiable même sur 2 caractères), sinon langue dominante de l'écran. */
    private fun sourceOf(t: String, dominant: String?): String? {
        var han = 0; var kana = 0; var hangul = 0; var arabic = 0; var latin = 0; var cyr = 0
        for (ch in t) {
            when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HANGUL -> hangul++
                Character.UnicodeScript.ARABIC -> arabic++
                Character.UnicodeScript.LATIN -> latin++
                Character.UnicodeScript.CYRILLIC -> cyr++
                else -> {}
            }
        }
        return when {
            kana > 0 -> TranslateLanguage.JAPANESE
            hangul > 0 -> TranslateLanguage.KOREAN
            han > 0 -> TranslateLanguage.CHINESE
            arabic > 0 -> TranslateLanguage.ARABIC
            cyr > 0 -> TranslateLanguage.RUSSIAN
            latin > 0 -> dominant?.let { TranslateLanguage.fromLanguageTag(it) }
                ?.takeIf { it in LATIN_LANGS } ?: TranslateLanguage.ENGLISH
            else -> null
        }
    }

    companion object {
        private val LATIN_LANGS = setOf(
            TranslateLanguage.ENGLISH, TranslateLanguage.FRENCH, TranslateLanguage.SPANISH,
            TranslateLanguage.GERMAN, TranslateLanguage.ITALIAN, TranslateLanguage.PORTUGUESE,
            TranslateLanguage.DUTCH, TranslateLanguage.TURKISH, TranslateLanguage.INDONESIAN,
            TranslateLanguage.VIETNAMESE,
        )
    }
}
