package com.gbalink.online

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * Raíz de la pantalla de juego. Acomoda la pantalla de la GBA (proporción 3:2) según la
 * orientación; el resto de los hijos (controles, barra de edición) se acomodan como en
 * un FrameLayout normal, encima de todo.
 *
 *   Vertical:   pantalla arriba a todo el ancho, línea de estado debajo, controles abajo.
 *   Horizontal: pantalla centrada a toda la altura, controles a los costados y la línea
 *               de estado arriba, encima del juego.
 */
class GameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    val isLandscape get() = width > height

    private var screenW = 0
    private var screenH = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = measuredWidth
        val h = measuredHeight
        if (w > h) {
            screenH = h
            screenW = h * 3 / 2
            if (screenW > w) {
                screenW = w
                screenH = w * 2 / 3
            }
        } else {
            screenW = w
            screenH = w * 2 / 3
        }
        findViewById<View>(R.id.screenFrame)?.measure(exactly(screenW), exactly(screenH))
        findViewById<View>(R.id.statusText)?.measure(
            // En horizontal deja libres las esquinas del juego, donde están ☰ y 💬.
            if (w > h) View.MeasureSpec.makeMeasureSpec(screenW - (112 * resources.displayMetrics.density).toInt(), View.MeasureSpec.AT_MOST) else exactly(w),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val w = right - left
        val h = bottom - top
        val screen = findViewById<View>(R.id.screenFrame) ?: return
        val status = findViewById<View>(R.id.statusText)
        if (w > h) {
            val x = (w - screenW) / 2
            val y = (h - screenH) / 2
            screen.layout(x, y, x + screenW, y + screenH)
            status?.let {
                val sx = (w - it.measuredWidth) / 2
                it.layout(sx, y, sx + it.measuredWidth, y + it.measuredHeight)
            }
        } else {
            screen.layout(0, 0, screenW, screenH)
            status?.layout(0, screenH, w, screenH + status.measuredHeight)
        }
    }

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
