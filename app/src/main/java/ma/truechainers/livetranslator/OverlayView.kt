package ma.truechainers.livetranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import kotlin.math.max

/**
 * Calque plein écran, transparent et NON touchable : il dessine chaque traduction
 * exactement par-dessus le texte d'origine. Les touches passent à l'app en dessous.
 */
class OverlayView(ctx: Context) : View(ctx) {

    data class Item(val rect: Rect, val text: String)

    private var items: List<Item> = emptyList()
    private val density = resources.displayMetrics.density
    private val loc = IntArray(2)

    private val bg = Paint().apply { color = 0xF5FFFFFF.toInt() }
    private val edge = Paint().apply {
        color = 0x443F51B5
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111111.toInt() }

    fun show(newItems: List<Item>) {
        items = newItems
        invalidate()
    }

    fun clear() {
        if (items.isEmpty()) return
        items = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc) // aligne coordonnées écran ↔ calque
        for (item in items) {
            val r = Rect(item.rect).apply { offset(-loc[0], -loc[1]) }
            canvas.drawRect(r, bg)
            canvas.drawRect(r, edge)
            drawFitted(canvas, item.text, r)
        }
    }

    private fun drawFitted(canvas: Canvas, text: String, r: Rect) {
        val pad = (2 * density).toInt()
        val width = max(r.width() - 2 * pad, (12 * density).toInt())
        var size = (r.height() * 0.62f).coerceIn(8 * density, 20 * density)
        var layout: StaticLayout
        while (true) {
            tp.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, width)
                .setIncludePad(false)
                .setMaxLines(8)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            if (layout.height <= r.height() || size <= 7 * density) break
            size -= 0.5f * density
        }
        canvas.save()
        val dy = max(0, (r.height() - layout.height) / 2)
        canvas.translate((r.left + pad).toFloat(), (r.top + dy).toFloat())
        layout.draw(canvas)
        canvas.restore()
    }
}
