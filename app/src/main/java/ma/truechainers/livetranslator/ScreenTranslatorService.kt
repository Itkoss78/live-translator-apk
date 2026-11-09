package ma.truechainers.livetranslator

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Cœur de l'app :
 *  1. lit le texte des autres apps via l'API d'accessibilité (positions exactes, pas d'OCR)
 *  2. le traduit (en ligne ou hors-ligne)
 *  3. dessine la traduction pile par-dessus, sur un calque qui laisse passer les touches.
 * Une bulle flottante « 译 » active / désactive l'affichage (pour revoir l'original).
 */
class ScreenTranslatorService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val router get() = (application as App).router

    private var overlay: OverlayView? = null
    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null

    private var visible = true
    private var scanJob: Job? = null
    private var translateJob: Job? = null
    private var latestTexts: List<String> = emptyList()
    private var lastTexts: List<String> = emptyList()
    private var lastLines: List<String> = emptyList()

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    // ---------- Cycle de vie ----------

    override fun onServiceConnected() {
        prefs = Prefs(this)
        wm = getSystemService(WindowManager::class.java)
        visible = prefs.enabled
        addOverlay()
        addBubble()
        setBubbleState(if (visible) State.IDLE else State.OFF)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        overlay?.let { runCatching { wm.removeView(it) } }
        bubble?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }

    // ---------- Événements ----------

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!visible) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        when (event.eventType) {
            // Le contenu bouge → on efface tout de suite (évite des traductions décalées)
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> overlay?.clear()
        }
        requestScan(350)
    }

    private var pendingSince = 0L

    /** Anti-rebond : attend que l'écran se stabilise, mais jamais plus d'1 s (apps qui s'animent sans arrêt). */
    private fun requestScan(delayMs: Long) {
        val now = System.currentTimeMillis()
        if (scanJob?.isActive == true) {
            if (delayMs > 0 && now - pendingSince > 1000) return
            scanJob?.cancel()
        } else {
            pendingSince = now
        }
        scanJob = scope.launch {
            delay(delayMs)
            scan()
        }
    }

    // ---------- Lecture de l'écran ----------

    private fun scan() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() == packageName) { overlay?.clear(); return }

        val dm = resources.displayMetrics
        val screen = Rect(0, 0, dm.widthPixels, dm.heightPixels + dp(200))
        val nodes = ArrayList<Pair<Rect, String>>()
        collect(root, nodes, 0, screen)
        if (nodes.isEmpty()) { overlay?.clear(); return }

        val texts = nodes.map { it.second }
        latestTexts = texts

        // Même écran qu'avant (simple repositionnement) → pas d'appel
        if (texts == lastTexts) { show(nodes, lastLines); return }

        // Affiche tout de suite ce qui est déjà en cache
        val target = prefs.target
        show(nodes, texts.map { router.cached(it, target) })

        if (translateJob?.isActive == true) return  // la fin du job relancera un scan
        translateJob = scope.launch {
            setBubbleState(State.WORKING)
            val r = router.translate(texts, prefs)
            lastTexts = texts
            lastLines = r.lines
            setBubbleState(when {
                r.online -> State.ONLINE
                r.error != null -> State.ERROR
                else -> State.OFFLINE
            })
            if (latestTexts == texts) show(nodes, r.lines) else requestScan(0)
        }
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<Pair<Rect, String>>, depth: Int, screen: Rect) {
        if (n == null || depth > 45 || out.size >= 160) return
        if (!n.isVisibleToUser) return

        val txt = (n.text ?: if (n.childCount == 0) n.contentDescription else null)?.toString()?.trim()
        if (!txt.isNullOrEmpty() && txt.any { it.isLetter() } && !n.isPassword && !(n.isEditable && n.isFocused)) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.intersect(screen) && r.width() > dp(6) && r.height() > dp(6)) {
                out += r to txt.take(400)
                return // un TextView n'a pas d'enfants utiles
            }
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1, screen)
    }

    private fun show(nodes: List<Pair<Rect, String>>, lines: List<String?>) {
        if (!visible) return
        val items = nodes.indices.mapNotNull { i ->
            val l = lines.getOrNull(i) ?: return@mapNotNull null
            if (l == nodes[i].second) null else OverlayView.Item(nodes[i].first, l) // déjà dans la bonne langue
        }
        overlay?.show(items)
    }

    // ---------- Fenêtres flottantes ----------

    private fun addOverlay() {
        val v = OverlayView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(v, lp)
        overlay = v
    }

    private fun addBubble() {
        val size = dp(46)
        val tv = TextView(this).apply {
            text = "译"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            elevation = dp(4).toFloat()
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - dp(8)
            y = dp(260)
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (dragging || abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        wm.updateViewLayout(tv, lp)
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) toggle()
            }
            true
        }
        wm.addView(tv, lp)
        bubble = tv
        bubbleLp = lp
    }

    private fun toggle() {
        visible = !visible
        prefs.enabled = visible
        if (visible) {
            setBubbleState(State.IDLE)
            lastTexts = emptyList()
            requestScan(0)
        } else {
            overlay?.clear()
            setBubbleState(State.OFF)
        }
    }

    private enum class State(val color: Int) {
        OFF(0xFF9E9E9E.toInt()),      // gris : traduction masquée
        IDLE(0xFF455A64.toInt()),     // ardoise : en attente
        WORKING(0xFFF9A825.toInt()),  // jaune : traduction en cours
        ONLINE(0xFF2E7D32.toInt()),   // vert : DeepSeek / Qwen / Claude
        OFFLINE(0xFF1565C0.toInt()),  // bleu : hors-ligne ML Kit
        ERROR(0xFFE65100.toInt()),    // orange : API en échec → hors-ligne
    }

    private fun setBubbleState(s: State) {
        if (s != State.OFF && !visible) return
        bubble?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(s.color)
            setStroke(dp(2), 0xCCFFFFFF.toInt())
        }
        bubble?.alpha = if (s == State.OFF) 0.6f else 0.92f
    }
}
