package ma.truechainers.livetranslator

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var endpoint: EditText
    private lateinit var key: EditText
    private lateinit var model: EditText
    private lateinit var offlineBox: LinearLayout
    private lateinit var offlineStatus: TextView
    private var shownProvider = Provider.DEEPSEEK
    private val scope = MainScope()
    private val router get() = (application as App).router

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(40))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        status = TextView(this).apply { textSize = 15f; setPadding(0, dp(6), 0, dp(6)) }
        root.addView(status)

        // Test
        title("Test")
        val testOut = TextView(this).apply { textSize = 13f; setPadding(0, dp(4), 0, dp(4)) }
        button("Tester la traduction") {
            saveProviderFields()
            testOut.text = "Test en cours…"
            scope.launch {
                val src = listOf("你好，欢迎光临", "确认订单", "购物车")
                val r = router.translate(src, prefs)
                testOut.text = "Moteur : ${r.engine}\n" + src.indices.joinToString("\n") { "${src[it]} → ${r.lines[it]}" } +
                    (r.error?.let { "\n⚠️ Erreur en ligne : $it" } ?: "") +
                    (if (r.lines == src) "\n❌ Rien traduit : clé API absente/invalide et langues hors-ligne non téléchargées" else "")
            }
        }
        root.addView(testOut)

        // 1 · Accessibilité
        title("1 · Activer le service")
        note("Accessibilité → Applications installées → Live Translator → Activer.\n" +
            "Si c'est grisé (« paramètre restreint ») : Infos de l'application → menu ⋮ → " +
            "« Autoriser les paramètres restreints », puis réessaie.")
        button("Ouvrir les réglages d'accessibilité") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("Infos de l'application (paramètres restreints, batterie)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        note("Sur Xiaomi / Huawei / Oppo : mets aussi la batterie en « Aucune restriction » " +
            "pour que le service ne soit pas coupé.")

        // 2 · Langue
        title("2 · Traduire vers")
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Languages.all.map { it.second })
        spinner.setSelection(Languages.all.indexOfFirst { it.first == prefs.target }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.target = Languages.all[pos].first
                buildOfflineList()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(spinner)

        // 3 · En ligne
        title("3 · En ligne (si réseau OK)")
        note("DeepSeek et Qwen fonctionnent en Chine sans VPN. En cas d'échec, bascule automatique hors-ligne.")
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        Provider.entries.forEach { p ->
            group.addView(RadioButton(this).apply { text = p.label; id = View.generateViewId(); tag = p })
        }
        root.addView(group)
        endpoint = field("Endpoint", InputType.TYPE_TEXT_VARIATION_URI)
        key = field("Clé API", InputType.TYPE_TEXT_VARIATION_PASSWORD)
        model = field("Modèle", InputType.TYPE_CLASS_TEXT)

        shownProvider = prefs.provider
        loadProviderFields()
        for (i in 0 until group.childCount) {
            val rb = group.getChildAt(i) as RadioButton
            if (rb.tag == shownProvider) rb.isChecked = true
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            saveProviderFields()
            shownProvider = g.findViewById<RadioButton>(checkedId).tag as Provider
            prefs.provider = shownProvider
            loadProviderFields()
        }
        button("Enregistrer") { saveProviderFields(); refreshStatus() }

        // 4 · Hors-ligne
        title("4 · Hors-ligne (ML Kit, sur le téléphone)")
        note("À faire une fois. Le téléchargement passe par Google : en Chine, active ton VPN juste pour cette étape.")
        offlineBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(offlineBox)
        offlineStatus = TextView(this).apply { setPadding(0, dp(6), 0, 0) }
        button("Télécharger les langues cochées") { downloadOffline() }
        root.addView(offlineStatus)
        buildOfflineList()

        // Aide
        title("Utilisation")
        note("La bulle « 译 » flotte sur l'écran : touche-la pour afficher / masquer la traduction, " +
            "glisse-la pour la déplacer.\n" +
            "Couleur : vert = en ligne · bleu = hors-ligne · jaune = en cours · orange = API en échec · gris = masqué.")
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onPause() {
        saveProviderFields()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------- Logique ----------

    private fun serviceEnabled(): Boolean {
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.contains("$packageName/")
    }

    private fun refreshStatus() {
        val svc = when {
            !serviceEnabled() -> "⚠️ Service désactivé (étape 1)"
            !ScreenTranslatorService.running -> "⚠️ Service activé mais coupé par le système : désactive / réactive-le"
            else -> "✅ Service actif"
        }
        val p = prefs.provider
        val online = if (prefs.key(p).isBlank()) "⚠️ Pas de clé ${p.label} → hors-ligne seulement" else "✅ ${p.label} configuré"
        status.text = "$svc\n$online\nCible : ${Languages.name(prefs.target)}" +
            "\n\nDernier écran : ${ScreenTranslatorService.diag}"
    }

    private fun loadProviderFields() {
        endpoint.setText(prefs.endpoint(shownProvider))
        key.setText(prefs.key(shownProvider))
        model.setText(prefs.model(shownProvider))
    }

    private fun saveProviderFields() {
        prefs.save(shownProvider, endpoint.text.toString(), key.text.toString(), model.text.toString())
    }

    private fun buildOfflineList() {
        if (!::offlineBox.isInitialized) return
        offlineBox.removeAllViews()
        val selected = prefs.offlineSources
        Languages.all.filter { it.first != prefs.target && it.first != "en" }.forEach { (code, name) ->
            offlineBox.addView(CheckBox(this).apply {
                text = name
                isChecked = code in selected
                setOnCheckedChangeListener { _, on ->
                    prefs.offlineSources = if (on) prefs.offlineSources + code else prefs.offlineSources - code
                }
            })
        }
        scope.launch {
            val tgtOk = router.offline.isDownloaded(prefs.target)
            val ok = prefs.offlineSources.filter { router.offline.isDownloaded(it) }
            offlineStatus.text = "Cible ${Languages.name(prefs.target)} : ${if (tgtOk) "✓" else "à télécharger"}" +
                "\nInstallées : ${ok.joinToString { Languages.name(it) }.ifEmpty { "aucune" }} (+ anglais intégré)"
        }
    }

    private fun downloadOffline() {
        val langs = prefs.offlineSources + prefs.target
        offlineStatus.text = "Téléchargement en cours… (~30 Mo par langue)"
        scope.launch {
            val failed = router.offline.download(langs)
            offlineStatus.text = if (failed.isEmpty()) "✓ Langues prêtes hors-ligne"
            else "✗ Échec : ${failed.joinToString { Languages.name(it) }} — vérifie le VPN et réessaie"
            buildOfflineList()
        }
    }

    // ---------- Petits helpers d'UI ----------

    private fun title(t: String) = root.addView(TextView(this).apply {
        text = t
        textSize = 18f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(22), 0, dp(6))
    })

    private fun note(t: String) = root.addView(TextView(this).apply {
        text = t
        textSize = 13f
        alpha = 0.75f
        setPadding(0, 0, 0, dp(6))
    })

    private fun button(label: String, onClick: () -> Unit) = root.addView(Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    })

    private fun field(hint: String, type: Int): EditText = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or type
        isSingleLine = true
        textSize = 14f
    }.also { root.addView(it) }
}
