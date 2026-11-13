package com.gbalink.online

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.SparseIntArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Controles táctiles de GBA dibujados encima de toda la pantalla.
 *
 * - Modo juego: multitáctil; cada dedo puede presionar uno o varios botones y deslizarse
 *   de uno a otro. Los toques que no caen sobre un control pasan a lo que hay debajo
 *   (por ejemplo los botones ☰ y 💬).
 * - Modo edición: se arrastra cada control a otro lugar y se cambia su tamaño. La
 *   disposición se guarda por separado para vertical y horizontal.
 */
class ControlsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Dónde se guardan las disposiciones (lo provee la actividad). */
    interface Store {
        fun loadLayout(key: String): String?
        fun saveLayout(key: String, json: String?)
        var opacity: Float
    }

    var onKeysChanged: ((Int) -> Unit)? = null
    var onSelectionChanged: ((String?) -> Unit)? = null
    var store: Store? = null
        set(value) {
            field = value
            if (width > 0) applyLayout()
        }

    private enum class Kind { DPAD, ROUND, PILL, SMALL_PILL }

    private class Element(val id: String, val key: Int, val label: String, val kind: Kind) {
        var cx = 0f
        var cy = 0f
        var scale = 1f
        var size = 0f // radio (cruceta/redondos) o media altura (píldoras)
        val halfWidth get() = if (kind == Kind.PILL || kind == Kind.SMALL_PILL) size * WIDTH_RATIO[kind]!! else size
        val bounds = RectF()

        fun updateBounds() {
            val hw = halfWidth
            bounds.set(cx - hw, cy - size, cx + hw, cy + size)
        }
    }

    private val elements = listOf(
        Element("dpad", 0, "", Kind.DPAD),
        Element("a", Keys.A, "A", Kind.ROUND),
        Element("b", Keys.B, "B", Kind.ROUND),
        Element("l", Keys.L, "L", Kind.PILL),
        Element("r", Keys.R, "R", Kind.PILL),
        Element("select", Keys.SELECT, "SELECT", Kind.SMALL_PILL),
        Element("start", Keys.START, "START", Kind.SMALL_PILL),
    )
    private val dpad = elements[0]

    var editMode = false
        set(value) {
            field = value
            selected = null
            pointerKeys.clear()
            setKeys(0)
            invalidate()
        }
    private var selected: Element? = null
        set(value) {
            field = value
            onSelectionChanged?.invoke(value?.let { nameOf(it) })
        }
    private var dragId = -1
    private var dragDx = 0f
    private var dragDy = 0f

    private val pointerKeys = SparseIntArray()
    private var keys = 0

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val editStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val path = Path()
    private val rect = RectF()

    private val orientationKey get() = if (width > height) "land" else "port"

    // ---------- Disposición ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        applyLayout()
    }

    private fun unit() = min(width, height).toFloat()

    private fun baseSize(kind: Kind): Float = unit() * when (kind) {
        Kind.DPAD -> 0.17f
        Kind.ROUND -> 0.078f
        Kind.PILL -> 0.045f
        Kind.SMALL_PILL -> 0.032f
    }

    private fun resize(e: Element) {
        e.size = baseSize(e.kind) * e.scale
        e.updateBounds()
    }

    /** Disposición de fábrica según la orientación y el tamaño de la pantalla. */
    private fun applyDefaults() {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 14f * density
        elements.forEach {
            it.scale = 1f
            it.size = baseSize(it.kind)
        }
        val a = elements[1]
        val b = elements[2]
        val l = elements[3]
        val r = elements[4]
        val select = elements[5]
        val start = elements[6]
        if (w <= h) {
            // Vertical: el juego arriba (alto = 2/3 del ancho) y los controles debajo.
            val top = w * 2f / 3f + 28f * density
            val bottom = h - pad
            l.cx = pad + l.halfWidth; l.cy = top + pad + l.size
            r.cx = w - pad - r.halfWidth; r.cy = l.cy
            select.cx = w / 2f - select.halfWidth - pad / 2f; select.cy = bottom - select.size
            start.cx = w / 2f + start.halfWidth + pad / 2f; start.cy = select.cy
            val mid = (l.cy + l.size + select.cy - select.size) / 2f + dpad.size * 0.1f
            dpad.cx = pad + dpad.size + w * 0.02f; dpad.cy = mid
            a.cx = w - pad - a.size - w * 0.03f; a.cy = mid - a.size * 0.6f
            b.cx = a.cx - a.size * 2.4f; b.cy = mid + a.size * 0.6f
        } else {
            // Horizontal: el juego al centro y los controles a los costados.
            l.cx = pad + l.halfWidth; l.cy = pad + l.size + 8f * density
            r.cx = w - pad - r.halfWidth; r.cy = l.cy
            dpad.cx = pad + dpad.size + 6f * density; dpad.cy = h * 0.58f
            a.cx = w - pad - a.size - 6f * density; a.cy = h * 0.50f
            b.cx = a.cx - a.size * 1.5f; b.cy = a.cy + a.size * 2.1f
            select.cx = pad + select.halfWidth; select.cy = h - pad - select.size
            start.cx = w - pad - start.halfWidth; start.cy = select.cy
        }
        elements.forEach { it.updateBounds() }
    }

    private fun applyLayout() {
        if (width == 0 || height == 0) return
        applyDefaults()
        val json = store?.loadLayout(orientationKey)
        if (json != null) {
            runCatching {
                val obj = JSONObject(json)
                for (e in elements) {
                    val arr = obj.optJSONArray(e.id) ?: continue
                    e.cx = (arr.getDouble(0) * width).toFloat()
                    e.cy = (arr.getDouble(1) * height).toFloat()
                    e.scale = arr.getDouble(2).toFloat().coerceIn(MIN_SCALE, MAX_SCALE)
                    resize(e)
                }
            }
        }
        invalidate()
    }

    private fun saveLayout() {
        val obj = JSONObject()
        for (e in elements) {
            obj.put(e.id, JSONArray().put((e.cx / width).toDouble()).put((e.cy / height).toDouble()).put(e.scale.toDouble()))
        }
        store?.saveLayout(orientationKey, obj.toString())
    }

    fun resetLayout() {
        store?.saveLayout(orientationKey, null)
        selected = null
        applyLayout()
    }

    /** Cambia el tamaño del control elegido. Devuelve false si no hay ninguno elegido. */
    fun resizeSelected(delta: Float): Boolean {
        val e = selected ?: return false
        e.scale = (e.scale + delta).coerceIn(MIN_SCALE, MAX_SCALE)
        resize(e)
        clampInside(e)
        saveLayout()
        invalidate()
        return true
    }

    fun changeOpacity(delta: Float) {
        val s = store ?: return
        s.opacity = (s.opacity + delta).coerceIn(0.15f, 1f)
        invalidate()
    }

    private fun clampInside(e: Element) {
        e.cx = e.cx.coerceIn(e.halfWidth * 0.5f, width - e.halfWidth * 0.5f)
        e.cy = e.cy.coerceIn(e.size * 0.5f, height - e.size * 0.5f)
        e.updateBounds()
    }

    private fun nameOf(e: Element) = when (e.kind) {
        Kind.DPAD -> "Cruceta"
        else -> e.label
    }

    // ---------- Dibujo ----------

    override fun onDraw(canvas: Canvas) {
        val alpha = ((store?.opacity ?: 0.6f) * 255).toInt().coerceIn(30, 255)
        val idle = Color.argb(alpha * 55 / 255 + 10, 255, 255, 255)
        val press = Color.argb(alpha * 150 / 255 + 40, 255, 120, 190)
        stroke.color = Color.argb(alpha * 140 / 255 + 20, 255, 255, 255)
        text.color = Color.argb(alpha * 220 / 255 + 30, 255, 255, 255)

        for (e in elements) {
            when (e.kind) {
                Kind.DPAD -> drawDpad(canvas, idle, press)
                Kind.ROUND -> {
                    fill.color = if (keys and e.key != 0) press else idle
                    canvas.drawCircle(e.cx, e.cy, e.size, fill)
                    canvas.drawCircle(e.cx, e.cy, e.size, stroke)
                    text.textSize = e.size * 0.8f
                    canvas.drawText(e.label, e.cx, e.cy + text.textSize * 0.35f, text)
                }
                Kind.PILL, Kind.SMALL_PILL -> {
                    fill.color = if (keys and e.key != 0) press else idle
                    canvas.drawRoundRect(e.bounds, e.size, e.size, fill)
                    canvas.drawRoundRect(e.bounds, e.size, e.size, stroke)
                    text.textSize = min(e.size * 0.9f, 16f * density * e.scale)
                    canvas.drawText(e.label, e.cx, e.cy + text.textSize * 0.35f, text)
                }
            }
            if (editMode) {
                editStroke.color = if (e === selected) Color.rgb(255, 126, 182) else Color.argb(200, 255, 255, 255)
                rect.set(e.bounds)
                rect.inset(-6f * density, -6f * density)
                canvas.drawRoundRect(rect, 8f * density, 8f * density, editStroke)
            }
        }
    }

    private fun drawDpad(canvas: Canvas, idle: Int, press: Int) {
        val r = dpad.size
        val arm = r * 0.36f
        val x = dpad.cx
        val y = dpad.cy
        path.reset()
        path.addRoundRect(RectF(x - arm, y - r, x + arm, y + r), arm * 0.4f, arm * 0.4f, Path.Direction.CW)
        path.addRoundRect(RectF(x - r, y - arm, x + r, y + arm), arm * 0.4f, arm * 0.4f, Path.Direction.CW)
        path.fillType = Path.FillType.WINDING
        fill.color = idle
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
        fill.color = press
        if (keys and Keys.UP != 0) canvas.drawRect(x - arm, y - r, x + arm, y - arm, fill)
        if (keys and Keys.DOWN != 0) canvas.drawRect(x - arm, y + arm, x + arm, y + r, fill)
        if (keys and Keys.LEFT != 0) canvas.drawRect(x - r, y - arm, x - arm, y + arm, fill)
        if (keys and Keys.RIGHT != 0) canvas.drawRect(x + arm, y - arm, x + r, y + arm, fill)
    }

    // ---------- Toques ----------

    /** Qué botones presiona un dedo en (x, y). Las zonas son algo más grandes que el dibujo. */
    private fun keysAt(x: Float, y: Float): Int {
        var result = 0
        for (e in elements) {
            when (e.kind) {
                Kind.DPAD -> {
                    val dx = x - e.cx
                    val dy = y - e.cy
                    val dist = hypot(dx, dy)
                    if (dist <= e.size * 1.4f && dist >= e.size * 0.18f) {
                        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).let { if (it < 0) it + 360 else it }
                        result = result or when ((((angle + 22.5) % 360) / 45).toInt()) {
                            0 -> Keys.RIGHT
                            1 -> Keys.RIGHT or Keys.DOWN
                            2 -> Keys.DOWN
                            3 -> Keys.DOWN or Keys.LEFT
                            4 -> Keys.LEFT
                            5 -> Keys.LEFT or Keys.UP
                            6 -> Keys.UP
                            else -> Keys.UP or Keys.RIGHT
                        }
                    }
                }
                Kind.ROUND -> if (hypot(x - e.cx, y - e.cy) <= e.size * 1.3f) result = result or e.key
                Kind.PILL, Kind.SMALL_PILL -> {
                    val slack = e.size * 0.7f
                    if (x >= e.bounds.left - slack && x <= e.bounds.right + slack &&
                        y >= e.bounds.top - slack && y <= e.bounds.bottom + slack
                    ) {
                        result = result or e.key
                    }
                }
            }
        }
        return result
    }

    private fun elementAt(x: Float, y: Float): Element? {
        val slack = 10f * density
        return elements.lastOrNull {
            x >= it.bounds.left - slack && x <= it.bounds.right + slack &&
                y >= it.bounds.top - slack && y <= it.bounds.bottom + slack
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        if (editMode) onEditTouch(event) else onPlayTouch(event)

    private fun onPlayTouch(event: MotionEvent): Boolean {
        val action = event.actionMasked
        // Un dedo que empieza fuera de los controles se deja pasar a lo de abajo.
        if (action == MotionEvent.ACTION_DOWN && keysAt(event.x, event.y) == 0) return false
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    pointerKeys.put(event.getPointerId(i), keysAt(event.getX(i), event.getY(i)))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> pointerKeys.delete(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointerKeys.clear()
        }
        var newKeys = 0
        for (i in 0 until pointerKeys.size()) newKeys = newKeys or pointerKeys.valueAt(i)
        setKeys(newKeys)
        return true
    }

    private fun setKeys(newKeys: Int) {
        if (newKeys == keys) return
        if (newKeys and keys.inv() != 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        keys = newKeys
        onKeysChanged?.invoke(keys)
        invalidate()
    }

    private fun onEditTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val e = elementAt(event.x, event.y)
                selected = e
                if (e != null) {
                    dragId = event.getPointerId(0)
                    dragDx = e.cx - event.x
                    dragDy = e.cy - event.y
                } else {
                    dragId = -1
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val e = selected
                val idx = event.findPointerIndex(dragId)
                if (e != null && idx >= 0) {
                    e.cx = event.getX(idx) + dragDx
                    e.cy = event.getY(idx) + dragDy
                    clampInside(e)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragId >= 0) saveLayout()
                dragId = -1
            }
        }
        return true
    }

    fun clear() {
        pointerKeys.clear()
        setKeys(0)
    }

    companion object {
        private const val MIN_SCALE = 0.5f
        private const val MAX_SCALE = 2.2f
        private val WIDTH_RATIO = mapOf(Kind.PILL to 3.0f, Kind.SMALL_PILL to 2.9f)
    }
}
