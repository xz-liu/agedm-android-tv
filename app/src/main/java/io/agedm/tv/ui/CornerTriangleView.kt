package io.agedm.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class CornerTriangleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    var triangleColor: Int = Color.TRANSPARENT
        set(value) {
            field = value
            invalidate()
        }

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        if (triangleColor == Color.TRANSPARENT) return
        paint.color = triangleColor
        path.rewind()
        path.moveTo(0f, 0f)
        path.lineTo(width.toFloat(), 0f)
        path.lineTo(width.toFloat(), height.toFloat())
        path.close()
        canvas.drawPath(path, paint)
    }
}
