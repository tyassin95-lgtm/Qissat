package tv.qissat.app.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Draws the remote-controlled pointer. Purely visual: it never takes focus or touches. */
class CursorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var pointerX = 0f
        private set
    var pointerY = 0f
        private set

    var clicking = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private val scale = 1.45f * density

    private val arrow = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 17f)
        lineTo(4.4f, 13.1f)
        lineTo(7.4f, 19.8f)
        lineTo(10.3f, 18.5f)
        lineTo(7.3f, 11.9f)
        lineTo(13f, 11.9f)
        close()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        strokeJoin = Paint.Join.ROUND
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 229, 57, 90)
        style = Paint.Style.FILL
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = INVISIBLE
    }

    fun moveTo(x: Float, y: Float) {
        pointerX = x
        pointerY = y
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (clicking) canvas.drawCircle(pointerX, pointerY, 20f * density, ring)
        canvas.save()
        canvas.translate(pointerX, pointerY)
        canvas.scale(scale, scale)
        canvas.save()
        canvas.translate(0.7f, 1.1f)
        canvas.drawPath(arrow, shadow)
        canvas.restore()
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, stroke)
        canvas.restore()
    }
}
